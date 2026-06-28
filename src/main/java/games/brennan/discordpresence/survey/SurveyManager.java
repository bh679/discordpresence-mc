package games.brennan.discordpresence.survey;

import games.brennan.discordpresence.config.DiscordCredentials;
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
 * Server-side driver for the feedback survey: offers the full question bank both on every
 * death and on demand (the {@code /feedback} command), and posts each submitted answer to
 * Discord.
 *
 * <p>The client walks the sent questions one screen at a time, submitting each (every submit
 * posts to Discord). The survey is offered fresh on every death and via {@code /feedback}, so
 * a player can give feedback as often as they like. Answered questions are still tracked per
 * player ({@link SurveyStore}) so {@link #record} can fire the survey-completed seam once a
 * player has answered everything.</p>
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

    /** On death, send the player every registered question (an empty list hides the button). */
    public void onPlayerDeath(ServerPlayer player) {
        List<SurveyQuestionPayload.Entry> entries = active(player) ? allEntries() : List.of();
        DPNetwork.sendTo(player, new SurveyQuestionPayload(entries));
    }

    /**
     * Open the survey on demand for this player — the {@code /feedback} command. Always offers
     * the full question bank (so players can give or update feedback at any time). Pushes a
     * payload that opens the screen immediately; messages the player and opens nothing only
     * when the survey can't run (disabled / no webhook / no consent).
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
        // No "already answered" guard: the survey re-opens on every death and via /feedback, and
        // every submit posts.
        String cleaned = sanitizeComment(comment);
        store.markAnswered(uuid, questionId);

        String name = player.getGameProfile().getName();
        List<DeathField> fields = new ArrayList<>();
        if (question.isChoice()) {
            // Multiple-choice: the score is the chosen 0-based index; post the option label.
            int idx = Math.max(0, Math.min(question.options().size() - 1, score));
            fields.add(new DeathField("Answer", question.options().get(idx)));
        } else if (question.hasScale()) {
            fields.add(new DeathField("Rating", question.clampScore(score) + " / " + question.scaleMax()));
        }
        if (!cleaned.isBlank()) {
            // For a text (no-scale) question the comment IS the answer; for a choice question it is
            // extra detail; for a scale question it is the optional reason for the score. Label the
            // Discord field accordingly.
            String label = question.isChoice() ? "Details" : (question.hasScale() ? "Comment" : "Answer");
            fields.add(new DeathField(label, cleaned));
        }
        // The genuine survey-answer path: ping the seam's user ids AND post the survey-results copy.
        // Other reusers of the survey embed style (e.g. DT's Free Play / difficulty notices) call
        // postSurveyResponse instead — they never ping and never produce a results-channel copy.
        DiscordService.get().postSurveyAnswer(player, "📋 Feedback — " + name, question.prompt(), fields,
                DiscordCredentials.providerSurveyPingUserIds());

        // If the player has now answered every question, the survey is complete — notify the
        // bundling mod so it can react (e.g. award an advancement). Fires on each completion;
        // the consumer is responsible for any once-only handling.
        if (unanswered(SurveyRegistry.questions(), id -> store.hasAnswered(uuid, id)).isEmpty()) {
            DiscordCredentials.providerOnSurveyCompleted(uuid, name);
        }
    }

    /** The full question bank as client payload entries, in ask-order. */
    private static List<SurveyQuestionPayload.Entry> allEntries() {
        return toEntries(SurveyRegistry.questions());
    }

    /** Map survey questions to their client-facing payload entries, preserving order. */
    private static List<SurveyQuestionPayload.Entry> toEntries(List<SurveyQuestion> questions) {
        List<SurveyQuestionPayload.Entry> out = new ArrayList<>(questions.size());
        for (SurveyQuestion q : questions) {
            out.add(new SurveyQuestionPayload.Entry(q.id(), q.prompt(), q.scaleMin(), q.scaleMax(),
                    q.allowComment(), q.options()));
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
