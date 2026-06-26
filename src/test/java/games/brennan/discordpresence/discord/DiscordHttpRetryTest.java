package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.OptionalLong;

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
}
