package games.brennan.discordpresence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Behaviour of the static provider holder: registration, null/throw safety, clearing. */
class DiscordCredentialsTest {

    @AfterEach
    void clearProvider() {
        DiscordCredentials.register(null); // isolate the shared static slot between tests
    }

    @Test
    void noProvider_returnsBlank() {
        DiscordCredentials.register(null);
        assertEquals("", DiscordCredentials.providerWebhookUrl());
        assertEquals("", DiscordCredentials.providerBotToken());
    }

    @Test
    void provider_suppliesWebhookAndToken() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return "https://relay/hook"; }
            @Override public String botToken() { return "tok"; }
        });
        assertEquals("https://relay/hook", DiscordCredentials.providerWebhookUrl());
        assertEquals("tok", DiscordCredentials.providerBotToken());
    }

    @Test
    void webhookOnlyProvider_tokenDefaultsBlank() {
        DiscordCredentials.register(() -> "https://relay/hook"); // default botToken() == ""
        assertEquals("https://relay/hook", DiscordCredentials.providerWebhookUrl());
        assertEquals("", DiscordCredentials.providerBotToken());
    }

    @Test
    void providerReturningNull_mappedToBlank() {
        DiscordCredentials.register(() -> null);
        assertEquals("", DiscordCredentials.providerWebhookUrl());
    }

    @Test
    void providerThrowing_swallowedToBlank() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { throw new RuntimeException("boom"); }
        });
        assertEquals("", DiscordCredentials.providerWebhookUrl());
        assertEquals("", DiscordCredentials.providerBotToken()); // default "" never throws
    }

    @Test
    void registerNull_clearsPreviousProvider() {
        DiscordCredentials.register(() -> "https://relay/hook");
        DiscordCredentials.register(null);
        assertEquals("", DiscordCredentials.providerWebhookUrl());
    }

    @Test
    void provider_suppliesRelayBaseUrl() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String relayBaseUrl() { return "https://relay/base"; }
        });
        assertEquals("https://relay/base", DiscordCredentials.providerRelayBaseUrl());
    }

    @Test
    void relayBaseUrl_defaultsBlankWhenNotOverridden() {
        DiscordCredentials.register(() -> "https://relay/hook"); // only webhookUrl() overridden
        assertEquals("", DiscordCredentials.providerRelayBaseUrl());
    }
}
