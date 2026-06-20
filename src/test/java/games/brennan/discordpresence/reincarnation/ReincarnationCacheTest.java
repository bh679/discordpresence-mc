package games.brennan.discordpresence.reincarnation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliding-window cache: the {@code needsRefresh} band math plus observe/store/serve and the
 * in-flight suppression. Record payloads are plain {@code String}s here (the cache is payload-agnostic
 * — in production they are pre-built PlayerMob records), so no Minecraft runtime is needed.
 */
class ReincarnationCacheTest {

    private static final long COOLDOWN = 30_000;
    private static final int DRIFT = 15;

    // --- needsRefresh ------------------------------------------------------

    @Test
    void coldBandNeedsRefresh() {
        assertTrue(ReincarnationCache.needsRefresh(null, 10, 0, 1_000, COOLDOWN, DRIFT));
    }

    @Test
    void freshMatchingBandDoesNotRefresh() {
        long fetchedAt = 1_000;
        long now = fetchedAt + 5_000; // within cooldown
        assertFalse(ReincarnationCache.needsRefresh(10, 12, fetchedAt, now, COOLDOWN, DRIFT)); // drift 2 ≤ 15
    }

    @Test
    void driftingPastBandNeedsRefresh() {
        long now = 2_000;
        assertTrue(ReincarnationCache.needsRefresh(10, 30, 1_000, now, COOLDOWN, DRIFT)); // drift 20 > 15
    }

    @Test
    void staleBandNeedsRefresh() {
        long fetchedAt = 1_000;
        long now = fetchedAt + COOLDOWN; // exactly at cooldown → stale
        assertTrue(ReincarnationCache.needsRefresh(10, 10, fetchedAt, now, COOLDOWN, DRIFT));
    }

    // --- observe / store / serve ------------------------------------------

    @Test
    void servesStoredBandAndEmptyOtherwise() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        assertTrue(cache.candidatesFor(owner).isEmpty()); // nothing cached yet
        assertTrue(cache.candidatesFor(null).isEmpty());

        cache.store(owner, 5, List.of("life-a", "life-b"), 1_000);
        assertEquals(List.of("life-a", "life-b"), cache.candidatesFor(owner));
        assertTrue(cache.candidatesFor(UUID.randomUUID()).isEmpty()); // unknown owner
    }

    @Test
    void bandsToFetchReturnsObservedOwnersNeedingRefresh() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID cold = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();

        cache.observe(cold, 3);
        cache.observe(fresh, 8);
        cache.store(fresh, 8, List.of("x"), 10_000); // fresh has a current band

        Map<UUID, Integer> toFetch = cache.bandsToFetch(11_000, COOLDOWN, DRIFT);
        assertTrue(toFetch.containsKey(cold)); // cold band → fetch
        assertFalse(toFetch.containsKey(fresh)); // fresh matching band → skip
        assertEquals(3, toFetch.get(cold));
    }

    @Test
    void inFlightOwnersAreSuppressed() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 4);

        assertTrue(cache.tryBeginFetch(owner));   // claim a fetch slot
        assertFalse(cache.tryBeginFetch(owner));  // second claim suppressed
        assertFalse(cache.bandsToFetch(1_000, COOLDOWN, DRIFT).containsKey(owner)); // in-flight → not re-fetched

        cache.endFetch(owner);
        assertTrue(cache.bandsToFetch(1_000, COOLDOWN, DRIFT).containsKey(owner)); // released → eligible again
    }

    @Test
    void clearDropsEverything() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 1);
        cache.store(owner, 1, List.of("y"), 1_000);
        cache.clear();
        assertTrue(cache.candidatesFor(owner).isEmpty());
        assertTrue(cache.bandsToFetch(1_000, COOLDOWN, DRIFT).isEmpty());
    }
}
