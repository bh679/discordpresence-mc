package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the server-stop online-reaction clear decision. */
class PresenceShutdownTest {

    @Test
    void integratedServerWithReactionsInUseClears() {
        // Single-player / LAN host: JVM stays alive after quit-to-title, so clear the greens.
        assertTrue(DiscordService.shouldClearReactionsOnShutdown(false, true));
    }

    @Test
    void dedicatedServerNeverClearsOnShutdown() {
        // JVM is about to exit (daemon HTTP threads die) → leave it to the startup reconcile.
        assertFalse(DiscordService.shouldClearReactionsOnShutdown(true, true));
    }

    @Test
    void noReactionsInUseSkips() {
        // Blank online emoji or no bot available → nothing to remove.
        assertFalse(DiscordService.shouldClearReactionsOnShutdown(false, false));
        assertFalse(DiscordService.shouldClearReactionsOnShutdown(true, false));
    }
}
