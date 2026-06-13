package games.brennan.discordpresence.config;

/**
 * Pure resolution of an effective Discord credential from the admin's config
 * value and a bundling mod's {@link DiscordCredentialsProvider} value. No
 * NeoForge and no static config state, so it is fully unit-testable.
 *
 * <p>A blank (empty or whitespace-only) value counts as "absent", matching the
 * mod's enable gate ({@code !webhookUrl.isBlank()}). The result is never null and
 * neither input is mutated.</p>
 */
public final class CredentialResolver {

    /** Which source wins when both the config and the provider supply a value. */
    public enum Policy {
        /** Admin config wins when non-blank; otherwise the provider; otherwise blank. */
        CONFIG_WINS,
        /** Provider wins when non-blank; otherwise the admin config; otherwise blank. */
        PROVIDER_WINS
    }

    private CredentialResolver() {}

    /**
     * The effective credential for the given precedence policy.
     *
     * @param configValue   the admin's config value (may be null / blank)
     * @param providerValue a bundling mod's value (may be null / blank)
     * @param policy        precedence when both are non-blank
     * @return the resolved value, never null (blank when neither source has one)
     */
    public static String resolve(String configValue, String providerValue, Policy policy) {
        String config = configValue == null ? "" : configValue;
        String provider = providerValue == null ? "" : providerValue;
        if (policy == Policy.PROVIDER_WINS) {
            return provider.isBlank() ? config : provider;
        }
        return config.isBlank() ? provider : config;
    }
}
