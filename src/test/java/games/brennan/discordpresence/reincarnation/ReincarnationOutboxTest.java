package games.brennan.discordpresence.reincarnation;

import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.PostPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The converted reincarnation outbox: a keep-until-2xx resend queue over a "post once" dedup set.
 * Covers the never-double-post contract (a delivered death is never re-queued), full-payload
 * persistence across reloads, remove-and-mark only on a confirmed 2xx, hold-on-failure, in-flight
 * de-dup, backward-compatible loading of legacy {@code posted}-only files, and bounded eviction.
 */
class ReincarnationOutboxTest {

    private static PostPayload payload(String snapshot) {
        return new PostPayload(snapshot, "Steve", "uuid-1", 7, "https://skin", List.of("F1", "F2"));
    }

    // --- dedup contract ----------------------------------------------------

    @Test
    void blankKeysAreNeverPostable() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        assertFalse(outbox.shouldPost(null));
        assertFalse(outbox.shouldPost(""));
        assertFalse(outbox.shouldPost("   "));
    }

    @Test
    void queuedThenDeliveredIsNeverPostableAgain() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        assertTrue(outbox.shouldPost("k1"));

        outbox.enqueue("k1", payload("SNAP"));
        assertFalse(outbox.shouldPost("k1"), "already queued → not re-enqueued");
        assertEquals(1, outbox.queuedCount());

        outbox.markDelivered("k1");
        assertFalse(outbox.shouldPost("k1"), "delivered → never posted again");
        assertEquals(0, outbox.queuedCount(), "delivery removes it from the queue");
        assertEquals(1, outbox.deliveredCount());
    }

    @Test
    void deliveredDeathIsNotRepostedFromAFreshScrape() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        outbox.enqueue("death-7", payload("SNAP"));
        outbox.markDelivered("death-7");
        // A later recentDeaths scrape re-observes the same death — it must stay claimed.
        assertFalse(outbox.shouldPost("death-7"));
        outbox.enqueue("death-7", payload("SNAP")); // no-op
        assertEquals(0, outbox.queuedCount());
    }

    // --- persistence -------------------------------------------------------

    @Test
    void enqueuePersistsFullPayloadAndSurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-reincarnation.json");
        ReincarnationOutbox first = new ReincarnationOutbox();
        first.load(file);
        first.enqueue("death-1", payload("SNAP"));

        ReincarnationOutbox reloaded = new ReincarnationOutbox();
        reloaded.load(file);
        assertEquals(1, reloaded.queuedCount());
        Map<String, PostPayload> queued = reloaded.queued();
        PostPayload got = queued.get("death-1");
        assertEquals("SNAP", got.snapshot());
        assertEquals("Steve", got.name());
        assertEquals("uuid-1", got.playerId());
        assertEquals(7, (int) got.carriage());
        assertEquals("https://skin", got.skinUrl());
        assertEquals(List.of("F1", "F2"), got.friends());
        // Still undelivered → still postable on reload (kept until a 2xx).
        assertFalse(reloaded.shouldPost("death-1")); // queued, so not re-enqueued
    }

    @Test
    void carriageNullRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-reincarnation.json");
        ReincarnationOutbox first = new ReincarnationOutbox();
        first.load(file);
        first.enqueue("death-2", new PostPayload("SNAP", "Alex", null, null, "", List.of()));

        ReincarnationOutbox reloaded = new ReincarnationOutbox();
        reloaded.load(file);
        PostPayload got = reloaded.queued().get("death-2");
        assertNull(got.carriage(), "a death not on a train → carriage stays null across a reload");
    }

    @Test
    void deliveredDedupSurvivesReloadAndQueueDoesNotResurrect(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-reincarnation.json");
        ReincarnationOutbox first = new ReincarnationOutbox();
        first.load(file);
        first.enqueue("death-8", payload("SNAP"));
        first.markDelivered("death-8"); // 2xx → moves to the dedup set, drops from the queue

        ReincarnationOutbox reloaded = new ReincarnationOutbox();
        reloaded.load(file);
        assertEquals(1, reloaded.deliveredCount());
        assertEquals(0, reloaded.queuedCount());
        assertFalse(reloaded.shouldPost("death-8"), "a delivered death stays claimed after a restart");
    }

    @Test
    void queuedPayloadIsKeptWhenNotDelivered(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-reincarnation.json");
        ReincarnationOutbox first = new ReincarnationOutbox();
        first.load(file);
        first.enqueue("death-9", payload("SNAP")); // enqueued but the POST "failed" (never marked)

        ReincarnationOutbox reloaded = new ReincarnationOutbox();
        reloaded.load(file);
        assertEquals(1, reloaded.queuedCount(), "an undelivered death is held for a later retry");
        assertEquals(0, reloaded.deliveredCount());
    }

    @Test
    void loadsLegacyPostedOnlyFile(@TempDir Path dir) throws Exception {
        // Files written by the old mark-before-send guard have only {"posted":[...]} — must still dedup.
        Path file = dir.resolve("discordpresence-reincarnation.json");
        Files.writeString(file, "{\"posted\":[\"old-1\",\"old-2\"]}", StandardCharsets.UTF_8);

        ReincarnationOutbox outbox = new ReincarnationOutbox();
        outbox.load(file);
        assertEquals(2, outbox.deliveredCount());
        assertEquals(0, outbox.queuedCount());
        assertFalse(outbox.shouldPost("old-1"));
        assertFalse(outbox.shouldPost("old-2"));
        assertTrue(outbox.shouldPost("new-3"));
    }

    @Test
    void missingFileStartsEmpty(@TempDir Path dir) {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        outbox.load(dir.resolve("does-not-exist.json"));
        assertEquals(0, outbox.deliveredCount());
        assertEquals(0, outbox.queuedCount());
        assertTrue(outbox.shouldPost("anything"));
    }

    // --- in-flight de-dup --------------------------------------------------

    @Test
    void inFlightGuardBlocksConcurrentResend() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        outbox.enqueue("k", payload("SNAP"));
        assertTrue(outbox.tryBeginSend("k"), "first claim wins");
        assertFalse(outbox.tryBeginSend("k"), "a second concurrent claim is refused");
        outbox.endSend("k");
        assertTrue(outbox.tryBeginSend("k"), "after the POST completes it can be retried");
    }

    @Test
    void cannotBeginSendForAnUnqueuedKey() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        assertFalse(outbox.tryBeginSend("never-queued"));
    }

    // --- eviction ----------------------------------------------------------

    @Test
    void queueEvictsOldestPastMaxQueue() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        for (int i = 0; i <= ReincarnationOutbox.MAX_QUEUE; i++) { // one over the cap
            outbox.enqueue("k" + i, payload("SNAP" + i));
        }
        assertEquals(ReincarnationOutbox.MAX_QUEUE, outbox.queuedCount());
        Map<String, PostPayload> queued = outbox.queued();
        assertFalse(queued.containsKey("k0"), "the oldest queued death was evicted");
        assertTrue(queued.containsKey("k" + ReincarnationOutbox.MAX_QUEUE), "the newest was kept");
    }

    @Test
    void trimOldestEvictsFromTheFront() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add("a");
        set.add("b");
        set.add("c");
        set.add("d");
        ReincarnationOutbox.trimOldest(set, 2);
        assertEquals(new LinkedHashSet<>(List.of("c", "d")), set); // oldest (a, b) evicted
    }

    @Test
    void trimOldestToZeroClears() {
        LinkedHashSet<String> set = new LinkedHashSet<>(List.of("a", "b"));
        ReincarnationOutbox.trimOldest(set, 0);
        assertTrue(set.isEmpty());
    }
}
