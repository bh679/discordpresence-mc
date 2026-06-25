package games.brennan.discordpresence.discord;

import java.util.OptionalLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Pure reconnect/backoff policy for the Discord gateway — the storm guard.
 *
 * <p>Discord auto-resets a bot token if it connects to the gateway too many times in a day
 * (~1000 IDENTIFYs / 24h). The original logic reset the backoff counter to zero the instant a
 * connection reached READY/RESUMED, so a connection that flapped (reached READY, dropped seconds
 * later, repeat) restarted backoff at the 1s floor and reconnected ~once per second — thousands of
 * connects/hour. This policy fixes that with three bounds:</p>
 *
 * <ol>
 *   <li><b>Stable-uptime-gated reset.</b> The attempt counter only resets after a session has
 *       stayed open at least {@link #STABLE_SESSION_MILLIS}. A flap keeps incrementing, so backoff
 *       climbs instead of restarting at the floor.</li>
 *   <li><b>Exponential backoff with floor, cap, and jitter.</b> {@code [MIN_BACKOFF, MAX_BACKOFF]}
 *       with ±20% jitter so many servers reconnecting together don't thunder.</li>
 *   <li><b>Circuit breaker.</b> After {@link #MAX_CONSECUTIVE_ATTEMPTS} reconnects without ever
 *       reaching a stable session, {@link #shouldGiveUp()} returns true so the caller can stop —
 *       bounding a permanently broken token/network (e.g. a REST 401 loop) to a finite number of
 *       connects instead of forever.</li>
 * </ol>
 *
 * <p>No I/O and no protocol knowledge — the clock and jitter source are injected so the whole
 * thing is deterministically unit-testable (see {@code ReconnectPolicyTest}).</p>
 *
 * <p>Touched from several threads ({@code onConnectionOpened} on the WebSocket-open callback,
 * {@code nextBackoffMillis}/{@code shouldGiveUp} on the reconnect path), so the mutating methods are
 * {@code synchronized} for atomicity and visibility — matching the {@code AtomicInteger} this
 * replaced.</p>
 */
final class ReconnectPolicy {

    /** A session must stay open at least this long before a later drop resets the backoff counter. */
    static final long STABLE_SESSION_MILLIS = 60_000;
    /** Hard floor on the delay between connection attempts. */
    static final long MIN_BACKOFF_MILLIS = 1_000;
    /**
     * Cap on the delay between connection attempts. 120s means a sustained flap settles at
     * ≤ ~720 connects/day, comfortably under Discord's ~1000/24h ceiling.
     */
    static final long MAX_BACKOFF_MILLIS = 120_000;
    /** Give up after this many reconnects without a stable session (circuit breaker). */
    static final int MAX_CONSECUTIVE_ATTEMPTS = 50;
    /** Fraction of jitter applied to each delay, ±. */
    static final double JITTER_FRACTION = 0.20;

    private final LongSupplier nanoClock;
    private final DoubleSupplier jitterSource;

    /** Reconnect attempts since the last stable session (or start). */
    private int attempts;
    /** {@code nanoTime} when the current connection opened, or {@code Long.MIN_VALUE} if none is open. */
    private long openedAtNanos = Long.MIN_VALUE;

    /** Production constructor: real clock, real (uniform [0,1)) jitter. */
    ReconnectPolicy() {
        this(System::nanoTime, Math::random);
    }

    /**
     * @param nanoClock    monotonic nanosecond clock (e.g. {@code System::nanoTime})
     * @param jitterSource supplies a value in {@code [0,1)} used to spread the delay; inject a
     *                     constant {@code 0.5} for zero net jitter in tests
     */
    ReconnectPolicy(LongSupplier nanoClock, DoubleSupplier jitterSource) {
        this.nanoClock = nanoClock;
        this.jitterSource = jitterSource;
    }

    /**
     * Record that a connection has opened. Marks the start of a candidate "stable" session; the
     * counter is not reset here (an open socket is not yet proof of stability — it must survive
     * {@link #STABLE_SESSION_MILLIS}).
     */
    synchronized void onConnectionOpened() {
        openedAtNanos = nanoClock.getAsLong();
    }

    /**
     * Compute the delay before the next connection attempt, or {@link OptionalLong#empty()} when the
     * circuit breaker has tripped and the caller should stop reconnecting.
     *
     * <p>Order matters: the stable-uptime reset is applied <em>first</em> so a session that stayed
     * stable then dropped can never trip the breaker — only sustained never-stable failure does.</p>
     *
     * <ol>
     *   <li>If the connection that just ended had been open at least {@link #STABLE_SESSION_MILLIS},
     *       reset the attempt counter (fast recovery for a genuinely stable session that dropped);
     *       otherwise the counter keeps climbing (a flap never restarts backoff at the floor).</li>
     *   <li>If the counter has reached {@link #MAX_CONSECUTIVE_ATTEMPTS}, return empty (give up).</li>
     *   <li>Otherwise return an exponential backoff in {@code [MIN_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS]}
     *       with ±{@link #JITTER_FRACTION} jitter.</li>
     * </ol>
     */
    synchronized OptionalLong nextBackoffMillis() {
        if (wasStableSession()) {
            attempts = 0;
        }
        openedAtNanos = Long.MIN_VALUE; // the prior session, stable or not, is now accounted for
        if (attempts >= MAX_CONSECUTIVE_ATTEMPTS) {
            return OptionalLong.empty();
        }
        int attempt = attempts++;
        long base = MIN_BACKOFF_MILLIS << Math.min(attempt, 7); // 1,2,4,…,64,128s → capped to MAX below
        long capped = Math.min(MAX_BACKOFF_MILLIS, base);
        return OptionalLong.of(applyJitter(capped));
    }

    /** Visible for tests. */
    synchronized int attempts() {
        return attempts;
    }

    private boolean wasStableSession() {
        if (openedAtNanos == Long.MIN_VALUE) {
            return false; // never opened (e.g. REST bootstrap failed before a socket)
        }
        long elapsedMillis = (nanoClock.getAsLong() - openedAtNanos) / 1_000_000L;
        return elapsedMillis >= STABLE_SESSION_MILLIS;
    }

    /** Spread {@code delay} by ±{@link #JITTER_FRACTION}, clamped to the [min,max] window. */
    private long applyJitter(long delay) {
        double offset = (jitterSource.getAsDouble() * 2.0 - 1.0) * JITTER_FRACTION; // [-frac, +frac)
        long jittered = Math.round(delay * (1.0 + offset));
        return Math.max(MIN_BACKOFF_MILLIS, Math.min(MAX_BACKOFF_MILLIS, jittered));
    }
}
