package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip + resilience for {@link DiscordPresenceStore}'s on-disk format. Pure file I/O — no
 * Minecraft runtime needed. The store maps a Discord user id to their last known status + the
 * epoch-millis they were last seen online (frozen when they go offline), backing the "last seen
 * online" query seam.
 */
class DiscordPresenceStoreTest {

    private static final String USER = "342110421114945537";

    @Test
    void recordOnlineThenReloadRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("discordpresence-discord-presence.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file); // no file yet → empty
        store.record(USER, "online", 1_700_000_000_000L);

        // On-disk shape: object with status + lastOnlineMillis.
        JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = obj.getAsJsonObject(USER);
        assertEquals("online", entry.get("status").getAsString());
        assertEquals(1_700_000_000_000L, entry.get("lastOnlineMillis").getAsLong());

        // A fresh store reads it back intact.
        DiscordPresenceStore reloaded = new DiscordPresenceStore();
        reloaded.load(file);
        assertEquals(Optional.of(1_700_000_000_000L), reloaded.lastOnlineMillis(USER));
        assertEquals(Optional.of("online"), reloaded.status(USER));
    }

    @Test
    void offlineFreezesLastSeenInsteadOfBumpingIt(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        store.record(USER, "online", 100L);
        store.record(USER, "offline", 999L); // going offline must NOT advance lastOnlineMillis

        assertEquals(Optional.of(100L), store.lastOnlineMillis(USER));
        assertEquals(Optional.of("offline"), store.status(USER));
    }

    @Test
    void idleAndDndCountAsOnlineAndBumpLastSeen(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        store.record(USER, "online", 100L);
        store.record(USER, "idle", 200L);
        assertEquals(Optional.of(200L), store.lastOnlineMillis(USER));
        store.record(USER, "dnd", 300L);
        assertEquals(Optional.of(300L), store.lastOnlineMillis(USER));
    }

    @Test
    void offlineWithoutPriorOnlineHasNoLastSeenButKnownStatus(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        store.record(USER, "offline", 500L); // we have only ever observed them offline

        assertEquals(Optional.empty(), store.lastOnlineMillis(USER)); // unknown last-seen
        assertEquals(Optional.of("offline"), store.status(USER));     // but the status is known
    }

    @Test
    void unknownUserYieldsEmptyOptionals(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        assertEquals(Optional.empty(), store.lastOnlineMillis("nobody"));
        assertEquals(Optional.empty(), store.status("nobody"));
        assertEquals(Optional.empty(), store.lastOnlineMillis(null));
        assertEquals(Optional.empty(), store.status(null));
    }

    @Test
    void blankOrNullUserIdIsIgnored(@TempDir Path dir) {
        Path file = dir.resolve("p.json");
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        store.record("", "online", 1L);
        store.record(null, "online", 1L);
        assertTrue(store.isEmpty());
    }

    @Test
    void corruptFileYieldsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.json");
        Files.writeString(file, "{ this is not valid json ", StandardCharsets.UTF_8);
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        assertTrue(store.isEmpty());
    }

    @Test
    void missingFileYieldsEmpty(@TempDir Path dir) {
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(dir.resolve("does-not-exist.json"));
        assertTrue(store.isEmpty());
    }

    @Test
    void skipsEntryWithNeitherStatusNorTimestamp(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("p.json");
        Files.writeString(file,
                "{\"" + USER + "\":{\"status\":\"online\",\"lastOnlineMillis\":5},"
                        + "\"empty\":{}}",
                StandardCharsets.UTF_8);

        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(file);
        assertEquals(Optional.of("online"), store.status(USER));
        assertEquals(Optional.empty(), store.status("empty"), "an entry with no info is skipped");
    }
}
