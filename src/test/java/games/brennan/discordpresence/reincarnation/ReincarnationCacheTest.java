package games.brennan.discordpresence.reincarnation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(ReincarnationCache.needsRefresh(null, null, 10, null, 0, 1_000, COOLDOWN, DRIFT));
    }

    @Test
    void freshMatchingBandDoesNotRefresh() {
        long fetchedAt = 1_000;
        long now = fetchedAt + 5_000; // within cooldown
        assertFalse(ReincarnationCache.needsRefresh(10, null, 12, null, fetchedAt, now, COOLDOWN, DRIFT)); // drift 2 ≤ 15
    }

    @Test
    void driftingPastBandNeedsRefresh() {
        long now = 2_000;
        assertTrue(ReincarnationCache.needsRefresh(10, null, 30, null, 1_000, now, COOLDOWN, DRIFT)); // drift 20 > 15
    }

    @Test
    void staleBandNeedsRefresh() {
        long fetchedAt = 1_000;
        long now = fetchedAt + COOLDOWN; // exactly at cooldown → stale
        assertTrue(ReincarnationCache.needsRefresh(10, null, 10, null, fetchedAt, now, COOLDOWN, DRIFT));
    }

    // --- observe / store / serve ------------------------------------------

    @Test
    void servesStoredBandAndEmptyOtherwise() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        assertTrue(cache.candidatesFor(owner).isEmpty()); // nothing cached yet
        assertTrue(cache.candidatesFor(null).isEmpty());

        cache.store(owner, 5, List.of("life-a", "life-b"), "1.2", 1_000);
        assertEquals(List.of("life-a", "life-b"), cache.candidatesFor(owner));
        assertTrue(cache.candidatesFor(UUID.randomUUID()).isEmpty()); // unknown owner
    }

    // --- conditional-GET etag + touch -------------------------------------

    @Test
    void etagIsStoredAndTouchKeepsRecordsWhileRestartingCooldown() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        assertNull(cache.etagFor(owner)); // nothing cached → no tag to send
        cache.observe(owner, 5);
        cache.store(owner, 5, List.of("life-a", "life-b"), "1.2", 1_000);
        assertEquals("1.2", cache.etagFor(owner), "the band's etag is remembered for the next fetch");

        // The band is now stale (cooldown elapsed) → it would be re-fetched.
        assertTrue(cache.bandsToFetch(1_000 + COOLDOWN, COOLDOWN, DRIFT).containsKey(owner));
        // An "unchanged" reply touches it: records + etag preserved, cooldown restarted → no longer stale.
        cache.touch(owner, 1_000 + COOLDOWN);
        assertEquals(List.of("life-a", "life-b"), cache.candidatesFor(owner), "records reused on unchanged");
        assertEquals("1.2", cache.etagFor(owner), "etag preserved across a touch");
        assertFalse(cache.bandsToFetch(1_000 + COOLDOWN + 5, COOLDOWN, DRIFT).containsKey(owner),
                "touch restarts the cooldown so the unchanged band isn't re-polled every tick");

        cache.touch(UUID.randomUUID(), 2_000); // touch on an unknown owner is a no-op (must not throw/create)
    }

    @Test
    void bandsToFetchReturnsObservedOwnersNeedingRefresh() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID cold = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();

        cache.observe(cold, 3);
        cache.observe(fresh, 8);
        cache.store(fresh, 8, List.of("x"), "e1", 10_000); // fresh has a current band

        Map<UUID, ReincarnationCache.FetchTarget> toFetch = cache.bandsToFetch(11_000, COOLDOWN, DRIFT);
        assertTrue(toFetch.containsKey(cold)); // cold band → fetch
        assertFalse(toFetch.containsKey(fresh)); // fresh matching band → skip
        assertEquals(3, toFetch.get(cold).carriage());
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

    // --- difficulty partition ---------------------------------------------

    @Test
    void aDifficultyChangeRefreshesImmediatelyRegardlessOfCooldown() {
        long fetchedAt = 1_000;
        long now = fetchedAt + 5_000; // well within the cooldown
        assertFalse(ReincarnationCache.needsRefresh(10, "hard", 10, "hard", fetchedAt, now, COOLDOWN, DRIFT));
        assertTrue(ReincarnationCache.needsRefresh(10, "hard", 10, "peaceful", fetchedAt, now, COOLDOWN, DRIFT),
                "a band from the difficulty the player just left must not be kept until the cooldown");
        // null and "" both mean unpartitioned, so they must not read as a change.
        assertFalse(ReincarnationCache.needsRefresh(10, null, 10, "", fetchedAt, now, COOLDOWN, DRIFT));
        assertTrue(ReincarnationCache.needsRefresh(10, null, 10, "hard", fetchedAt, now, COOLDOWN, DRIFT));
    }

    @Test
    void aBandFromAnotherPartitionIsNeitherServedNorTagged() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 5, "hard");
        cache.store(owner, 5, "hard", List.of("hard-life"), "1.2", 1_000);

        assertEquals(List.of("hard-life"), cache.candidatesFor(owner, "hard"));
        assertEquals("1.2", cache.etagFor(owner, "hard"));
        // Switched to Peaceful: the Hard band is not offered, and its tag is not sent (the tag is a list
        // of record ids, so the relay could otherwise confirm "unchanged" for a band we no longer want).
        assertTrue(cache.candidatesFor(owner, "peaceful").isEmpty());
        assertNull(cache.etagFor(owner, "peaceful"));
    }

    @Test
    void bandsToFetchCarriesThePartitionToFetchFor() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 7, "easy");

        Map<UUID, ReincarnationCache.FetchTarget> toFetch = cache.bandsToFetch(1_000, COOLDOWN, DRIFT);
        assertEquals(7, toFetch.get(owner).carriage());
        assertEquals("easy", toFetch.get(owner).difficulty());

        // Storing the easy band settles it; observing a different difficulty makes it due again at once.
        cache.store(owner, 7, "easy", List.of("e"), "e1", 1_000);
        assertFalse(cache.bandsToFetch(1_100, COOLDOWN, DRIFT).containsKey(owner));
        cache.observe(owner, 7, "hard");
        assertEquals("hard", cache.bandsToFetch(1_100, COOLDOWN, DRIFT).get(owner).difficulty());
    }

    @Test
    void unpartitionedCallsBehaveAsBefore() {
        // PlayerMob older than 0.87.0 (or isolation off): no partition anywhere, and the cache is
        // indistinguishable from its pre-partition self.
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 2);
        cache.store(owner, 2, List.of("life"), "t", 1_000);
        assertEquals(List.of("life"), cache.candidatesFor(owner));
        assertEquals("t", cache.etagFor(owner));
        assertNull(cache.bandsToFetch(1_100, COOLDOWN, DRIFT).get(owner), "a fresh band is not re-fetched");
    }

    @Test
    void clearDropsEverything() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 1);
        cache.store(owner, 1, List.of("y"), "e", 1_000);
        cache.clear();
        assertTrue(cache.candidatesFor(owner).isEmpty());
        assertTrue(cache.bandsToFetch(1_000, COOLDOWN, DRIFT).isEmpty());
    }

    // --- Free Play provenance -----------------------------------------------

    @Test
    void aProvenanceChangeRefreshesImmediatelyRegardlessOfCooldown() {
        long fetchedAt = 1_000;
        long now = fetchedAt + 5_000;
        assertFalse(ReincarnationCache.needsRefresh(10, "hard", false, 10, "hard", false, fetchedAt, now, COOLDOWN, DRIFT));
        assertTrue(ReincarnationCache.needsRefresh(10, "hard", false, 10, "hard", true, fetchedAt, now, COOLDOWN, DRIFT),
                "a legit band must not be kept for a player whose run just turned Free Play");
        // Unfiltered (null) is its own band: it holds both provenances, so it is not either filtered one.
        assertTrue(ReincarnationCache.needsRefresh(10, "hard", null, 10, "hard", false, fetchedAt, now, COOLDOWN, DRIFT));
        assertFalse(ReincarnationCache.needsRefresh(10, "hard", null, 10, "hard", null, fetchedAt, now, COOLDOWN, DRIFT));
    }

    @Test
    void aBandOfTheOtherProvenanceIsNeitherServedNorTagged() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 5, "normal", false);
        cache.store(owner, 5, "normal", false, List.of("legit-life"), "4.5", 1_000);

        assertEquals(List.of("legit-life"), cache.candidatesFor(owner, "normal", false));
        assertEquals("4.5", cache.etagFor(owner, "normal", false));
        assertTrue(cache.candidatesFor(owner, "normal", true).isEmpty());
        assertNull(cache.etagFor(owner, "normal", true), "the legit band's tag must never be sent for a Free Play fetch");
        // The difficulty still counts too.
        assertTrue(cache.candidatesFor(owner, "hard", false).isEmpty());
    }

    @Test
    void bandsToFetchCarriesTheProvenanceToFetchFor() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 7, "easy", true);
        assertEquals(Boolean.TRUE, cache.bandsToFetch(1_000, COOLDOWN, DRIFT).get(owner).freePlay());

        cache.store(owner, 7, "easy", true, List.of("f"), "f1", 1_000);
        assertFalse(cache.bandsToFetch(1_100, COOLDOWN, DRIFT).containsKey(owner));
        cache.observe(owner, 7, "easy", false);
        assertEquals(Boolean.FALSE, cache.bandsToFetch(1_100, COOLDOWN, DRIFT).get(owner).freePlay());
    }

    @Test
    void touchKeepsTheBandsProvenance() {
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.store(owner, 3, "normal", false, List.of("l"), "t", 1_000);
        cache.touch(owner, 2_000);
        assertEquals(List.of("l"), cache.candidatesFor(owner, "normal", false));
        assertTrue(cache.candidatesFor(owner, "normal", null).isEmpty());
    }

    @Test
    void theDifficultyOnlyOverloadsAreUnfiltered() {
        // Callers that predate the provenance filter (and PlayerMob without matchFreePlay) read and write
        // the unfiltered band, exactly as before.
        ReincarnationCache cache = new ReincarnationCache();
        UUID owner = UUID.randomUUID();
        cache.observe(owner, 4, "hard");
        cache.store(owner, 4, "hard", List.of("x"), "e", 1_000);
        assertEquals(List.of("x"), cache.candidatesFor(owner, "hard", null));
        assertNull(cache.bandsToFetch(1_100, COOLDOWN, DRIFT).get(owner));
        assertNull(new ReincarnationQueryData("CARRIAGE", 4, null, owner, "hard").matchFreePlay());
    }
}
