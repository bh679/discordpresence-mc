package games.brennan.discordpresence.client;

import games.brennan.discordpresence.network.SurveyQuestionPayload;

/**
 * Client-only holder for the survey question the server currently wants this player to
 * answer on the death screen (or none). Set by the {@link SurveyQuestionPayload}
 * handler; read by the death-screen Feedback button (visibility) and the survey screen
 * (which question to render).
 */
public final class SurveyClientState {

    private static volatile SurveyQuestionPayload current = SurveyQuestionPayload.NONE;

    private SurveyClientState() {}

    public static void set(SurveyQuestionPayload payload) {
        current = payload == null ? SurveyQuestionPayload.NONE : payload;
    }

    /** The current question to offer, or {@code null} when there is none. */
    public static SurveyQuestionPayload current() {
        SurveyQuestionPayload c = current;
        return (c != null && c.present()) ? c : null;
    }

    /** Whether there is a question to offer right now. */
    public static boolean hasQuestion() {
        return current() != null;
    }

    /** Optimistically clear after submitting, so the button hides without waiting for the server. */
    public static void clear() {
        current = SurveyQuestionPayload.NONE;
    }
}
