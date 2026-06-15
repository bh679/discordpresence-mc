package games.brennan.discordpresence.survey;

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
 * player has answered, offers the next unanswered one on each death, and posts a
 * submitted answer to Discord.
 *
 * <p>Exactly one question is surfaced per death — the offer is pushed on death and
 * cleared on submit (see {@link #onPlayerDeath} / {@link #record}).</p>
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

    /** On death, offer the player their next unanswered question (or clear the button). */
    public void onPlayerDeath(ServerPlayer player) {
        SurveyQuestion next = active(player) ? nextFor(player.getUUID()) : null;
        DPNetwork.sendTo(player, next == null
                ? SurveyQuestionPayload.NONE
                : new SurveyQuestionPayload(true, next.id(), next.prompt(),
                        next.scaleMin(), next.scaleMax(), next.allowComment()));
    }

    /** Handle a submitted answer: validate, persist, post to Discord, then clear the button. */
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

        // Clear the death-screen button now that this question is answered (one per death).
        DPNetwork.sendTo(player, SurveyQuestionPayload.NONE);
    }

    /** The first registered question this player has not answered, or {@code null} if none remain. */
    private SurveyQuestion nextFor(UUID uuid) {
        return firstUnanswered(SurveyRegistry.questions(), id -> store.hasAnswered(uuid, id));
    }

    /** Pure selection logic (testable): the first bank question for which {@code answered} is false. */
    static SurveyQuestion firstUnanswered(List<SurveyQuestion> bank, Predicate<String> answered) {
        for (SurveyQuestion q : bank) {
            if (!answered.test(q.id())) {
                return q;
            }
        }
        return null;
    }

    /**
     * Whether the survey may run for this player right now: enabled in config, a
     * Discord webhook configured (so responses can post), and network use allowed
     * (always on a dedicated server; requires the one-time consent in singleplayer).
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
