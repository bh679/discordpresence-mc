package games.brennan.discordpresence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
