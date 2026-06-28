package games.brennan.discordpresence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void requireEngagement_defaultsFalse_providerCanEnable() {
        DiscordCredentials.register(null);
        assertFalse(DiscordCredentials.providerRequireEngagementForGameRelay());
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean requireEngagementForGameRelay() { return true; }
        });
        assertTrue(DiscordCredentials.providerRequireEngagementForGameRelay());
    }

    @Test
    void gameRelayMentions_defaultEmpty_nullAndThrowSafe() {
        DiscordCredentials.register(null);
        assertTrue(DiscordCredentials.providerGameRelayMentions().isEmpty());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> gameRelayMentions() { return null; }
        });
        assertTrue(DiscordCredentials.providerGameRelayMentions().isEmpty()); // null → empty

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> gameRelayMentions() { throw new RuntimeException("boom"); }
        });
        assertTrue(DiscordCredentials.providerGameRelayMentions().isEmpty()); // throw → empty

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> gameRelayMentions() { return List.of("@dev=<@1>"); }
        });
        assertEquals(List.of("@dev=<@1>"), DiscordCredentials.providerGameRelayMentions());
    }

    @Test
    void surveyPingUserIds_defaultEmpty_nullAndThrowSafe() {
        DiscordCredentials.register(null);
        assertTrue(DiscordCredentials.providerSurveyPingUserIds().isEmpty());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> surveyPingUserIds() { return null; }
        });
        assertTrue(DiscordCredentials.providerSurveyPingUserIds().isEmpty()); // null → empty

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> surveyPingUserIds() { throw new RuntimeException("boom"); }
        });
        assertTrue(DiscordCredentials.providerSurveyPingUserIds().isEmpty()); // throw → empty

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> surveyPingUserIds() { return List.of("342110421114945537"); }
        });
        assertEquals(List.of("342110421114945537"), DiscordCredentials.providerSurveyPingUserIds());
    }

    @Test
    void surveyResultsCopyEnabled_defaultsFalse_providerCanEnable_throwSafe() {
        DiscordCredentials.register(null);
        assertFalse(DiscordCredentials.providerSurveyResultsCopyEnabled());

        DiscordCredentials.register(() -> ""); // default surveyResultsCopyEnabled() == false
        assertFalse(DiscordCredentials.providerSurveyResultsCopyEnabled());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean surveyResultsCopyEnabled() { return true; }
        });
        assertTrue(DiscordCredentials.providerSurveyResultsCopyEnabled());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean surveyResultsCopyEnabled() { throw new RuntimeException("boom"); }
        });
        assertFalse(DiscordCredentials.providerSurveyResultsCopyEnabled()); // throw → false
    }

    @Test
    void surveyResultsWebhookUrl_defaultBlank_nullAndThrowSafe() {
        DiscordCredentials.register(null);
        assertEquals("", DiscordCredentials.providerSurveyResultsWebhookUrl());

        DiscordCredentials.register(() -> ""); // default surveyResultsWebhookUrl() == ""
        assertEquals("", DiscordCredentials.providerSurveyResultsWebhookUrl());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsWebhookUrl() { return null; }
        });
        assertEquals("", DiscordCredentials.providerSurveyResultsWebhookUrl()); // null → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsWebhookUrl() { throw new RuntimeException("boom"); }
        });
        assertEquals("", DiscordCredentials.providerSurveyResultsWebhookUrl()); // throw → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsWebhookUrl() { return "https://relay/survey/hook"; }
        });
        assertEquals("https://relay/survey/hook", DiscordCredentials.providerSurveyResultsWebhookUrl());
    }

    @Test
    void surveyResultsLinkGuildId_defaultBlank_nullAndThrowSafe() {
        DiscordCredentials.register(null);
        assertEquals("", DiscordCredentials.providerSurveyResultsLinkGuildId());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsLinkGuildId() { return null; }
        });
        assertEquals("", DiscordCredentials.providerSurveyResultsLinkGuildId()); // null → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsLinkGuildId() { throw new RuntimeException("boom"); }
        });
        assertEquals("", DiscordCredentials.providerSurveyResultsLinkGuildId()); // throw → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String surveyResultsLinkGuildId() { return "1234567890"; }
        });
        assertEquals("1234567890", DiscordCredentials.providerSurveyResultsLinkGuildId());
    }

    @Test
    void advancementSuffix_defaultBlank_nullAndThrowSafe() {
        UUID player = UUID.randomUUID();

        DiscordCredentials.register(null);
        assertEquals("", DiscordCredentials.providerAdvancementSuffix(player, "minecraft:story/root"));

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String advancementMessageSuffix(UUID id, String adv) { return null; }
        });
        assertEquals("", DiscordCredentials.providerAdvancementSuffix(player, "minecraft:story/root")); // null → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String advancementMessageSuffix(UUID id, String adv) { throw new RuntimeException("boom"); }
        });
        assertEquals("", DiscordCredentials.providerAdvancementSuffix(player, "minecraft:story/root")); // throw → ""

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String advancementMessageSuffix(UUID id, String adv) { return "Carriage +1 · Difficulty Level 0"; }
        });
        assertEquals("Carriage +1 · Difficulty Level 0",
                DiscordCredentials.providerAdvancementSuffix(player, "minecraft:story/root"));
    }
}
