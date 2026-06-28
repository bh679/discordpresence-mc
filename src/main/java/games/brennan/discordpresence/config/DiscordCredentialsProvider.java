package games.brennan.discordpresence.config;

import java.util.List;
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
     * Whether DP should suppress its own generic auto-disconnect-report (the {@code autoDisconnectReport}
     * config) because the bundling mod posts its own richer logout report via {@code DiscordService}'s
     * {@code postDeathReport(...)}. Default false (DP's auto report fires as configured).
     */
    default boolean suppressAutoDisconnectReport() {
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

    /**
     * Whether DP should restrict game→Discord chat relay to <i>engaged</i> players (those Discord
     * is actively conversing with) plus messages containing a {@link #gameRelayMentions()} trigger.
     * Forces on DP's {@code relayGameToDiscordEngagedOnly} config (off by default standalone). Default
     * false — a bundling mod opts in explicitly; DP relays all chat as configured otherwise.
     */
    default boolean requireEngagementForGameRelay() {
        return false;
    }

    /**
     * Chat-tag triggers that bypass the engagement gate and ping a Discord user, reusing DP's
     * trusted-mention path (the same {@code allowed_mentions.users} mechanism as the join suffix).
     * Each entry is {@code "@token=<@discordUserId>"} (or {@code "@token=discordUserId"}), e.g.
     * {@code "@dev=<@342110421114945537>"}; a bare {@code "@token"} is gate-only (no ping). Matching
     * is case-insensitive substring. Unioned with the admin's {@code gameRelayMentions} config.
     * Default empty (no triggers).
     */
    default List<String> gameRelayMentions() {
        return List.of();
    }

    /**
     * Short bullet lines for the title-screen consent popup ("what the internet is for"), supplied
     * by the bundling mod. Rendered verbatim as a small bulleted list on
     * {@code NetworkConsentScreen}. Empty = DP's generic fallback wording (standalone DP). Read on
     * the physical client at title-screen time. Default empty.
     */
    default List<String> networkConsentFeatures() {
        return List.of();
    }

    /**
     * Short bullet lines for things the connection will <i>not</i> do, supplied by the bundling mod.
     * Rendered below {@link #networkConsentFeatures()} on {@code NetworkConsentScreen} with a red ✗
     * marker (instead of the blue dot; the text itself stays the normal bullet grey) so the "won't
     * do" reassurances read as the opposite of the positive bullets. Empty = no negative section
     * shown. Read on the physical client at title-screen time. Default empty.
     */
    default List<String> networkConsentNonFeatures() {
        return List.of();
    }

    /**
     * Discord user ids whose online presence DP should track for the "last seen online" query seam
     * ({@code DiscordService.lastSeenOnline} / {@code isDiscordUserOnline}). Unioned with the admin's
     * {@code presenceTrackUserIds} config. A non-empty result makes DP request the privileged
     * {@code GUILD_PRESENCES} intent (which must be enabled in the Developer Portal) and — in
     * direct-bot mode — open the gateway to receive presence events even when two-way chat is off.
     * Default empty (no tracking). Note: in relay-mode DP holds no local gateway, so presence must be
     * served by the relay (see the mod's relay notes) — this list alone won't light it up there.
     */
    default List<String> presenceTrackUserIds() {
        return List.of();
    }

    /**
     * Optional plain-text game-state line for an advancement announcement — e.g. a bundling mod's
     * context (Dungeon Train adds the earning player's carriage # and difficulty level). DP renders it
     * as a separate bot message chained immediately after the embed, so it appears on its own line
     * <i>below</i> the embed box. Called on the server thread, so an implementation may read live game
     * state. {@code ""}/{@code null} = nothing added; standalone DP leaves advancement messages
     * unchanged. The arguments identify the earning player and the advancement id so a provider can
     * vary the line per player / per advancement.
     */
    default String advancementMessageSuffix(UUID playerId, String advancementId) {
        return "";
    }

    /**
     * Called once a player has answered <i>all</i> their outstanding death-screen survey questions
     * (i.e. none remain unanswered). Invoked on the server thread right after the final answer is
     * persisted, so an implementation may read or modify live game state — e.g. a bundling mod
     * awarding an advancement for completing the feedback survey. Skipped questions stay
     * outstanding, so this fires only when the player has genuinely answered everything; it may
     * fire again on a later death once new questions are added and then answered. Default does
     * nothing. Like the other seams it MUST NOT throw (a thrown exception is swallowed).
     */
    default void onSurveyCompleted(UUID playerId, String playerName) {
    }

    /**
     * Discord user ids to @-mention (ping) on <i>every</i> submitted survey answer — DP adds them to
     * the survey-response message's {@code content} with a trusted {@code allowed_mentions.users}
     * allow-list, so the listed users are actually notified each time a player posts feedback. A
     * bundling mod returns its maintainer's id here to be alerted to incoming feedback; standalone DP
     * returns the default empty list (no ping, message unchanged). Read on the server thread per
     * answer. Like the other seams it MUST NOT throw (a thrown exception is swallowed).
     */
    default List<String> surveyPingUserIds() {
        return List.of();
    }

    /**
     * Whether DP should also post a COPY of each survey answer into a flat survey-results channel
     * (in addition to the per-player thread), so all feedback is browsable in one place. Default
     * false (standalone DP posts only the threaded answer, as before). A bundling mod opts in here.
     * The copy's destination is {@link #surveyResultsWebhookUrl()}; when that is blank the copy
     * falls back to the default webhook (so a dev build's copy still appears in its dev channel).
     */
    default boolean surveyResultsCopyEnabled() {
        return false;
    }

    /**
     * Destination the survey-results copy posts to — its own webhook URL or relay {@code <base>/hook}
     * for a dedicated channel. Blank/{@code null} = the default webhook (same feed as the thread,
     * posted top-level). Only consulted when {@link #surveyResultsCopyEnabled()} is true. Default
     * blank. A dev-env webhook override still wins for local testing.
     */
    default String surveyResultsWebhookUrl() {
        return "";
    }

    /**
     * Discord guild (server) id used to build the copy's jump-link back to the threaded original
     * ({@code https://discord.com/channels/{guild}/{thread}/{message}}). Required for a clickable
     * link because DP cannot learn the guild id at runtime in relay / webhook-only mode. Not a
     * secret. Blank/{@code null} = the copy posts without a link. Default blank.
     */
    default String surveyResultsLinkGuildId() {
        return "";
    }
}
