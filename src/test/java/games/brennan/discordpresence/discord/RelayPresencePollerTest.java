package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the relay's {@code /presence/<id>} JSON and the relay-mode store write path. Pure logic
 * — no network or Minecraft runtime. Backs the relay-mode "last seen online" seam.
 */
class RelayPresencePollerTest {

    private static final String USER = "342110421114945537";

    @Test
    void parsesOnlineStatusAndLastOnline() {
        RelayPresencePoller.Parsed p =
                RelayPresencePoller.parse("{\"userId\":\"" + USER + "\",\"status\":\"online\",\"lastOnlineMillis\":1781522645837}");
        assertEquals("online", p.status());
        assertEquals(1781522645837L, p.lastOnlineMillis());
    }

    @Test
    void parsesOfflineWithFrozenLastOnline() {
        RelayPresencePoller.Parsed p =
                RelayPresencePoller.parse("{\"status\":\"offline\",\"lastOnlineMillis\":500}");
        assertEquals("offline", p.status());
        assertEquals(500L, p.lastOnlineMillis());
    }

    @Test
    void unknownPresenceParsesToNullStatus() {
        // Relay returns null fields when it holds no presence for the user → caller skips (keeps prior).
        RelayPresencePoller.Parsed p =
                RelayPresencePoller.parse("{\"userId\":\"" + USER + "\",\"status\":null,\"lastOnlineMillis\":null}");
        assertNull(p.status());
        assertEquals(0L, p.lastOnlineMillis());
        // Missing keys tolerate to the same.
        RelayPresencePoller.Parsed q = RelayPresencePoller.parse("{\"userId\":\"" + USER + "\"}");
        assertNull(q.status());
        assertEquals(0L, q.lastOnlineMillis());
    }

    @Test
    void setRelayStoresTheRelaysAuthoritativeValues(@TempDir Path dir) {
        DiscordPresenceStore store = new DiscordPresenceStore();
        store.load(dir.resolve("p.json"));

        // Offline-with-history: the relay's lastOnlineMillis is stored verbatim (not "now").
        store.setRelay(USER, "offline", 1234L);
        assertEquals("offline", store.status(USER).orElse(null));
        assertEquals(Optional.of(1234L), store.lastOnlineMillis(USER));

        // Online overwrites with the relay's newer value.
        store.setRelay(USER, "online", 5000L);
        assertEquals("online", store.status(USER).orElse(null));
        assertEquals(Optional.of(5000L), store.lastOnlineMillis(USER));

        // Blank/null status reads as offline; a zero lastOnline means never-observed → empty.
        store.setRelay(USER, null, 0L);
        assertEquals("offline", store.status(USER).orElse(null));
        assertFalse(store.lastOnlineMillis(USER).isPresent());
    }
}
