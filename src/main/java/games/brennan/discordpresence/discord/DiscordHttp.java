package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import org.slf4j.Logger;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Shared daemon HTTP executor + {@link HttpClient} for every Discord REST call and
 * the gateway WebSocket, plus a dedicated scheduler for gateway heartbeats. Daemon
 * threads, so a hanging request can never keep the JVM alive. The HTTP executor is a
 * small cached pool — NOT a single thread — because the JDK {@link java.net.http.WebSocket}
 * dispatches its onOpen/onText listener callbacks on this executor; one thread starves
 * them and the gateway hangs forever "awaiting HELLO".
 *
 * <p>The shared client/executors/timeout are {@code public} so sibling features outside this
 * package (e.g. the {@code reincarnation} relay client) reuse the same daemon HTTP infrastructure
 * rather than spinning up their own. The bot-request helpers stay package-private (Discord-only).</p>
 */
public final class DiscordHttp {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Longer per-request timeout for report posts that carry a composed/uploaded image (death &
     * disconnect reports). A multipart PNG — especially a full screenshot routed through the relay on a
     * slow uplink — can take well over the 10s {@link #TIMEOUT} that suffices for tiny JSON calls.
     */
    static final Duration REPORT_TIMEOUT = Duration.ofSeconds(30);

    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "DiscordPresence-HTTP");
        t.setDaemon(true);
        return t;
    });

    /**
     * Dedicated daemon scheduler for the gateway's heartbeat ticks and reconnect
     * backoff ({@link DiscordGateway}). Kept separate from {@link #EXECUTOR} so
     * heartbeat timing can never be starved behind in-flight HTTP/WebSocket
     * callbacks (which would trip false zombie-connection detection).
     */
    public static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-Gateway");
        t.setDaemon(true);
        return t;
    });

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .executor(EXECUTOR)
            .build();

    /**
     * Whether a bot REST call cannot proceed: direct-to-Discord mode with a blank token. In
     * relay-mode this is always {@code false} — the relay injects the token, so DP needs none.
     */
    static boolean botUnavailable() {
        return !DiscordPresenceConfig.isRelayMode() && DiscordPresenceConfig.getBotToken().isBlank();
    }

    /**
     * A request builder for a bot REST call (User-Agent + timeout). Adds the
     * {@code Authorization: Bot <token>} header only in direct mode; in relay-mode the header is
     * omitted because the relay holds and injects the token server-side.
     */
    static HttpRequest.Builder botRequest(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(TIMEOUT);
        if (!DiscordPresenceConfig.isRelayMode()) {
            builder.header("Authorization", "Bot " + DiscordPresenceConfig.getBotToken());
        }
        return builder;
    }

    // ── Retrying send ───────────────────────────────────────────────────
    // A single sendAsync drops a message on any transient hiccup (a 429 rate-limit,
    // a 5xx, or a connection that never landed) — Discord's reports then silently
    // vanish. This wraps the send so those provably-recoverable failures are retried,
    // honouring the server's Retry-After, while ambiguous failures (a read timeout
    // after the body went out — Discord may have created the message; webhooks have no
    // idempotency key) are NOT retried, so a report can never be duplicated.

    /** Total attempts (1 initial + 3 retries). */
    static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 8_000L;
    /** Cap a server-dictated Retry-After so a pathological header can't park a thread for minutes. */
    private static final long MAX_RETRY_AFTER_MS = 60_000L;

    /**
     * Send {@code request} on {@link #CLIENT}, retrying the provably-recoverable failures (HTTP 429 —
     * after the response's {@code Retry-After}/{@code X-RateLimit-Reset-After} — HTTP 5xx, and pre-send
     * connection failures) up to {@link #MAX_ATTEMPTS} total. Each wait is scheduled on the JDK's daemon
     * delay scheduler and the resend runs on {@link #EXECUTOR}, so a backoff never blocks the game thread.
     * The returned future completes with the final {@link HttpResponse} (success or a non-retryable status,
     * which the caller still inspects) or completes exceptionally when a retryable error is exhausted — so
     * a caller's existing {@code .exceptionally(...)} fallback keeps working unchanged.
     */
    public static CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request) {
        return attempt(request, 0);
    }

    private static CompletableFuture<HttpResponse<String>> attempt(HttpRequest request, int attemptIndex) {
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> evaluate(request, resp, err, attemptIndex))
                .thenCompose(next -> next);
    }

    private static CompletableFuture<HttpResponse<String>> evaluate(
            HttpRequest request, HttpResponse<String> resp, Throwable err, int attemptIndex) {
        boolean lastAttempt = attemptIndex >= MAX_ATTEMPTS - 1;
        if (err != null) {
            Throwable cause = unwrap(err);
            if (!lastAttempt && isRetryableException(cause)) {
                return retryAfter(request, attemptIndex, backoffMs(attemptIndex), cause.toString());
            }
            return CompletableFuture.failedFuture(cause); // exhausted / non-retryable → caller's exceptionally(...)
        }
        int code = resp.statusCode();
        if (!lastAttempt && isRetryableStatus(code)) {
            long delay = (code == 429) ? retryAfterMs(resp).orElse(backoffMs(attemptIndex)) : backoffMs(attemptIndex);
            return retryAfter(request, attemptIndex, delay, "HTTP " + code);
        }
        return CompletableFuture.completedFuture(resp); // success, or a non-retryable status the caller inspects
    }

    private static CompletableFuture<HttpResponse<String>> retryAfter(
            HttpRequest request, int attemptIndex, long delayMs, String reason) {
        LOGGER.warn("Discord {} {} failed ({}); retry {}/{} in {} ms",
                request.method(), request.uri().getPath(), reason, attemptIndex + 1, MAX_ATTEMPTS - 1, delayMs);
        Executor delayed = CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, EXECUTOR);
        return CompletableFuture.supplyAsync(() -> attempt(request, attemptIndex + 1), delayed)
                .thenCompose(next -> next);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    /** A status worth retrying: rate-limited (429) or a transient server error (5xx). */
    static boolean isRetryableStatus(int code) {
        return code == 429 || (code >= 500 && code < 600);
    }

    /**
     * Only failures where the request <em>provably never reached/was processed by</em> Discord — so a
     * resend can't duplicate the message. A read timeout ({@link java.net.http.HttpTimeoutException} that
     * is not a connect timeout) is deliberately excluded: the body may already be on the wire.
     */
    static boolean isRetryableException(Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof HttpConnectTimeoutException;
    }

    /** Exponential backoff (1s, 2s, 4s … capped) plus a little jitter, in ms, for attempt {@code index}. */
    static long backoffMs(int attemptIndex) {
        long base = Math.min(BASE_BACKOFF_MS << Math.max(0, attemptIndex), MAX_BACKOFF_MS);
        return base + ThreadLocalRandom.current().nextLong(250L);
    }

    /** The retry delay a 429 response dictates, from {@code Retry-After} then {@code X-RateLimit-Reset-After}. */
    private static OptionalLong retryAfterMs(HttpResponse<String> resp) {
        OptionalLong retryAfter = resp.headers().firstValue("Retry-After")
                .map(DiscordHttp::parseRetryAfterMillis).orElse(OptionalLong.empty());
        if (retryAfter.isPresent()) return retryAfter;
        return resp.headers().firstValue("X-RateLimit-Reset-After")
                .map(DiscordHttp::parseRetryAfterMillis).orElse(OptionalLong.empty());
    }

    /**
     * Parse a Discord rate-limit delay header (seconds, possibly fractional, e.g. {@code "1.5"}) into
     * milliseconds, rounded up and capped at {@link #MAX_RETRY_AFTER_MS}. Pure + package-visible for tests;
     * empty when the value is missing or unparseable.
     */
    static OptionalLong parseRetryAfterMillis(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return OptionalLong.empty();
        try {
            long ms = (long) Math.ceil(Double.parseDouble(headerValue.trim()) * 1000.0);
            return OptionalLong.of(Math.min(Math.max(0L, ms), MAX_RETRY_AFTER_MS));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private DiscordHttp() {}
}
