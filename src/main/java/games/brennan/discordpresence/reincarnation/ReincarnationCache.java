package games.brennan.discordpresence.reincarnation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * A fetched band for one owner: the carriage AND difficulty partition it was fetched for, when, the
     * built records, and the relay's {@code etag} tagging that record set — sent back on the next fetch so
     * an unchanged band can be confirmed without re-shipping the snapshots.
     *
     * <p>The difficulty is part of the band's identity, not decoration. The {@code etag} is a list of
     * record ids, so two partitions can produce the same tag; reusing a band (or its tag) across a
     * difficulty change would serve the previous difficulty's echoes, or let the relay confirm
     * "unchanged" for a band that is not the one we now want.</p>
     *
     * <p>{@code freePlay} is part of the identity for the same reason: it is the Free Play provenance the
     * band was filtered to ({@code null} = both). PlayerMob spawns a life only for a player whose Free
     * Play state matches it, so a band fetched for the other provenance is one the player can't use.</p>
     */
    private record CachedBand(int carriage, String difficulty, Boolean freePlay, List<Object> records,
                              long fetchedAt, String etag) {}

    /**
     * What a {@code candidates()} call told us to pre-fetch for: where the player is, their difficulty
     * partition, and the Free Play provenance they can spawn ({@code null} = unfiltered).
     */
    record FetchTarget(int carriage, String difficulty, Boolean freePlay) {
        FetchTarget(int carriage, String difficulty) {
            this(carriage, difficulty, null);
        }
    }

    /** owner → latest {@link FetchTarget} observed from a {@code candidates()} call (drives prefetch). */
    private final ConcurrentHashMap<UUID, FetchTarget> observed = new ConcurrentHashMap<>();
    /** owner → most recently fetched band of pre-built records. */
    private final ConcurrentHashMap<UUID, CachedBand> bands = new ConcurrentHashMap<>();
    /** owners with a fetch currently in flight (suppresses duplicate concurrent fetches). */
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /** Record where a live player currently is, learned from a {@code candidates()} call. Server-thread, O(1). */
    void observe(UUID owner, int carriage) {
        observe(owner, carriage, null);
    }

    /**
     * As {@link #observe(UUID, int)}, also recording the difficulty partition the player is currently in
     * ({@code null} = unpartitioned). Server-thread, O(1).
     */
    void observe(UUID owner, int carriage, String difficulty) {
        observe(owner, carriage, difficulty, null);
    }

    /**
     * As {@link #observe(UUID, int, String)}, also recording the Free Play provenance the player can spawn
     * ({@code null} = unfiltered: dev builds, or a PlayerMob without the query handle). Server-thread, O(1).
     */
    void observe(UUID owner, int carriage, String difficulty, Boolean freePlay) {
        if (owner != null) {
            observed.put(owner, new FetchTarget(carriage, difficulty, freePlay));
        }
    }

    /** The pre-fetched records for {@code owner} (oldest→newest), or an empty list when none cached. Server-thread, O(1). */
    List<Object> candidatesFor(UUID owner) {
        return candidatesFor(owner, null);
    }

    /**
     * As {@link #candidatesFor(UUID)}, but a band fetched for a different difficulty partition reads as
     * empty rather than being offered — the player has moved into another isolated profile, and its band
     * is on its way. Server-thread, O(1).
     */
    List<Object> candidatesFor(UUID owner, String difficulty) {
        return candidatesFor(owner, difficulty, null);
    }

    /** As {@link #candidatesFor(UUID, String)}, also reading a band of the other provenance as empty. */
    List<Object> candidatesFor(UUID owner, String difficulty, Boolean freePlay) {
        if (owner == null) {
            return List.of();
        }
        CachedBand band = bands.get(owner);
        if (band == null || !sameBand(band, difficulty, freePlay)) {
            return List.of();
        }
        return band.records();
    }

    /** Whether two partition keys name the same partition ({@code null} and {@code ""} both = unpartitioned). */
    static boolean samePartition(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equals(y);
    }

    /** Whether two Free Play filters are the same ({@code null} = unfiltered, distinct from both values). */
    static boolean sameProvenance(Boolean a, Boolean b) {
        return Objects.equals(a, b);
    }

    private static boolean sameBand(CachedBand band, String difficulty, Boolean freePlay) {
        return samePartition(band.difficulty(), difficulty) && sameProvenance(band.freePlay(), freePlay);
    }

    /**
     * The owners that need a (re)fetch right now and the carriage to fetch around — for each observed
     * owner not already in flight whose cached band {@linkplain #needsRefresh is cold, drifted, or stale}.
     */
    Map<UUID, FetchTarget> bandsToFetch(long now, long cooldownMillis, int drift) {
        Map<UUID, FetchTarget> out = new HashMap<>();
        for (Map.Entry<UUID, FetchTarget> e : observed.entrySet()) {
            UUID owner = e.getKey();
            FetchTarget target = e.getValue();
            if (inFlight.contains(owner)) {
                continue;
            }
            CachedBand band = bands.get(owner);
            Integer cachedCarriage = band == null ? null : band.carriage();
            String cachedDifficulty = band == null ? null : band.difficulty();
            Boolean cachedFreePlay = band == null ? null : band.freePlay();
            long fetchedAt = band == null ? 0L : band.fetchedAt();
            if (needsRefresh(cachedCarriage, cachedDifficulty, cachedFreePlay, target.carriage(),
                    target.difficulty(), target.freePlay(), fetchedAt, now, cooldownMillis, drift)) {
                out.put(owner, target);
            }
        }
        return out;
    }

    /**
     * Whether a band should be (re)fetched: no band yet (cold), the player drifted more than {@code drift}
     * carriages from the fetched band's centre (crossed the pre-fetched range), the band is older than
     * {@code cooldownMillis} (stale — picks up newly posted lives), or the player is now in a different
     * difficulty partition (the cached band belongs to a profile they have left). Pure → unit-tested.
     *
     * <p>The partition check is deliberately not subject to the cooldown: a difficulty change should take
     * effect at once, not up to 30 seconds later.</p>
     */
    static boolean needsRefresh(Integer cachedCarriage, String cachedDifficulty,
                                int observedCarriage, String observedDifficulty, long fetchedAt,
                                long now, long cooldownMillis, int drift) {
        return needsRefresh(cachedCarriage, cachedDifficulty, null, observedCarriage, observedDifficulty, null,
                fetchedAt, now, cooldownMillis, drift);
    }

    /**
     * As {@link #needsRefresh(Integer, String, int, String, long, long, long, int)}, also refetching at once
     * when the player's Free Play provenance changed (e.g. a run turned Free Play): the cached band was
     * filtered to lives they can no longer spawn.
     */
    static boolean needsRefresh(Integer cachedCarriage, String cachedDifficulty, Boolean cachedFreePlay,
                                int observedCarriage, String observedDifficulty, Boolean observedFreePlay,
                                long fetchedAt, long now, long cooldownMillis, int drift) {
        if (cachedCarriage == null) {
            return true;
        }
        if (!samePartition(cachedDifficulty, observedDifficulty)) {
            return true;
        }
        if (!sameProvenance(cachedFreePlay, observedFreePlay)) {
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

    /** Atomically replace {@code owner}'s cached band with an immutable copy of {@code records} + its {@code etag}. */
    void store(UUID owner, int carriage, List<Object> records, String etag, long now) {
        store(owner, carriage, null, records, etag, now);
    }

    /** As {@link #store(UUID, int, List, String, long)}, tagging the band with the partition it was fetched for. */
    void store(UUID owner, int carriage, String difficulty, List<Object> records, String etag, long now) {
        store(owner, carriage, difficulty, null, records, etag, now);
    }

    /** As {@link #store(UUID, int, String, List, String, long)}, also tagging the band's Free Play filter. */
    void store(UUID owner, int carriage, String difficulty, Boolean freePlay, List<Object> records, String etag,
               long now) {
        if (owner == null) {
            return;
        }
        bands.put(owner, new CachedBand(carriage, difficulty, freePlay, List.copyOf(records), now, etag));
    }

    /**
     * Refresh {@code owner}'s cached band's fetch time WITHOUT changing its records — the conditional-GET
     * "unchanged" path, where the relay confirmed the band the caller already holds is still current. Its
     * cooldown restarts (so it isn't re-fetched every tick) while its already-decoded records are reused,
     * and no snapshots are re-shipped. No-op when there is no cached band to touch.
     */
    void touch(UUID owner, long now) {
        if (owner == null) {
            return;
        }
        bands.computeIfPresent(owner,
            (k, b) -> new CachedBand(b.carriage(), b.difficulty(), b.freePlay(), b.records(), now, b.etag()));
    }

    /**
     * The {@code etag} of {@code owner}'s cached band, or {@code null} when none is cached — sent on the
     * next fetch as the conditional-GET tag so the relay can answer "unchanged".
     */
    String etagFor(UUID owner) {
        return etagFor(owner, null);
    }

    /**
     * As {@link #etagFor(UUID)}, but {@code null} when the cached band belongs to a different difficulty
     * partition. Sending that band's tag would be a lie: the tag is a list of record ids, so the relay
     * could confirm "unchanged" against a band we no longer want.
     */
    String etagFor(UUID owner, String difficulty) {
        return etagFor(owner, difficulty, null);
    }

    /** As {@link #etagFor(UUID, String)}, also {@code null} when the cached band has the other provenance. */
    String etagFor(UUID owner, String difficulty, Boolean freePlay) {
        if (owner == null) {
            return null;
        }
        CachedBand band = bands.get(owner);
        if (band == null || !sameBand(band, difficulty, freePlay)) {
            return null;
        }
        return band.etag();
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
