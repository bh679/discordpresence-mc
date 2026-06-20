package games.brennan.discordpresence.reincarnation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "post once" outbox: dedup, persistence across reloads, and the bounded-eviction helper. */
class ReincarnationOutboxTest {

    @Test
    void marksAndDeduplicatesKeys() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        assertTrue(outbox.shouldPost("k1"));
        outbox.markPosted("k1");
        assertFalse(outbox.shouldPost("k1")); // posted once → never again
        assertTrue(outbox.shouldPost("k2"));
    }

    @Test
    void blankKeysAreNeverPostable() {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        assertFalse(outbox.shouldPost(null));
        assertFalse(outbox.shouldPost(""));
        assertFalse(outbox.shouldPost("   "));
    }

    @Test
    void persistsAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-reincarnation.json");
        ReincarnationOutbox first = new ReincarnationOutbox();
        first.load(file);
        first.markPosted("death-7");
        first.markPosted("death-8");

        // A fresh instance loading the same file remembers the posted keys (restart-safe dedup).
        ReincarnationOutbox reloaded = new ReincarnationOutbox();
        reloaded.load(file);
        assertEquals(2, reloaded.size());
        assertFalse(reloaded.shouldPost("death-7"));
        assertFalse(reloaded.shouldPost("death-8"));
        assertTrue(reloaded.shouldPost("death-9"));
    }

    @Test
    void missingFileStartsEmpty(@TempDir Path dir) {
        ReincarnationOutbox outbox = new ReincarnationOutbox();
        outbox.load(dir.resolve("does-not-exist.json"));
        assertEquals(0, outbox.size());
        assertTrue(outbox.shouldPost("anything"));
    }

    @Test
    void trimOldestEvictsFromTheFront() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add("a");
        set.add("b");
        set.add("c");
        set.add("d");
        ReincarnationOutbox.trimOldest(set, 2);
        assertEquals(new LinkedHashSet<>(java.util.List.of("c", "d")), set); // oldest (a, b) evicted
    }

    @Test
    void trimOldestToZeroClears() {
        LinkedHashSet<String> set = new LinkedHashSet<>(java.util.List.of("a", "b"));
        ReincarnationOutbox.trimOldest(set, 0);
        assertTrue(set.isEmpty());
    }
}
