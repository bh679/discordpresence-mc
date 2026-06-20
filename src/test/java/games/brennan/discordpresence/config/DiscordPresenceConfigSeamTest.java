package games.brennan.discordpresence.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.discordpresence.config.CredentialResolver.Policy.PROVIDER_WINS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the public {@link DiscordPresenceConfig} getters honour a registered
 * provider. In a unit test the COMMON config is not loaded ({@code isLoaded()} is
 * false), so the config side resolves to {@code ""} and the provider drives the
 * result — exactly the bundled-mod path, and proof the getter wiring (not just the
 * helpers) works.
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

    @Test
    void providerCanSuppressAutoDeathReport() {
        // No provider → DP's auto-death-report follows config (default true).
        DiscordCredentials.register(null);
        assertTrue(DiscordPresenceConfig.isAutoDeathReport());
        // A bundling mod that posts its own death report suppresses DP's generic one.
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean suppressAutoDeathReport() { return true; }
        });
        assertFalse(DiscordPresenceConfig.isAutoDeathReport());
    }

    @Test
    void disconnectReportDefaults_whenConfigUnloaded() {
        DiscordCredentials.register(null);
        assertFalse(DiscordPresenceConfig.isAutoDisconnectReport()); // off by default for standalone DP
        assertEquals(DiscordPresenceConfig.DEFAULT_DISCONNECT_REPORT_TITLE,
                DiscordPresenceConfig.getDisconnectReportTitleTemplate());
        assertEquals(DiscordPresenceConfig.DEFAULT_DISCONNECT_REPORT_EMBED_COLOR,
                DiscordPresenceConfig.getDisconnectReportEmbedColor());
    }

    @Test
    void providerCanSuppressAutoDisconnectReport() {
        // The seam mirrors the death report. The disconnect report defaults OFF, so this is verified
        // at the holder (the getter can't be flipped ON outside a running server / loaded config).
        DiscordCredentials.register(null);
        assertFalse(DiscordCredentials.providerSuppressAutoDisconnectReport());

        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean suppressAutoDisconnectReport() { return true; }
        });
        assertTrue(DiscordCredentials.providerSuppressAutoDisconnectReport());
        assertFalse(DiscordPresenceConfig.isAutoDisconnectReport()); // suppressed → off
    }

    @Test
    void providerCanRequireEngagementForGameRelay() {
        // No provider → engaged-only gate follows config (default off; DT registers no override).
        DiscordCredentials.register(null);
        assertFalse(DiscordPresenceConfig.isRelayGameToDiscordEngagedOnly());
        // A bundling mod could force the gate on (not done by DT at this stage).
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public boolean requireEngagementForGameRelay() { return true; }
        });
        assertTrue(DiscordPresenceConfig.isRelayGameToDiscordEngagedOnly());
    }

    @Test
    void providerSuppliesGameRelayMentions() {
        // No provider + config unloaded → no triggers.
        DiscordCredentials.register(null);
        assertTrue(DiscordPresenceConfig.getGameRelayMentions().isEmpty());
        // A bundling mod could supply the trigger list (not done by DT at this stage).
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> gameRelayMentions() {
                return List.of("@dev=<@342110421114945537>", "@brennanhatton=<@342110421114945537>");
            }
        });
        assertEquals(List.of("@dev=<@342110421114945537>", "@brennanhatton=<@342110421114945537>"),
                DiscordPresenceConfig.getGameRelayMentions());
    }

    @Test
    void presenceTrackingDefaultsOff_andProviderEnablesIt() {
        // No provider + config unloaded → no tracked ids, tracking disabled.
        DiscordCredentials.register(null);
        assertTrue(DiscordPresenceConfig.getPresenceTrackUserIds().isEmpty());
        assertFalse(DiscordPresenceConfig.isPresenceTrackingEnabled());
        // A bundling mod supplies the ids (this is how DT will configure Brennan's id).
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> presenceTrackUserIds() { return List.of("342110421114945537"); }
        });
        assertEquals(List.of("342110421114945537"), DiscordPresenceConfig.getPresenceTrackUserIds());
        assertTrue(DiscordPresenceConfig.isPresenceTrackingEnabled());
    }

    @Test
    void presenceTrackUserIds_nullProviderListIsSafe() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public List<String> presenceTrackUserIds() { return null; }
        });
        assertTrue(DiscordPresenceConfig.getPresenceTrackUserIds().isEmpty());
        assertFalse(DiscordPresenceConfig.isPresenceTrackingEnabled());
    }

    // --- Dev webhook env-var override (DISCORDPRESENCE_DEV_WEBHOOK_URL) ----------------------------
    // The env read itself isn't portably settable in a unit test, so the precedence + relay-forcing
    // logic is extracted into pure helpers and exercised here directly. The blank-relay → Discord-direct
    // consequence is covered by noRelay_botApiBaseIsDiscordDirect above.

    @Test
    void devOverride_winsOverConfigProviderAndRelay() {
        assertEquals("https://dev/hook", DiscordPresenceConfig.resolveWebhookUrl(
                "https://dev/hook", "https://relay/base", "https://admin/hook", "https://provider/hook", PROVIDER_WINS));
    }

    @Test
    void devOverride_isTrimmed() {
        assertEquals("https://dev/hook", DiscordPresenceConfig.resolveWebhookUrl(
                "  https://dev/hook  ", "", "", "", PROVIDER_WINS));
    }

    @Test
    void devOverride_whitespaceIgnored_fallsThroughToRelay() {
        assertEquals("https://relay/base/hook", DiscordPresenceConfig.resolveWebhookUrl(
                "   ", "https://relay/base", "https://admin/hook", "https://provider/hook", PROVIDER_WINS));
    }

    @Test
    void devOverride_nullIsSafe_resolvesConfigProvider() {
        assertEquals("https://provider/hook", DiscordPresenceConfig.resolveWebhookUrl(
                null, "", "https://admin/hook", "https://provider/hook", PROVIDER_WINS));
    }

    @Test
    void noDevOverride_relayMode_derivesHook() {
        assertEquals("https://relay/base/hook", DiscordPresenceConfig.resolveWebhookUrl(
                "", "https://relay/base", "https://admin/hook", "https://provider/hook", PROVIDER_WINS));
    }

    @Test
    void devOverride_forcesRelayOff() {
        assertEquals("", DiscordPresenceConfig.resolveRelayBaseUrl(
                "https://dev/hook", "https://relay/base", "https://provider/relay", PROVIDER_WINS));
    }

    @Test
    void noDevOverride_relayResolvesAndStripsTrailingSlash() {
        assertEquals("https://relay/base", DiscordPresenceConfig.resolveRelayBaseUrl(
                "", "https://relay/base/", "", PROVIDER_WINS));
    }

    @Test
    void devOverride_whitespaceDoesNotForceRelayOff() {
        assertEquals("https://relay/base", DiscordPresenceConfig.resolveRelayBaseUrl(
                "   ", "https://relay/base", "", PROVIDER_WINS));
    }

    // --- Routing description for startup logging (secret-safe) ------------------------------------

    @Test
    void describeRouting_devOverride_directAndFlagTrue() {
        assertEquals("devOverride=true, mode=direct, target=discord.com",
                DiscordPresenceConfig.describeRouting(true, false, "",
                        "https://discord.com/api/webhooks/123/SECRET"));
    }

    @Test
    void describeRouting_relayMode_targetIsRelayHost_flagFalse() {
        assertEquals("devOverride=false, mode=relay, target=brennan.games",
                DiscordPresenceConfig.describeRouting(false, true,
                        "https://brennan.games/api/dp-relay/CAP",
                        "https://brennan.games/api/dp-relay/CAP/hook"));
    }

    @Test
    void describeRouting_directNoOverride_targetIsWebhookHost() {
        assertEquals("devOverride=false, mode=direct, target=discord.com",
                DiscordPresenceConfig.describeRouting(false, false, "",
                        "https://discord.com/api/webhooks/123/SECRET"));
    }

    @Test
    void describeRouting_neverLeaksWebhookTokenOrId() {
        String s = DiscordPresenceConfig.describeRouting(true, false, "",
                "https://discord.com/api/webhooks/123456/SUPERSECRETTOKEN");
        assertFalse(s.contains("SUPERSECRETTOKEN"));
        assertFalse(s.contains("123456"));
    }

    @Test
    void describeRouting_neverLeaksRelayCapabilityToken() {
        String s = DiscordPresenceConfig.describeRouting(false, true,
                "https://brennan.games/api/dp-relay/CAPTOKEN",
                "https://brennan.games/api/dp-relay/CAPTOKEN/hook");
        assertFalse(s.contains("CAPTOKEN"));
    }

    @Test
    void safeHost_blankAndMalformedAreSafe() {
        assertEquals("none", DiscordPresenceConfig.safeHost(""));
        assertEquals("none", DiscordPresenceConfig.safeHost(null));
        assertEquals("none", DiscordPresenceConfig.safeHost("   "));
        assertEquals("unparseable", DiscordPresenceConfig.safeHost("not a url"));
    }
}
