package games.brennan.discordpresence.survey;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The ordered bank of survey questions, merged from two sources by {@link #questions()}:
 *
 * <ul>
 *   <li><b>Programmatic</b> — the built-in NPS question (registered first in a static
 *       initialiser, so it is always present and asked first) plus anything a mod adds
 *       via {@link #register} from its constructor / setup.</li>
 *   <li><b>Data-driven</b> — questions loaded from {@code data/<ns>/dp_surveys/*.json}
 *       across all datapacks + mods by {@link SurveyQuestionLoader}, replaced on every
 *       server data reload.</li>
 * </ul>
 *
 * <p>Thread-safe: programmatic registrations use a {@link CopyOnWriteArrayList}; the
 * data-driven list is a {@code volatile} immutable snapshot swapped wholesale on reload.</p>
 */
public final class SurveyRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The built-in question id shipped by Discord Presence itself. */
    public static final String NPS_ID = "discordpresence:nps";

    /** Built-in + programmatically-registered questions (NPS first). */
    private static final CopyOnWriteArrayList<SurveyQuestion> QUESTIONS = new CopyOnWriteArrayList<>();

    /** Questions from datapack/mod data files, already sorted by the loader; replaced each reload. */
    private static volatile List<SurveyQuestion> dataDriven = List.of();

    static {
        // The classic Net Promoter Score question — always present, always asked first.
        register(SurveyQuestion.nps(NPS_ID,
                "How likely are you to recommend this game to a friend? (0 = not at all, 10 = extremely)"));
    }

    private SurveyRegistry() {}

    /**
     * Append a programmatic question to the bank. Ignored (with a warning) when a
     * question with the same id is already known — first registration wins, so ids
     * stay stable.
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

    /**
     * Replace the data-driven question set loaded from datapack/mod data files (already
     * sorted by {@link SurveyQuestionLoader}). Called on every server data reload.
     */
    public static void setDataDriven(List<SurveyQuestion> questions) {
        dataDriven = questions == null ? List.of() : List.copyOf(questions);
    }

    /**
     * The questions in ask-order: programmatic first (NPS first), then data-driven
     * (loader-sorted), de-duplicated by id with programmatic precedence. Immutable snapshot.
     */
    public static List<SurveyQuestion> questions() {
        List<SurveyQuestion> merged = new ArrayList<>(QUESTIONS);
        for (SurveyQuestion d : dataDriven) {
            if (findById(merged, d.id()) == null) {
                merged.add(d);
            }
        }
        return List.copyOf(merged);
    }

    /** The question with this id (programmatic or data-driven), or {@code null} if none. */
    public static SurveyQuestion byId(String id) {
        SurveyQuestion q = findById(QUESTIONS, id);
        return q != null ? q : findById(dataDriven, id);
    }

    private static SurveyQuestion findById(List<SurveyQuestion> list, String id) {
        if (id == null) {
            return null;
        }
        for (SurveyQuestion q : list) {
            if (q.id().equals(id)) {
                return q;
            }
        }
        return null;
    }
}
