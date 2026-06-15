package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "is this status online" + "has a usable id" truth tables for {@link PresenceUpdate}. */
class PresenceUpdateTest {

    @Test
    void onlineIdleDndCountAsOnline() {
        assertTrue(new PresenceUpdate("1", "online").isOnline());
        assertTrue(new PresenceUpdate("1", "idle").isOnline());
        assertTrue(new PresenceUpdate("1", "dnd").isOnline());
    }

    @Test
    void offlineBlankNullAreNotOnline() {
        assertFalse(new PresenceUpdate("1", "offline").isOnline());
        assertFalse(new PresenceUpdate("1", "").isOnline());
        assertFalse(new PresenceUpdate("1", "   ").isOnline());
        assertFalse(new PresenceUpdate("1", null).isOnline());
    }

    @Test
    void hasUserIdRejectsBlankAndNull() {
        assertTrue(new PresenceUpdate("123", "online").hasUserId());
        assertFalse(new PresenceUpdate("", "online").hasUserId());
        assertFalse(new PresenceUpdate("  ", "online").hasUserId());
        assertFalse(new PresenceUpdate(null, "online").hasUserId());
    }
}
