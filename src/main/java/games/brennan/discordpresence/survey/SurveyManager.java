package games.brennan.discordpresence.survey;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side driver for the death-screen feedback survey: on every death it sends the
 * player the full set of registered questions, and posts each submitted answer to Discord.
 *
 * <p>The client walks the sent questions one screen at a time, submitting each (every
 * submit posts to Discord). The survey is offered fresh on every death — there is no
 * per-player "ask once" tracking — so a player can give feedback as often as they die.</p>
 */
public final class SurveyManager {

    private static final SurveyManager INSTANCE = new SurveyManager();
    private static final int MAX_COMMENT = 300;

    public static SurveyManager get() {
        return INSTANCE;
    }

    private SurveyManager() {}

    /** On death, send the player every registered question (an empty list hides the button). */
    public void onPlayerDeath(ServerPlayer player) {
        List<SurveyQuestionPayload.Entry> entries = active(player) ? allEntries() : List.of();
        DPNetwork.sendTo(player, new SurveyQuestionPayload(entries));
    }

    /** Handle a submitted answer: validate and post it to Discord. */
    public void record(ServerPlayer player, String questionId, int score, String comment) {
        SurveyQuestion question = SurveyRegistry.byId(questionId);
        if (question == null) {
            return; // unknown id — ignore (stale client / tampering)
        }
        if (!active(player)) {
            return; // survey disabled / no webhook / no consent
        }
        int clamped = question.clampScore(score);
        String cleaned = sanitizeComment(comment);

        String name = player.getGameProfile().getName();
        List<DeathField> fields = new ArrayList<>();
        fields.add(new DeathField("Rating", clamped + " / " + question.scaleMax()));
        if (!cleaned.isBlank()) {
            fields.add(new DeathField("Comment", cleaned));
        }
        DiscordService.get().postSurveyResponse(player, "📋 Feedback — " + name, question.prompt(), fields);
    }

    /** Every registered question as client payload entries, in ask-order. */
    private static List<SurveyQuestionPayload.Entry> allEntries() {
        List<SurveyQuestionPayload.Entry> out = new ArrayList<>();
        for (SurveyQuestion q : SurveyRegistry.questions()) {
            out.add(new SurveyQuestionPayload.Entry(q.id(), q.prompt(), q.scaleMin(), q.scaleMax(), q.allowComment()));
        }
        return out;
    }

    /**
     * Whether the survey may run for this player right now: enabled in config, a Discord
     * webhook configured (so responses can post), and network use allowed (always on a
     * dedicated server; requires the one-time consent in singleplayer).
     */
    private static boolean active(ServerPlayer player) {
        if (!DiscordPresenceConfig.isSurveyEnabled()) {
            return false;
        }
        if (DiscordPresenceConfig.getWebhookUrl().isBlank()) {
            return false;
        }
        MinecraftServer server = player.server;
        return server != null && (server.isDedicatedServer() || DiscordPresenceClientConfig.isGranted());
    }

    /** Single-line, length-capped comment safe for a Discord embed field. */
    static String sanitizeComment(String comment) {
        if (comment == null) {
            return "";
        }
        String s = comment.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > MAX_COMMENT ? s.substring(0, MAX_COMMENT) + "…" : s;
    }
}
