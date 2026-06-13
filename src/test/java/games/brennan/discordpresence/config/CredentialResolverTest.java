package games.brennan.discordpresence.config;

import org.junit.jupiter.api.Test;

import static games.brennan.discordpresence.config.CredentialResolver.Policy.CONFIG_WINS;
import static games.brennan.discordpresence.config.CredentialResolver.Policy.PROVIDER_WINS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure unit tests for the credential precedence resolver — no NeoForge runtime. */
class CredentialResolverTest {

    @Test
    void providerOnly_configBlank_usesProvider() {
        assertEquals("https://relay/hook", CredentialResolver.resolve("", "https://relay/hook", CONFIG_WINS));
    }

    @Test
    void bothSet_configWins_underConfigPolicy() {
        assertEquals("https://admin/hook",
                CredentialResolver.resolve("https://admin/hook", "https://relay/hook", CONFIG_WINS));
    }

    @Test
    void bothBlank_returnsBlank_eitherPolicy() {
        assertEquals("", CredentialResolver.resolve("", "", CONFIG_WINS));
        assertEquals("", CredentialResolver.resolve("", "", PROVIDER_WINS));
    }

    @Test
    void bothSet_providerWins_underProviderPolicy() {
        assertEquals("https://relay/hook",
                CredentialResolver.resolve("https://admin/hook", "https://relay/hook", PROVIDER_WINS));
    }

    @Test
    void providerPolicy_fallsBackToConfig_whenProviderBlank() {
        assertEquals("https://admin/hook", CredentialResolver.resolve("https://admin/hook", "", PROVIDER_WINS));
    }

    @Test
    void nullInputs_normaliseToBlank() {
        assertEquals("", CredentialResolver.resolve(null, null, CONFIG_WINS));
        assertEquals("", CredentialResolver.resolve(null, null, PROVIDER_WINS));
    }

    @Test
    void nullConfig_usesProvider() {
        assertEquals("p", CredentialResolver.resolve(null, "p", CONFIG_WINS));
    }

    @Test
    void nullProvider_usesConfig() {
        assertEquals("c", CredentialResolver.resolve("c", null, PROVIDER_WINS));
    }

    @Test
    void whitespaceConfigTreatedAsBlank_underConfigPolicy() {
        assertEquals("p", CredentialResolver.resolve("   ", "p", CONFIG_WINS));
    }

    @Test
    void whitespaceProviderTreatedAsBlank_underProviderPolicy() {
        assertEquals("c", CredentialResolver.resolve("c", "   ", PROVIDER_WINS));
    }
}
