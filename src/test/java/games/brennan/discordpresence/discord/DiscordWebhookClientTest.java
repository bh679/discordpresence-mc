package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression tests for the Discord webhook-username sanitiser. */
class DiscordWebhookClientTest {

    @Test
    void allowsNormalPlayerNames() {
        assertEquals("Steve", DiscordWebhookClient.safeUsername("Steve"));
        assertEquals("Notch_99", DiscordWebhookClient.safeUsername("Notch_99"));
    }

    @Test
    void rejectsDiscordAndClydeSubstrings() {
        // Discord returns HTTP 400 for these — must fall back to the webhook name.
        assertNull(DiscordWebhookClient.safeUsername("discordfan"));
        assertNull(DiscordWebhookClient.safeUsername("ProDISCORDgg"));
        assertNull(DiscordWebhookClient.safeUsername("clyde"));
        assertNull(DiscordWebhookClient.safeUsername("xX_Clyde_Xx"));
    }

    @Test
    void rejectsBlankOrOverlong() {
        assertNull(DiscordWebhookClient.safeUsername(null));
        assertNull(DiscordWebhookClient.safeUsername("   "));
        assertNull(DiscordWebhookClient.safeUsername("a".repeat(81)));
    }
}
