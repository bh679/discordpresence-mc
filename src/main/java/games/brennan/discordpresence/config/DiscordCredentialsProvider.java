package games.brennan.discordpresence.config;

import java.util.UUID;

/**
 * Supplies Discord credentials at runtime so a mod that <b>bundles</b> Discord
 * Presence (e.g. Dungeon Train via jarJar) can light up a central feed
 * <i>without DP shipping any secret</i> in its own repo or jar.
 *
 * <p>Standalone DP registers no provider, so its credentials stay
 * blank-by-default — the mod is off until a webhook URL is set in
 * {@code discordpresence-server.toml}. A bundling mod calls
 * {@link DiscordCredentials#register(DiscordCredentialsProvider)} from its mod
 * constructor to supply values.</p>
 *
 * <p>For the recommended relay setup the secret never ships in the jar: the
 * bundling mod returns the URL of a relay it hosts from {@link #webhookUrl()}
 * (DP posts to it exactly like a Discord webhook) and leaves {@link #botToken()}
 * blank — the real webhook and token live server-side on the relay.</p>
 *
 * <p>Implementations are read off the Discord I/O threads and MUST NOT throw; a
 * thrown exception or a {@code null} return is treated as "no value"
 * (see {@link DiscordCredentials}).</p>
 */
@FunctionalInterface
public interface DiscordCredentialsProvider {

    /** Webhook URL (or relay endpoint) DP should post through, or {@code ""}/{@code null} for none. */
    String webhookUrl();

    /**
     * Bot token DP should use for reactions / threads / gateway, or {@code ""} for none.
     * Defaults to blank so a provider can supply a webhook (or relay) only.
     */
    default String botToken() {
        return "";
    }

    /**
     * Optional relay base URL (e.g. {@code https://host/api/dp-relay/<cap>}). When non-blank, DP
     * routes ALL Discord I/O through the relay — webhook posts to {@code <base>/hook}, bot REST to
     * {@code <base>/bot} — and sends NO token (the relay injects it server-side). Lets a bundling
     * mod point DP at a central feed while holding no Discord secret. Blank = talk to Discord
     * directly using {@link #webhookUrl()} + {@link #botToken()}.
     */
    default String relayBaseUrl() {
        return "";
    }

    /**
     * Whether DP should suppress its own generic auto-death-report (the {@code autoDeathReport}
     * config) because the bundling mod posts its own richer death report via {@code DiscordService}'s
     * {@code postDeathReport(...)}. Default false (DP's auto report fires as configured).
     */
    default boolean suppressAutoDeathReport() {
        return false;
    }

    /**
     * Optional extra block appended below each join / first-join message — e.g. a bundling mod's
     * {@code "DungeonTrain 0.298.0"} line, optionally plus a relay ping-marker for a specific
     * player. Called on the server thread during join handling. {@code ""}/{@code null} = nothing
     * appended; standalone DP leaves its join messages unchanged. The arguments identify the
     * joining player so a provider can vary the block per player (e.g. a one-time milestone tag).
     */
    default String joinMessageSuffix(UUID playerId, String playerName) {
        return "";
    }
}
