package games.brennan.discordpresence.reincarnation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sliding-window cache that lets the reincarnation {@code candidates()} seam callback answer
 * <b>synchronously on the server thread</b>. PlayerMob queries candidates during entity spawn and
 * forbids blocking I/O there, so DP never fetches inside the call — instead it pre-fetches a band of
 * remote lives per nearby player and serves them from here.
 *
 * <p>Per live player ("owner") it keeps: the latest carriage {@link #observe}d from a {@code candidates()}
 * call (the only place DP learns where a player is), and the most recently fetched band of pre-built
 * PlayerMob records for them. {@link #bandsToFetch} (run off-thread on the refresh tick) decides which
 * owners need a (re)fetch — cold, drifted past the pre-fetched range, or stale — skipping any already
 * in flight; the tick then {@link #store}s the result. Records are stored as opaque {@code Object}s
 * (real {@code games.brennan.playermob.compat.ReincarnationRecord} instances built by
 * {@link PlayerMobSeam}) so this class — and its tests — never reference a PlayerMob type.</p>
 *
 * <p>All maps are concurrent; cached lists are immutable and swapped atomically, so the server thread
 * always reads a consistent band.</p>
 */
final class ReincarnationCache {

    /** A fetched band for one owner: the carriage it was fetched around, when, and the built records. */
    private record CachedBand(int carriage, List<Object> records, long fetchedAt) {}

    /** owner → latest carriage observed from a {@code candidates()} call (drives prefetch). */
    private final ConcurrentHashMap<UUID, Integer> observed = new ConcurrentHashMap<>();
    /** owner → most recently fetched band of pre-built records. */
    private final ConcurrentHashMap<UUID, CachedBand> bands = new ConcurrentHashMap<>();
    /** owners with a fetch currently in flight (suppresses duplicate concurrent fetches). */
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** Record where a live player currently is, learned from a {@code candidates()} call. Server-thread, O(1). */
    void observe(UUID owner, int carriage) {
        if (owner != null) {
            observed.put(owner, carriage);
        }
    }

    /** The pre-fetched records for {@code owner} (oldest→newest), or an empty list when none cached. Server-thread, O(1). */
    List<Object> candidatesFor(UUID owner) {
        if (owner == null) {
            return List.of();
        }
        CachedBand band = bands.get(owner);
        return band == null ? List.of() : band.records();
    }

    /**
     * The owners that need a (re)fetch right now and the carriage to fetch around — for each observed
     * owner not already in flight whose cached band {@linkplain #needsRefresh is cold, drifted, or stale}.
     */
    Map<UUID, Integer> bandsToFetch(long now, long cooldownMillis, int drift) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Map.Entry<UUID, Integer> e : observed.entrySet()) {
            UUID owner = e.getKey();
            int carriage = e.getValue();
            if (inFlight.contains(owner)) {
                continue;
            }
            CachedBand band = bands.get(owner);
            Integer cachedCarriage = band == null ? null : band.carriage();
            long fetchedAt = band == null ? 0L : band.fetchedAt();
            if (needsRefresh(cachedCarriage, carriage, fetchedAt, now, cooldownMillis, drift)) {
                out.put(owner, carriage);
            }
        }
        return out;
    }

    /**
     * Whether a band should be (re)fetched: no band yet (cold), the player drifted more than {@code drift}
     * carriages from the fetched band's centre (crossed the pre-fetched range), or the band is older than
     * {@code cooldownMillis} (stale — picks up newly posted lives). Pure → unit-tested.
     */
    static boolean needsRefresh(Integer cachedCarriage, int observedCarriage, long fetchedAt,
                                long now, long cooldownMillis, int drift) {
        if (cachedCarriage == null) {
            return true;
        }
        if (Math.abs(observedCarriage - cachedCarriage) > drift) {
            return true;
        }
        return (now - fetchedAt) >= cooldownMillis;
    }

    /** Claim a fetch slot for {@code owner}; returns false if one is already in flight. */
    boolean tryBeginFetch(UUID owner) {
        return owner != null && inFlight.add(owner);
    }

    /** Release {@code owner}'s fetch slot (call when the fetch settles, success or failure). */
    void endFetch(UUID owner) {
        if (owner != null) {
            inFlight.remove(owner);
        }
    }

    /** Atomically replace {@code owner}'s cached band with an immutable copy of {@code records}. */
    void store(UUID owner, int carriage, List<Object> records, long now) {
        if (owner == null) {
            return;
        }
        bands.put(owner, new CachedBand(carriage, List.copyOf(records), now));
    }

    /** Number of owners with a cached band (diagnostic). */
    int cachedOwnerCount() {
        return bands.size();
    }

    /** Number of cached records for {@code owner} (diagnostic). */
    int cachedCountFor(UUID owner) {
        if (owner == null) {
            return 0;
        }
        CachedBand band = bands.get(owner);
        return band == null ? 0 : band.records().size();
    }

    /** Drop all observations + cached bands + in-flight markers (server stop). */
    void clear() {
        observed.clear();
        bands.clear();
        inFlight.clear();
    }
}
