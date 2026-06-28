package games.brennan.discordpresence.discord;

/**
 * Builds a clickable Discord "jump link" to a posted message and the body line that
 * carries it on the survey-results copy.
 *
 * <p>A survey answer posts as an embed into the player's thread; a copy of that embed
 * is then dropped into a flat survey-results channel (see
 * {@link DiscordService#postSurveyResponse}). The copy carries a link back to the
 * threaded original so the two stay connected. A Discord message URL needs the guild
 * id, the channel id (the thread id, which equals {@link DiscordMessageRef#channelId()})
 * and the message id; the guild id is supplied by config/provider because DP cannot
 * learn it at runtime in relay / webhook-only mode.</p>
 *
 * <p>Pure (no I/O, no NeoForge) so it is trivially unit-testable; it takes primitives
 * rather than the package-private {@link DiscordMessageRef} so the caller passes
 * {@code ref.channelId()} / {@code ref.messageId()} and the test needs no Discord type.</p>
 */
final class SurveyJumpLink {

    private SurveyJumpLink() {}

    /**
     * The {@code https://discord.com/channels/{guild}/{channel}/{message}} jump URL, or
     * {@code null} when any part is blank (so the copy posts without a link rather than a
     * broken one). The {@code channelId} is the thread id for a threaded original, or the
     * parent channel id when the player had no thread — the URL is valid either way.
     */
    static String url(String guildId, String channelId, String messageId) {
        if (isBlank(guildId) || isBlank(channelId) || isBlank(messageId)) {
            return null;
        }
        return "https://discord.com/channels/" + guildId + "/" + channelId + "/" + messageId;
    }

    /**
     * The copy message's content line referencing the original, or {@code null} when
     * {@code jumpUrl} is blank (no link available → post the copy with no content body).
     */
    static String content(String jumpUrl) {
        if (isBlank(jumpUrl)) {
            return null;
        }
        return "🧵 Originally posted in the player's thread → " + jumpUrl;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
