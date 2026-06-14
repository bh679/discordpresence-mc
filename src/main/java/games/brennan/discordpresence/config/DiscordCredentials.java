package games.brennan.discordpresence.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Process-wide holder for an optional {@link DiscordCredentialsProvider} supplied
 * by a mod that bundles Discord Presence (e.g. Dungeon Train). Lets DP stay
 * blank-by-default standalone while a bundling mod can point it at a central feed
 * at runtime — without DP shipping any secret.
 *
 * <p>{@link DiscordPresenceConfig#getWebhookUrl()} /
 * {@link DiscordPresenceConfig#getBotToken()} fold the provider's values in via
 * {@link CredentialResolver}. The provider is read on the Discord I/O daemon
 * threads, so the slot is {@code volatile}; a misbehaving provider (throws or
 * returns {@code null}) degrades to "no value" — logged once, then swallowed — so
 * it never breaks a join handler or the gateway.</p>
 */
public final class DiscordCredentials {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One-shot WARN so a persistently-throwing provider can't spam the log on every read. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private static volatile DiscordCredentialsProvider provider;

    private DiscordCredentials() {}

    /**
     * Register the provider a bundling mod uses to supply credentials. Call once
     * from the bundling mod's constructor; the last registration wins and
     * {@code null} clears it.
     */
    public static void register(DiscordCredentialsProvider newProvider) {
        provider = newProvider;
    }

    /** The provider's webhook URL, or {@code ""} when none is registered / it fails. */
    public static String providerWebhookUrl() {
        return read(DiscordCredentialsProvider::webhookUrl);
    }

    /** The provider's bot token, or {@code ""} when none is registered / it fails. */
    public static String providerBotToken() {
        return read(DiscordCredentialsProvider::botToken);
    }

    /** The provider's relay base URL, or {@code ""} when none is registered / it fails. */
    public static String providerRelayBaseUrl() {
        return read(DiscordCredentialsProvider::relayBaseUrl);
    }

    /** Whether the provider asks DP to suppress its own auto-death-report; false when none / it throws. */
    public static boolean providerSuppressAutoDeathReport() {
        DiscordCredentialsProvider current = provider;
        if (current == null) {
            return false;
        }
        try {
            return current.suppressAutoDeathReport();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The provider's join-message suffix block for this player, or {@code ""} when none is
     * registered / it fails. Reuses {@link #read} for the null- and throwable-safe contract.
     */
    public static String providerJoinSuffix(UUID playerId, String playerName) {
        return read(p -> p.joinMessageSuffix(playerId, playerName));
    }

    /** Snapshot the volatile slot and read one field, mapping null / any throwable to {@code ""}. */
    private static String read(Function<DiscordCredentialsProvider, String> field) {
        DiscordCredentialsProvider current = provider;
        if (current == null) {
            return "";
        }
        try {
            String value = field.apply(current);
            return value == null ? "" : value;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("DiscordCredentialsProvider threw; ignoring its value (this warning is logged once).", t);
            }
            return "";
        }
    }
}
