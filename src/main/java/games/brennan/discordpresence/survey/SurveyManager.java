package games.brennan.discordpresence.survey;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyOpenPayload;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-side driver for the feedback survey: tracks which questions a player has answered,
 * offers questions on death and on demand, and posts a submitted answer to Discord.
 *
 * <p>The client walks the sent questions one screen at a time, submitting each (every submit
 * posts to Discord). The death-screen path is opportunistic — it sends only a player's
 * unanswered questions and the death-screen button hides once they have answered everything.
 * The {@code /feedback} command ({@link #openSurveyFor}) is always available: it sends the
 * full question bank and lets players give or update feedback at any time.</p>
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

    /**
     * Open the survey on demand for this player — the {@code /feedback} command. Always offers
     * the full question bank (so players can give or update feedback at any time), unlike the
     * death path which sends only unanswered questions. Pushes a payload that opens the screen
     * immediately; messages the player and opens nothing only when the survey can't run
     * (disabled / no webhook / no consent).
     */
    public void openSurveyFor(ServerPlayer player) {
        if (!active(player)) {
            player.sendSystemMessage(Component.literal("Feedback isn't available right now."));
            return;
        }
        List<SurveyQuestionPayload.Entry> entries = allEntries();
        if (entries.isEmpty()) {
            return; // no questions configured — nothing to open
        }
        DPNetwork.sendTo(player, new SurveyOpenPayload(entries));
    }

    /** Handle a submitted answer: validate, persist, and post it to Discord. */
    public void record(ServerPlayer player, String questionId, int score, String comment) {
        SurveyQuestion question = SurveyRegistry.byId(questionId);
        if (question == null) {
            return; // unknown id — ignore (stale client / tampering)
        }
        UUID uuid = player.getUUID();
        if (!active(player)) {
            return; // survey disabled / no webhook / no consent
        }
        // No "already answered" guard: /feedback intentionally allows re-submitting. The
        // death path only ever sends unanswered questions, so it never re-submits anyway.
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
    }

    /** The player's unanswered questions as client payload entries, in ask-order (death path). */
    private List<SurveyQuestionPayload.Entry> unansweredEntries(UUID uuid) {
        return toEntries(unanswered(SurveyRegistry.questions(), id -> store.hasAnswered(uuid, id)));
    }

    /** The full question bank as client payload entries, in ask-order (the /feedback command). */
    private static List<SurveyQuestionPayload.Entry> allEntries() {
        return toEntries(SurveyRegistry.questions());
    }

    /** Map survey questions to their client-facing payload entries, preserving order. */
    private static List<SurveyQuestionPayload.Entry> toEntries(List<SurveyQuestion> questions) {
        List<SurveyQuestionPayload.Entry> out = new ArrayList<>(questions.size());
        for (SurveyQuestion q : questions) {
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
