package games.brennan.discordpresence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the public {@link DiscordPresenceConfig} getters honour a registered
 * provider. Outside a running server the SERVER config is not loaded, so the
 * config side resolves to {@code ""} and the provider drives the result — exactly
 * the bundled-mod path, and proof the getter wiring (not just the helpers) works.
 */
class DiscordPresenceConfigSeamTest {

    @AfterEach
    void clearProvider() {
        DiscordCredentials.register(null); // isolate the shared static slot
    }

    @Test
    void noProvider_gettersBlank() {
        DiscordCredentials.register(null);
        assertEquals("", DiscordPresenceConfig.getWebhookUrl());
        assertEquals("", DiscordPresenceConfig.getBotToken());
    }

    @Test
    void provider_drivesGetters_whenConfigUnloaded() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return "https://relay/hook"; }
            @Override public String botToken() { return "tok"; }
        });
        assertEquals("https://relay/hook", DiscordPresenceConfig.getWebhookUrl());
        assertEquals("tok", DiscordPresenceConfig.getBotToken());
    }

    @Test
    void clearingProvider_revertsToBlank() {
        DiscordCredentials.register(() -> "https://relay/hook");
        DiscordCredentials.register(null);
        assertEquals("", DiscordPresenceConfig.getWebhookUrl());
    }

    @Test
    void noRelay_botApiBaseIsDiscordDirect() {
        DiscordCredentials.register(null);
        assertFalse(DiscordPresenceConfig.isRelayMode());
        assertEquals("https://discord.com/api/v10", DiscordPresenceConfig.getBotApiBase());
    }

    @Test
    void relayMode_derivesHookAndBotBaseFromRelayBase() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String relayBaseUrl() { return "https://brennan.games/api/dp-relay/CAP"; }
        });
        assertTrue(DiscordPresenceConfig.isRelayMode());
        assertEquals("https://brennan.games/api/dp-relay/CAP/hook", DiscordPresenceConfig.getWebhookUrl());
        assertEquals("https://brennan.games/api/dp-relay/CAP/bot", DiscordPresenceConfig.getBotApiBase());
    }

    @Test
    void relayMode_stripsTrailingSlashOnBase() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String relayBaseUrl() { return "https://relay/base/"; }
        });
        assertEquals("https://relay/base/hook", DiscordPresenceConfig.getWebhookUrl());
        assertEquals("https://relay/base/bot", DiscordPresenceConfig.getBotApiBase());
    }
}
