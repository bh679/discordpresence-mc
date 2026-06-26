package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconnect-storm guard — pure backoff / stable-uptime reset / circuit-breaker logic.
 * Uses a fake nanosecond clock and zero-net jitter (0.5) for deterministic assertions.
 */
class ReconnectPolicyTest {

    /** Jitter of 0.5 maps to a 0 offset → delays come out exactly on the base curve. */
    private static final double NO_JITTER = 0.5;

    /** Advanceable fake clock in nanos. */
    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong(0);
        long get() { return nanos.get(); }
        void advanceMillis(long ms) { nanos.addAndGet(ms * 1_000_000L); }
    }

    private static ReconnectPolicy policy(FakeClock clock) {
        return new ReconnectPolicy(clock::get, () -> NO_JITTER);
    }

    @Test
    void flappingConnectionClimbsBackoffInsteadOfResetting() {
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);

        // Each cycle: open, stay up only briefly (< STABLE), then drop and reconnect.
        long[] expected = {1_000, 2_000, 4_000, 8_000, 16_000};
        for (long want : expected) {
            p.onConnectionOpened();
            clock.advanceMillis(5_000); // 5s < 60s stable threshold → NOT stable
            assertEquals(want, p.nextBackoffMillis().getAsLong(),
                    "a flap must keep climbing backoff, never restart at the floor");
        }
    }

    @Test
    void backoffIsCappedAtMax() {
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);
        long last = 0;
        for (int i = 0; i < 10; i++) {
            p.onConnectionOpened();
            clock.advanceMillis(1_000); // never stable
            last = p.nextBackoffMillis().getAsLong();
        }
        assertEquals(ReconnectPolicy.MAX_BACKOFF_MILLIS, last,
                "sustained flap must settle at the cap, not grow unbounded");
    }

    @Test
    void stableSessionResetsBackoffToFloor() {
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);

        // Climb a few attempts.
        for (int i = 0; i < 3; i++) {
            p.onConnectionOpened();
            clock.advanceMillis(1_000);
            p.nextBackoffMillis();
        }
        assertTrue(p.attempts() > 1, "precondition: counter has climbed");

        // A genuinely stable session (>= 60s) then a drop → next backoff is back at the floor.
        p.onConnectionOpened();
        clock.advanceMillis(ReconnectPolicy.STABLE_SESSION_MILLIS + 1);
        assertEquals(ReconnectPolicy.MIN_BACKOFF_MILLIS, p.nextBackoffMillis().getAsLong(),
                "a session stable >= threshold must reset backoff to the floor");
    }

    @Test
    void delayNeverBelowFloorOrAboveCapEvenWithExtremeJitter() {
        FakeClock clock = new FakeClock();
        // Jitter pinned to the extremes.
        ReconnectPolicy low = new ReconnectPolicy(clock::get, () -> 0.0);  // -20%
        ReconnectPolicy high = new ReconnectPolicy(clock::get, () -> 1.0); // +20%

        for (int i = 0; i < 12; i++) {
            low.onConnectionOpened();
            high.onConnectionOpened();
            clock.advanceMillis(1_000);
            long lo = low.nextBackoffMillis().getAsLong();
            long hi = high.nextBackoffMillis().getAsLong();
            assertTrue(lo >= ReconnectPolicy.MIN_BACKOFF_MILLIS, "delay must respect the floor");
            assertTrue(hi <= ReconnectPolicy.MAX_BACKOFF_MILLIS, "delay must respect the cap");
        }
    }

    @Test
    void jitterStaysWithinTwentyPercentBand() {
        FakeClock clock = new FakeClock();
        // First attempt has base 2000ms (1000 << 1) so ±20% (1600..2400) is fully inside [1000,120000].
        ReconnectPolicy lo = new ReconnectPolicy(clock::get, () -> 0.0);
        ReconnectPolicy hi = new ReconnectPolicy(clock::get, () -> 1.0);
        // Advance one attempt so base = 2000.
        lo.onConnectionOpened(); hi.onConnectionOpened();
        clock.advanceMillis(1_000);
        lo.nextBackoffMillis(); hi.nextBackoffMillis(); // attempt 0 -> base 1000
        lo.onConnectionOpened(); hi.onConnectionOpened();
        clock.advanceMillis(1_000);
        long loDelay = lo.nextBackoffMillis().getAsLong(); // attempt 1 -> base 2000, -20% = 1600
        long hiDelay = hi.nextBackoffMillis().getAsLong(); // attempt 1 -> base 2000, +20% = 2400
        assertEquals(1_600, loDelay);
        assertEquals(2_400, hiDelay);
    }

    @Test
    void circuitBreakerTripsAfterMaxConsecutiveUnstableAttempts() {
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);

        // MAX_CONSECUTIVE_ATTEMPTS attempts succeed (return a delay); the next gives up.
        for (int i = 0; i < ReconnectPolicy.MAX_CONSECUTIVE_ATTEMPTS; i++) {
            p.onConnectionOpened();
            clock.advanceMillis(1_000); // never stable
            assertTrue(p.nextBackoffMillis().isPresent(), "attempt " + i + " should still back off");
        }
        // The socket may never even open on a failing bootstrap (REST 401) — no onConnectionOpened().
        assertTrue(p.nextBackoffMillis().isEmpty(), "must give up after the cap");
    }

    @Test
    void neverOpenedConnectionsCountTowardBreaker() {
        // Mirrors the REST-401 vector: fetchGatewayUrl fails before any socket opens, so
        // onConnectionOpened() is never called — these must still climb and eventually give up.
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);
        OptionalLong last = OptionalLong.of(0);
        for (int i = 0; i < ReconnectPolicy.MAX_CONSECUTIVE_ATTEMPTS + 1; i++) {
            last = p.nextBackoffMillis(); // no onConnectionOpened() between calls
        }
        assertTrue(last.isEmpty(), "a never-opening bootstrap loop must trip the breaker");
    }

    @Test
    void stableSessionResetsTheCircuitBreaker() {
        FakeClock clock = new FakeClock();
        ReconnectPolicy p = policy(clock);

        // Almost trip the breaker.
        for (int i = 0; i < ReconnectPolicy.MAX_CONSECUTIVE_ATTEMPTS - 1; i++) {
            p.onConnectionOpened();
            clock.advanceMillis(1_000);
            p.nextBackoffMillis();
        }
        // One stable session resets the counter → the breaker is far from tripping again.
        p.onConnectionOpened();
        clock.advanceMillis(ReconnectPolicy.STABLE_SESSION_MILLIS + 1);
        assertTrue(p.nextBackoffMillis().isPresent());
        // After the reset-then-increment, the counter is back near zero, not at the cap.
        assertEquals(1, p.attempts(), "a stable session must reset the breaker counter");
    }
}
