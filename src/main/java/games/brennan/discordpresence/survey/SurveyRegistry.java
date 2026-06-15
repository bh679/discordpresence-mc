package games.brennan.discordpresence.survey;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The ordered bank of survey questions. Discord Presence registers a built-in NPS
 * question first (in a static initialiser, so it is always present and always asked
 * first); bundling mods append their own via {@link #register} from their mod
 * constructor / common-setup.
 *
 * <p>Thread-safe (a {@link CopyOnWriteArrayList} plus id de-duplication) so
 * registration from several mods' setup is safe. Registration is expected only at
 * startup; {@link #questions()} is read per player death.</p>
 */
public final class SurveyRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The built-in question id shipped by Discord Presence itself. */
    public static final String NPS_ID = "discordpresence:nps";

    private static final CopyOnWriteArrayList<SurveyQuestion> QUESTIONS = new CopyOnWriteArrayList<>();

    static {
        // The classic Net Promoter Score question — always present, always asked first.
        register(SurveyQuestion.nps(NPS_ID,
                "How likely are you to recommend this game to a friend? (0 = not at all, 10 = extremely)"));
    }

    private SurveyRegistry() {}

    /**
     * Append a question to the bank. Ignored (with a warning) when a question with
     * the same id is already registered — first registration wins, so ids stay stable.
     */
    public static void register(SurveyQuestion question) {
        if (question == null) {
            return;
        }
        if (byId(question.id()) != null) {
            LOGGER.warn("Discord Presence: survey question '{}' already registered — ignoring duplicate.",
                    question.id());
            return;
        }
        QUESTIONS.add(question);
        LOGGER.info("Discord Presence: registered survey question '{}'.", question.id());
    }

    /** The questions in ask-order (NPS first, then registration order). Immutable snapshot. */
    public static List<SurveyQuestion> questions() {
        return List.copyOf(QUESTIONS);
    }

    /** The registered question with this id, or {@code null} if none. */
    public static SurveyQuestion byId(String id) {
        if (id == null) {
            return null;
        }
        for (SurveyQuestion q : QUESTIONS) {
            if (q.id().equals(id)) {
                return q;
            }
        }
        return null;
    }
}
