package games.brennan.discordpresence.survey;

import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-side driver for the death-screen feedback survey: tracks which questions a
 * player has answered, sends their remaining (unanswered) questions on each death, and
 * posts a submitted answer to Discord.
 *
 * <p>The client walks the sent questions one screen at a time, submitting each (every
 * submit posts to Discord), so the player works through all their unanswered questions
 * in one sitting. An answered question is never re-sent, and the death-screen button is
 * hidden once a player has answered everything.</p>
 */
public final class SurveyManager {

    private static final SurveyManager INSTANCE = new SurveyManager();
    private static final String STORE_FILE = "discordpresence-surveys.json";
    private static final int MAX_COMMENT = 300;

    public static SurveyManager get() {
        return INSTANCE;
    }

    private final SurveyStore store = new SurveyStore();

    private SurveyManager() {}

    /** Load the persisted per-player answered-questions map on server start. */
    public void load() {
        store.load(FMLPaths.CONFIGDIR.get().resolve(STORE_FILE));
    }

    /** On death, send the player their unanswered questions (an empty list hides the button). */
    public void onPlayerDeath(ServerPlayer player) {
        List<SurveyQuestionPayload.Entry> entries = active(player) ? unansweredEntries(player.getUUID()) : List.of();
        DPNetwork.sendTo(player, new SurveyQuestionPayload(entries));
    }

    /** Handle a submitted answer: validate, persist, and post it to Discord. */
    public void record(ServerPlayer player, String questionId, int score, String comment) {
        SurveyQuestion question = SurveyRegistry.byId(questionId);
        if (question == null) {
            return; // unknown id — ignore (stale client / tampering)
        }
        UUID uuid = player.getUUID();
        if (store.hasAnswered(uuid, questionId)) {
            return; // already answered — idempotent, no double post
        }
        if (!active(player)) {
            return; // survey disabled / no webhook / no consent
        }
        int clamped = question.clampScore(score);
        String cleaned = sanitizeComment(comment);
        store.markAnswered(uuid, questionId);

        String name = player.getGameProfile().getName();
        List<DeathField> fields = new ArrayList<>();
        fields.add(new DeathField("Rating", clamped + " / " + question.scaleMax()));
        if (!cleaned.isBlank()) {
            fields.add(new DeathField("Comment", cleaned));
        }
        DiscordService.get().postSurveyResponse(player, "📋 Feedback — " + name, question.prompt(), fields);

        // If that was the player's last outstanding question, the survey is complete — notify the
        // bundling mod so it can react (e.g. award an advancement). Skipped questions stay
        // outstanding, so this fires only once the player has answered everything.
        if (unanswered(SurveyRegistry.questions(), id -> store.hasAnswered(uuid, id)).isEmpty()) {
            DiscordCredentials.providerOnSurveyCompleted(uuid, name);
        }
    }

    /** The player's unanswered questions as client payload entries, in ask-order. */
    private List<SurveyQuestionPayload.Entry> unansweredEntries(UUID uuid) {
        List<SurveyQuestionPayload.Entry> out = new ArrayList<>();
        for (SurveyQuestion q : unanswered(SurveyRegistry.questions(), id -> store.hasAnswered(uuid, id))) {
            out.add(new SurveyQuestionPayload.Entry(q.id(), q.prompt(), q.scaleMin(), q.scaleMax(), q.allowComment()));
        }
        return out;
    }

    /** Pure selection (testable): the bank questions for which {@code answered} is false, in order. */
    static List<SurveyQuestion> unanswered(List<SurveyQuestion> bank, Predicate<String> answered) {
        List<SurveyQuestion> out = new ArrayList<>();
        for (SurveyQuestion q : bank) {
            if (!answered.test(q.id())) {
                out.add(q);
            }
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
