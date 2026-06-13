package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + backward-compatibility for {@link DiscordThreadStore}'s on-disk format.
 * Pure file I/O — no Minecraft runtime needed. The store persists the per-player anchor
 * ref (parent channel + thread id) as an object, and still reads legacy bare thread-id
 * strings so existing {@code discordpresence-threads.json} files keep working.
 */
class DiscordThreadStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void loadsLegacyBareStringEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-threads.json");
        Files.writeString(file, "{\"" + PLAYER + "\":\"123456789\"}", StandardCharsets.UTF_8);

        DiscordThreadStore store = new DiscordThreadStore();
        store.load(file);

        DiscordMessageRef ref = store.get(PLAYER);
        assertNotNull(ref);
        assertNull(ref.channelId(), "legacy entry has no parent channel until backfilled");
        assertEquals("123456789", ref.messageId());
        assertEquals("123456789", store.threadId(PLAYER));
    }

    @Test
    void loadsObjectEntry(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-threads.json");
        Files.writeString(file,
                "{\"" + PLAYER + "\":{\"channelId\":\"chan-1\",\"messageId\":\"msg-1\"}}",
                StandardCharsets.UTF_8);

        DiscordThreadStore store = new DiscordThreadStore();
        store.load(file);

        DiscordMessageRef ref = store.get(PLAYER);
        assertNotNull(ref);
        assertEquals("chan-1", ref.channelId());
        assertEquals("msg-1", ref.messageId());
    }

    @Test
    void putThenReloadRoundTripsAsObject(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-threads.json");

        DiscordThreadStore store = new DiscordThreadStore();
        store.load(file); // no file yet → empty
        store.put(PLAYER, new DiscordMessageRef("chan-9", "msg-9"));

        // On-disk shape is the object form.
        JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = obj.getAsJsonObject(PLAYER.toString());
        assertEquals("chan-9", entry.get("channelId").getAsString());
        assertEquals("msg-9", entry.get("messageId").getAsString());

        // A fresh store reads it back intact.
        DiscordThreadStore reloaded = new DiscordThreadStore();
        reloaded.load(file);
        assertEquals(new DiscordMessageRef("chan-9", "msg-9"), reloaded.get(PLAYER));
    }

    @Test
    void legacyEntryUpgradesToObjectOnPut(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-threads.json");
        Files.writeString(file, "{\"" + PLAYER + "\":\"thread-7\"}", StandardCharsets.UTF_8);

        DiscordThreadStore store = new DiscordThreadStore();
        store.load(file);
        // Simulate the backfill: parent channel resolved, store upgraded to the object form.
        store.put(PLAYER, new DiscordMessageRef("parent-7", "thread-7"));

        JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(obj.get(PLAYER.toString()).isJsonObject(), "legacy string upgraded to object form");
        assertEquals("parent-7", obj.getAsJsonObject(PLAYER.toString()).get("channelId").getAsString());
    }

    @Test
    void skipsInvalidEntries(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-threads.json");
        // A non-UUID key and an object missing messageId are both skipped; the valid one loads.
        Files.writeString(file,
                "{\"not-a-uuid\":\"x\",\"" + PLAYER + "\":\"ok-1\","
                        + "\"11111111-1111-1111-1111-111111111111\":{\"channelId\":\"c\"}}",
                StandardCharsets.UTF_8);

        DiscordThreadStore store = new DiscordThreadStore();
        store.load(file);

        assertEquals("ok-1", store.threadId(PLAYER));
        assertNull(store.get(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                "object entry without messageId is skipped");
    }

    @Test
    void missingFileYieldsEmpty(@TempDir Path dir) {
        DiscordThreadStore store = new DiscordThreadStore();
        store.load(dir.resolve("does-not-exist.json"));
        assertNull(store.get(PLAYER));
        assertNull(store.threadId(PLAYER));
    }
}
