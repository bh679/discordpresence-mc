package games.brennan.discordpresence.client;

import games.brennan.discordpresence.network.SurveyQuestionPayload;

import java.util.List;

/**
 * Client-only holder for the survey questions the server wants this player to answer on
 * the death screen — their unanswered set, in ask-order. Set by the
 * {@link SurveyQuestionPayload} handler; read by the death-screen Feedback button
 * (visibility) and the survey screen (which questions to walk).
 */
public final class SurveyClientState {

    private static volatile List<SurveyQuestionPayload.Entry> questions = List.of();

    private SurveyClientState() {}

    public static void set(List<SurveyQuestionPayload.Entry> next) {
        questions = next == null ? List.of() : List.copyOf(next);
    }

    /** The questions to walk this session (possibly empty). */
    public static List<SurveyQuestionPayload.Entry> questions() {
        return questions;
    }

    /** Whether there is at least one question to offer right now. */
    public static boolean hasQuestions() {
        return !questions.isEmpty();
    }

    /** Optimistically clear after finishing the walk, so the button hides immediately. */
    public static void clear() {
        questions = List.of();
    }
}
