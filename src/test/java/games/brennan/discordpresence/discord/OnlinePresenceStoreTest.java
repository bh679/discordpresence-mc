package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + resilience for {@link OnlinePresenceStore}'s on-disk format. Pure file I/O —
 * no Minecraft runtime needed. The store persists each online player's reaction-bearing
 * message ref (parent channel + message id) plus a {@code lastSeen} timestamp, so a stale
 * green reaction can be removed after a crash.
 */
class OnlinePresenceStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void recordThenReloadRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-presence.json");

        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file); // no file yet → empty
        store.recordOnline(PLAYER, new DiscordMessageRef("chan-9", "msg-9"), 1_700_000_000_000L);

        // On-disk shape: object with channelId, messageId, lastSeen.
        JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = obj.getAsJsonObject(PLAYER.toString());
        assertEquals("chan-9", entry.get("channelId").getAsString());
        assertEquals("msg-9", entry.get("messageId").getAsString());
        assertEquals(1_700_000_000_000L, entry.get("lastSeen").getAsLong());

        // A fresh store reads it back intact.
        OnlinePresenceStore reloaded = new OnlinePresenceStore();
        reloaded.load(file);
        OnlinePresenceStore.PresenceEntry got = reloaded.entries().get(PLAYER);
        assertNotNull(got);
        assertEquals(new DiscordMessageRef("chan-9", "msg-9"), got.ref());
        assertEquals(1_700_000_000_000L, got.lastSeen());
    }

    @Test
    void markOfflineRemovesEntryAndPersists(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-presence.json");
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        store.recordOnline(PLAYER, new DiscordMessageRef("c", "m"), 1L);

        store.markOffline(PLAYER);
        assertTrue(store.isEmpty());

        OnlinePresenceStore reloaded = new OnlinePresenceStore();
        reloaded.load(file);
        assertNull(reloaded.entries().get(PLAYER), "offline player is gone after reload");
    }

    @Test
    void touchUpdatesLastSeenForConnectedOnly(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-presence.json");
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        store.recordOnline(PLAYER, new DiscordMessageRef("c1", "m1"), 100L);
        store.recordOnline(PLAYER_2, new DiscordMessageRef("c2", "m2"), 100L);

        store.touch(List.of(PLAYER), 999L); // only PLAYER is still connected

        assertEquals(999L, store.entries().get(PLAYER).lastSeen());
        assertEquals(100L, store.entries().get(PLAYER_2).lastSeen(), "untouched player keeps old lastSeen");
        assertEquals("m1", store.entries().get(PLAYER).ref().messageId(), "ref is preserved on touch");
    }

    @Test
    void dropStaleRemovesUnchangedEntries(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-presence.json");
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        store.recordOnline(PLAYER, new DiscordMessageRef("c1", "m1"), 1L);
        store.recordOnline(PLAYER_2, new DiscordMessageRef("c2", "m2"), 1L);

        store.dropStale(store.entries()); // nothing changed since the snapshot → all removed
        assertTrue(store.isEmpty());
    }

    @Test
    void dropStaleKeepsEntryChangedSinceSnapshot(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-presence.json");
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        store.recordOnline(PLAYER, new DiscordMessageRef("c", "m"), 1L);

        Map<UUID, OnlinePresenceStore.PresenceEntry> snapshot = store.entries();
        store.recordOnline(PLAYER, new DiscordMessageRef("c", "m"), 2L); // "reconnect" → fresher lastSeen

        store.dropStale(snapshot); // CAS sees the entry changed → must NOT remove it
        assertNotNull(store.entries().get(PLAYER), "a reconnect after the snapshot is never clobbered");
        assertEquals(2L, store.entries().get(PLAYER).lastSeen());
    }

    @Test
    void entriesIsSnapshot(@TempDir Path dir) {
        Path file = dir.resolve("discordpresence-presence.json");
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        store.recordOnline(PLAYER, new DiscordMessageRef("c", "m"), 1L);

        Map<UUID, OnlinePresenceStore.PresenceEntry> snap = store.entries();
        store.markOffline(PLAYER); // mutate after snapshot
        assertFalse(snap.isEmpty(), "snapshot is decoupled from the live map");
    }

    @Test
    void skipsEntryWithoutMessageId(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-presence.json");
        Files.writeString(file,
                "{\"" + PLAYER + "\":{\"channelId\":\"c\",\"messageId\":\"ok\",\"lastSeen\":5},"
                        + "\"" + PLAYER_2 + "\":{\"channelId\":\"c\",\"lastSeen\":5}}",
                StandardCharsets.UTF_8);

        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);

        assertEquals("ok", store.entries().get(PLAYER).ref().messageId());
        assertNull(store.entries().get(PLAYER_2), "entry without messageId is skipped");
    }

    @Test
    void missingLastSeenDefaultsToZero(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-presence.json");
        Files.writeString(file,
                "{\"" + PLAYER + "\":{\"channelId\":\"c\",\"messageId\":\"m\"}}",
                StandardCharsets.UTF_8);

        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        assertEquals(0L, store.entries().get(PLAYER).lastSeen());
    }

    @Test
    void corruptFileYieldsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-presence.json");
        Files.writeString(file, "{ this is not valid json ", StandardCharsets.UTF_8);

        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(file);
        assertTrue(store.isEmpty());
    }

    @Test
    void missingFileYieldsEmpty(@TempDir Path dir) {
        OnlinePresenceStore store = new OnlinePresenceStore();
        store.load(dir.resolve("does-not-exist.json"));
        assertTrue(store.isEmpty());
    }
}
