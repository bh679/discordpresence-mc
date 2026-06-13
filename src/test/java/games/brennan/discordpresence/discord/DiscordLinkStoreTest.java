package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the durable account-link store (persistence + reverse lookup + tolerance). */
class DiscordLinkStoreTest {

    private static final UUID UID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final String DISCORD_ID = "123456789012345678";

    @Test
    void putGetAndReverseLookup(@TempDir Path dir) {
        DiscordLinkStore store = new DiscordLinkStore();
        store.load(dir.resolve("links.json")); // no file yet
        assertEquals(0, store.size());

        store.put(UID, DISCORD_ID);
        assertEquals(DISCORD_ID, store.getDiscordId(UID));
        assertEquals(UID, store.getUuidForDiscord(DISCORD_ID));
        assertNull(store.getUuidForDiscord("999"));
        assertEquals(1, store.size());
    }

    @Test
    void persistsAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("links.json");
        DiscordLinkStore store = new DiscordLinkStore();
        store.load(file);
        store.put(UID, DISCORD_ID);

        DiscordLinkStore reloaded = new DiscordLinkStore();
        reloaded.load(file);
        assertEquals(DISCORD_ID, reloaded.getDiscordId(UID), "link must survive a reload");
    }

    @Test
    void removeClearsLink(@TempDir Path dir) {
        DiscordLinkStore store = new DiscordLinkStore();
        store.load(dir.resolve("links.json"));
        store.put(UID, DISCORD_ID);

        assertTrue(store.remove(UID));
        assertNull(store.getDiscordId(UID));
        assertFalse(store.remove(UID), "removing an absent link returns false");
    }

    @Test
    void corruptFileYieldsEmptyStore(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("links.json");
        Files.writeString(file, "this is not json", StandardCharsets.UTF_8);

        DiscordLinkStore store = new DiscordLinkStore();
        store.load(file); // must not throw
        assertEquals(0, store.size());
    }
}
