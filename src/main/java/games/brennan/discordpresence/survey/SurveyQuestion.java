package games.brennan.discordpresence.survey;

/**
 * One survey question: a prompt answered on a 0–N rating scale, with an optional
 * free-text comment.
 *
 * <p>The prompt is a plain {@link String} (not a translatable component), so the
 * SAME text drives both the in-game survey UI and the Discord embed field. This
 * matches Discord Presence's existing literal-text convention and sidesteps
 * server-side translation-key resolution (a dedicated server need not have the
 * client's language loaded).</p>
 *
 * <p>Public so a bundling mod (e.g. Dungeon Train) can register its own questions
 * via {@link SurveyRegistry#register}.</p>
 *
 * @param id           stable, unique id (namespaced, e.g. {@code "discordpresence:nps"});
 *                     persisted as "answered" per player and echoed back on submit
 * @param prompt       the question text shown to the player and used as the embed description
 * @param scaleMin     lowest selectable rating (typically 0)
 * @param scaleMax     highest selectable rating (typically 10); must be ≥ {@code scaleMin}
 * @param allowComment whether an optional free-text comment box is offered
 */
public record SurveyQuestion(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment) {

    public SurveyQuestion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("survey question id must be non-blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("survey question prompt must be non-blank");
        }
        if (scaleMax < scaleMin) {
            throw new IllegalArgumentException("scaleMax (" + scaleMax + ") < scaleMin (" + scaleMin + ")");
        }
    }

    /** The common 0–10 NPS-style question with an optional comment. */
    public static SurveyQuestion nps(String id, String prompt) {
        return new SurveyQuestion(id, prompt, 0, 10, true);
    }

    /** Clamp a submitted score into this question's {@code [scaleMin, scaleMax]} range. */
    public int clampScore(int score) {
        return Math.max(scaleMin, Math.min(scaleMax, score));
    }
}
