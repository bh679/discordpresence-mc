package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Persistent Discord <b>Gateway</b> (WebSocket) client — the inbound (Discord→game)
 * read path of the two-way bridge. Implements the v10 protocol by hand over the
 * JDK {@link WebSocket} (no JDA / Discord4J, preserving the mod's zero-dependency
 * property): {@code GET /gateway/bot} → HELLO/heartbeat (with ACK zombie
 * detection) → IDENTIFY (with the privileged Message Content intent) → DISPATCH
 * {@code MESSAGE_CREATE} → RESUME/reconnect with exponential backoff.
 *
 * <p>Best-effort, exactly like the REST clients: every failure is logged and
 * swallowed, and the connection self-heals. Fatal IDENTIFY errors (bad token,
 * disallowed intents) stop cleanly with an actionable log line instead of
 * reconnect-looping. All I/O is off the server thread; parsed messages are handed
 * to {@code onMessage} which is responsible for hopping back to the game thread.</p>
 */
final class DiscordGateway {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GATEWAY_BOT_URL = "https://discord.com/api/v10/gateway/bot";
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final String token;
    private final Consumer<InboundMessage> onMessage;

    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile boolean resuming;
    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private volatile Integer lastSeq;
    private volatile ScheduledFuture<?> heartbeatTask;

    private final AtomicBoolean heartbeatAckPending = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    /** Serialises all WebSocket sends — {@code sendText} throws if a send is already pending. */
    private final AtomicReference<CompletableFuture<WebSocket>> lastSend =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    DiscordGateway(String token, Consumer<InboundMessage> onMessage) {
        this.token = token;
        this.onMessage = onMessage;
    }

    // --- lifecycle ---

    void start() {
        if (token == null || token.isBlank()) {
            LOGGER.warn("Discord gateway not started: bot token is blank.");
            return;
        }
        running = true;
        connect(false);
    }

    void stop() {
        running = false;
        stopHeartbeat();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down");
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                ws.abort();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    // --- connect / reconnect ---

    private void connect(boolean resume) {
        if (!running) {
            return;
        }
        LOGGER.info("Discord gateway connect(resume={})…", resume);
        reconnecting.set(false);
        boolean canResume = resume && sessionId != null && lastSeq != null && resumeGatewayUrl != null;
        this.resuming = canResume;
        if (canResume) {
            openSocket(resumeGatewayUrl);
            return;
        }
        // Fresh connection — drop any stale session before re-identifying.
        sessionId = null;
        lastSeq = null;
        fetchGatewayUrl().whenComplete((url, err) -> {
            if (!running) {
                return;
            }
            if (url == null || url.isBlank()) {
                LOGGER.warn("Discord gateway URL fetch failed: {}", err != null ? err.toString() : "no url returned");
                reconnect(false);
            } else {
                openSocket(url);
            }
        });
    }

    private void openSocket(String baseUrl) {
        String sep = baseUrl.endsWith("/") ? "" : "/";
        String url = baseUrl + sep + GatewayPayloads.GATEWAY_QUERY;
        try {
            DiscordHttp.CLIENT.newWebSocketBuilder()
                    .connectTimeout(DiscordHttp.TIMEOUT)
                    .buildAsync(URI.create(url), new GatewayListener(this))
                    .whenComplete((ws, err) -> {
                        if (err != null || ws == null) {
                            LOGGER.warn("Discord gateway connect failed: {}",
                                    err != null ? err.toString() : "null socket");
                            reconnect(true);
                        }
                        // On success, webSocket + lastSend are assigned in onOpen (which the JDK
                        // guarantees to run before any frame). Doing it here races with frame
                        // delivery — HELLO can arrive first, so IDENTIFY would see a null socket.
                    });
        } catch (Exception e) {
            LOGGER.warn("Discord gateway connect threw: {}", e.toString());
            reconnect(true);
        }
    }

    private void reconnect(boolean resume) {
        if (!running) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return; // a reconnect is already scheduled
        }
        stopHeartbeat();
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        long delay = backoffSeconds();
        LOGGER.info("Discord gateway reconnecting in {}s (resume={}).", delay, resume);
        DiscordHttp.SCHEDULER.schedule(() -> connect(resume), delay, TimeUnit.SECONDS);
    }

    private long backoffSeconds() {
        int attempt = reconnectAttempts.getAndIncrement();
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 6)); // 1,2,4,…,64 → capped 60
    }

    private CompletableFuture<String> fetchGatewayUrl() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GATEWAY_BOT_URL))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .GET()
                .build();
        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        if (resp.statusCode() == 401) {
                            LOGGER.warn("Discord rejected the bot token for the gateway (401) — check 'botToken'.");
                        } else {
                            LOGGER.warn("GET /gateway/bot returned HTTP {}.", resp.statusCode());
                        }
                        return null;
                    }
                    try {
                        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                        return json.has("url") ? json.get("url").getAsString() : null;
                    } catch (Exception e) {
                        LOGGER.warn("Failed to parse /gateway/bot response", e);
                        return null;
                    }
                })
                .exceptionally(t -> {
                    LOGGER.warn("GET /gateway/bot failed: {}", t.toString());
                    return null;
                });
    }

    // --- heartbeat ---

    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();
        heartbeatAckPending.set(false);
        long firstDelay = (long) (intervalMs * 0.5); // jitter the first beat
        heartbeatTask = DiscordHttp.SCHEDULER.scheduleAtFixedRate(
                this::heartbeatTick, firstDelay, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void heartbeatTick() {
        if (!running || webSocket == null) {
            return;
        }
        if (heartbeatAckPending.get()) {
            LOGGER.warn("Discord gateway heartbeat not acknowledged — assuming a zombie connection, reconnecting.");
            reconnect(true);
            return;
        }
        heartbeatAckPending.set(true);
        send(GatewayPayloads.heartbeat(lastSeq));
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) {
            t.cancel(false);
            heartbeatTask = null;
        }
    }

    // --- send serialisation ---

    private void send(String payload) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        // Chain on the previous send so two sends never overlap (sendText throws otherwise);
        // handle() collapses any prior result/error back to the current socket.
        lastSend.updateAndGet(prev -> prev
                .handle((w, t) -> ws)
                .thenCompose(w -> w.sendText(payload, true)));
    }

    // --- inbound frames (called from the WebSocket thread via GatewayListener) ---

    /**
     * Called by the listener the instant the socket opens — before any frame — so the
     * first sends (IDENTIFY / RESUME, triggered by HELLO) have a non-null target. The
     * JDK guarantees {@code onOpen} runs before {@code onText}, which the late
     * {@code buildAsync} completion does not.
     */
    void onOpen(WebSocket ws) {
        this.webSocket = ws;
        this.lastSend.set(CompletableFuture.completedFuture(ws));
    }

    void onText(String text) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Discord gateway: ignoring unparseable frame ({} chars).", text.length());
            return;
        }

        Integer s = GatewayPayloads.seqOf(payload);
        if (s != null) {
            lastSeq = s;
        }

        switch (GatewayPayloads.opOf(payload)) {
            case GatewayPayloads.OP_HELLO -> {
                long interval = GatewayPayloads.helloInterval(payload);
                startHeartbeat(interval);
                if (resuming && sessionId != null && lastSeq != null) {
                    send(GatewayPayloads.resume(token, sessionId, lastSeq));
                } else {
                    send(GatewayPayloads.identify(token));
                }
            }
            case GatewayPayloads.OP_HEARTBEAT -> send(GatewayPayloads.heartbeat(lastSeq));
            case GatewayPayloads.OP_HEARTBEAT_ACK -> heartbeatAckPending.set(false);
            case GatewayPayloads.OP_RECONNECT -> reconnect(true);
            case GatewayPayloads.OP_INVALID_SESSION -> {
                boolean resumable = GatewayPayloads.invalidSessionResumable(payload);
                if (!resumable) {
                    sessionId = null;
                    lastSeq = null;
                }
                reconnect(resumable);
            }
            case GatewayPayloads.OP_DISPATCH -> handleDispatch(payload);
            default -> { /* ignore unknown opcodes */ }
        }
    }

    private void handleDispatch(JsonObject payload) {
        String type = GatewayPayloads.typeOf(payload);
        if (type == null) {
            return;
        }
        switch (type) {
            case "READY" -> {
                reconnectAttempts.set(0);
                JsonObject d = payload.getAsJsonObject("d");
                sessionId = d.has("session_id") ? d.get("session_id").getAsString() : null;
                resumeGatewayUrl = d.has("resume_gateway_url") ? d.get("resume_gateway_url").getAsString() : null;
                LOGGER.info("Discord gateway READY — listening for replies/threads.");
            }
            case "RESUMED" -> {
                reconnectAttempts.set(0);
                LOGGER.info("Discord gateway RESUMED.");
            }
            case "MESSAGE_CREATE" -> {
                try {
                    onMessage.accept(GatewayPayloads.message(payload.getAsJsonObject("d")));
                } catch (Exception e) {
                    LOGGER.warn("Failed to route MESSAGE_CREATE", e);
                }
            }
            default -> { /* ignore other dispatch events */ }
        }
    }

    void onClose(int code, String reason) {
        LOGGER.info("Discord gateway closed: {} {}", code, reason);
        if (isFatalClose(code)) {
            logFatalClose(code);
            running = false; // do not reconnect — needs operator action
            stopHeartbeat();
            return;
        }
        reconnect(true);
    }

    void onError(Throwable error) {
        LOGGER.warn("Discord gateway socket error: {}", error.toString());
        reconnect(true);
    }

    /** A handler threw while processing a frame — log, but keep the connection. */
    void onHandlerError(Throwable error) {
        LOGGER.warn("Discord gateway frame handler error: {}", error.toString());
    }

    /** Close codes that won't be fixed by reconnecting — almost always a config/portal issue. */
    private static boolean isFatalClose(int code) {
        return code == 4004   // authentication failed (bad token)
                || code == 4010  // invalid shard
                || code == 4011  // sharding required
                || code == 4012  // invalid API version
                || code == 4013  // invalid intent(s)
                || code == 4014; // disallowed intent(s) — privileged intent not enabled
    }

    private static void logFatalClose(int code) {
        switch (code) {
            case 4004 -> LOGGER.error("Discord rejected the bot token (4004). Check 'botToken' in "
                    + "discordpresence-server.toml. Gateway disabled until restart.");
            case 4014 -> LOGGER.error("Discord refused the gateway: disallowed intent(s) (4014). Enable the "
                    + "'MESSAGE CONTENT INTENT' for the bot in the Discord Developer Portal "
                    + "(Bot → Privileged Gateway Intents), then restart. Gateway disabled until then.");
            case 4013 -> LOGGER.error("Discord refused the gateway intents (4013). Gateway disabled until restart.");
            default -> LOGGER.error("Discord closed the gateway with a fatal code {}. Gateway disabled until restart.",
                    code);
        }
    }
}
