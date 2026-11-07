package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DiscordHttp}'s retry decision: which statuses/exceptions are retried,
 * how Discord's rate-limit delay header is parsed, and the backoff bounds. No network — the
 * scheduling itself is JDK plumbing; only the decisions are ours to verify.
 */
class DiscordHttpRetryTest {

    // --- which statuses are retryable -------------------------------------

    @Test
    void retriesRateLimitAndServerErrors() {
        assertTrue(DiscordHttp.isRetryableStatus(429), "429 rate-limit must retry");
        assertTrue(DiscordHttp.isRetryableStatus(500));
        assertTrue(DiscordHttp.isRetryableStatus(502));
        assertTrue(DiscordHttp.isRetryableStatus(503));
        assertTrue(DiscordHttp.isRetryableStatus(599));
    }

    @Test
    void doesNotRetrySuccessOrClientErrors() {
        assertFalse(DiscordHttp.isRetryableStatus(200), "success never retries");
        assertFalse(DiscordHttp.isRetryableStatus(204));
        assertFalse(DiscordHttp.isRetryableStatus(400), "bad payload won't succeed on retry");
        assertFalse(DiscordHttp.isRetryableStatus(401));
        assertFalse(DiscordHttp.isRetryableStatus(403));
        assertFalse(DiscordHttp.isRetryableStatus(404));
    }

    // --- message-creating posts: no 5xx retry ------------------------------

    @Test
    void messageCreatingPostsDoNotRetryServerErrors() {
        // Behind the relay a 5xx is ambiguous: the relay aborts its upstream forward on a timeout and
        // answers 502, but the body it streamed may already have created the message. Resending it is
        // how one death report landed in the public feed several times.
        assertFalse(DiscordHttp.shouldRetryStatus(500, false));
        assertFalse(DiscordHttp.shouldRetryStatus(502, false), "relay upstream_error must not be resent");
        assertFalse(DiscordHttp.shouldRetryStatus(503, false));
        assertFalse(DiscordHttp.shouldRetryStatus(599, false));
    }

    @Test
    void messageCreatingPostsStillRetryRateLimits() {
        // A 429 provably created nothing, so it stays retryable under either policy.
        assertTrue(DiscordHttp.shouldRetryStatus(429, false), "429 created nothing — still safe to retry");
        assertTrue(DiscordHttp.shouldRetryStatus(429, true));
    }

    @Test
    void idempotentCallsKeepTheServerErrorRetry() {
        assertTrue(DiscordHttp.shouldRetryStatus(500, true));
        assertTrue(DiscordHttp.shouldRetryStatus(502, true));
    }

    @Test
    void successAndClientErrorsNeverRetryUnderEitherPolicy() {
        for (boolean retryServerErrors : new boolean[] {true, false}) {
            assertFalse(DiscordHttp.shouldRetryStatus(200, retryServerErrors));
            assertFalse(DiscordHttp.shouldRetryStatus(400, retryServerErrors));
            assertFalse(DiscordHttp.shouldRetryStatus(404, retryServerErrors));
        }
    }

    // --- which exceptions are retryable -----------------------------------

    @Test
    void retriesProvablyPreSendFailures() {
        assertTrue(DiscordHttp.isRetryableException(new ConnectException("refused")));
        assertTrue(DiscordHttp.isRetryableException(new UnknownHostException("dns")));
        assertTrue(DiscordHttp.isRetryableException(new HttpConnectTimeoutException("connect timed out")));
    }

    @Test
    void doesNotRetryAmbiguousOrReadFailures() {
        // A read timeout fires after the body may already be on the wire — retrying risks a duplicate.
        assertFalse(DiscordHttp.isRetryableException(new HttpTimeoutException("request timed out")));
        assertFalse(DiscordHttp.isRetryableException(new IOException("connection reset")));
        assertFalse(DiscordHttp.isRetryableException(new RuntimeException("unexpected")));
    }

    // --- which failures the durable resend queue enqueues on ---------------

    @Test
    void enqueuesOnlyProvablyPreSendConnectionFailures() {
        // These never reached Discord → safe to durably resend without a duplicate.
        assertTrue(noIdQueueable(new ConnectException("refused")));
        assertTrue(noIdQueueable(new UnknownHostException("dns")));
        assertTrue(noIdQueueable(new HttpConnectTimeoutException("connect timed out")));
        // As it actually arrives at a CompletableFuture.exceptionally stage: wrapped in CompletionException.
        assertTrue(noIdQueueable(new CompletionException(new ConnectException("refused"))));
    }

    @Test
    void doesNotEnqueueAmbiguousReadFailuresOrNull() {
        // A read timeout / reset may already have delivered — enqueuing would risk a duplicate, so drop.
        assertFalse(noIdQueueable(new HttpTimeoutException("read timed out")));
        assertFalse(noIdQueueable(new CompletionException(new HttpTimeoutException("read"))));
        assertFalse(noIdQueueable(new IOException("connection reset")));
        assertFalse(noIdQueueable(null));
    }

    // --- Retry-After parsing ----------------------------------------------

    @Test
    void parsesIntegerAndFractionalSeconds() {
        assertEquals(OptionalLong.of(1_000L), DiscordHttp.parseRetryAfterMillis("1"));
        assertEquals(OptionalLong.of(1_500L), DiscordHttp.parseRetryAfterMillis("1.5"));
        assertEquals(OptionalLong.of(250L), DiscordHttp.parseRetryAfterMillis("0.25"));
        assertEquals(OptionalLong.of(0L), DiscordHttp.parseRetryAfterMillis("0"));
        assertEquals(OptionalLong.of(2L), DiscordHttp.parseRetryAfterMillis("0.0011"), "rounds up to whole ms");
        assertEquals(OptionalLong.of(2_000L), DiscordHttp.parseRetryAfterMillis(" 2 "), "tolerates whitespace");
    }

    @Test
    void retryAfterEmptyOnMissingOrBadValue() {
        assertTrue(DiscordHttp.parseRetryAfterMillis(null).isEmpty());
        assertTrue(DiscordHttp.parseRetryAfterMillis("").isEmpty());
        assertTrue(DiscordHttp.parseRetryAfterMillis("   ").isEmpty());
        assertTrue(DiscordHttp.parseRetryAfterMillis("soon").isEmpty());
    }

    @Test
    void retryAfterCappedAtOneMinute() {
        assertEquals(OptionalLong.of(60_000L), DiscordHttp.parseRetryAfterMillis("120"),
                "a pathological Retry-After can't park a thread for minutes");
    }

    // --- backoff bounds ---------------------------------------------------

    @Test
    void backoffGrowsExponentiallyThenCaps() {
        assertInRange(DiscordHttp.backoffMs(0), 1_000L, 1_250L);
        assertInRange(DiscordHttp.backoffMs(1), 2_000L, 2_250L);
        assertInRange(DiscordHttp.backoffMs(2), 4_000L, 4_250L);
        // Capped at 8s + jitter — never unbounded, even past the attempt budget.
        assertInRange(DiscordHttp.backoffMs(3), 8_000L, 8_250L);
        assertInRange(DiscordHttp.backoffMs(10), 8_000L, 8_250L);
    }

    private static void assertInRange(long actual, long lowInclusive, long highExclusive) {
        assertTrue(actual >= lowInclusive && actual < highExclusive,
                actual + " not in [" + lowInclusive + ", " + highExclusive + ")");
    }

    /** The queue rule for a post with no relay post id: provably-pre-send failures only. */
    private static boolean noIdQueueable(Throwable t) {
        return DiscordHttp.isQueueableFailure(t, false);
    }

    @Test
    void postIdMakesAmbiguousFailuresQueueable() {
        Throwable timeout = new java.util.concurrent.CompletionException(new java.net.http.HttpTimeoutException("request timed out"));
        Throwable reset = new java.io.IOException("Connection reset");
        Throwable status = new java.util.concurrent.CompletionException(new DiscordHttp.RetryableStatusException(503));
        assertTrue(DiscordHttp.isQueueableFailure(timeout, true));
        assertTrue(DiscordHttp.isQueueableFailure(reset, true));
        assertTrue(DiscordHttp.isQueueableFailure(status, true));
        assertFalse(DiscordHttp.isQueueableFailure(timeout, false), "no id: a re-send could double-post");
        assertFalse(DiscordHttp.isQueueableFailure(reset, false));
        assertFalse(DiscordHttp.isQueueableFailure(status, false));
    }

    @Test
    void aBugIsNeverQueued() {
        assertFalse(DiscordHttp.isQueueableFailure(new IllegalStateException("bug"), true));
        assertFalse(DiscordHttp.isQueueableFailure(null, true));
    }
}
