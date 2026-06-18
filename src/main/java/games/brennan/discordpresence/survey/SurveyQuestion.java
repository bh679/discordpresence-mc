package games.brennan.discordpresence.survey;

/**
 * One survey question. Usually a prompt answered on a 0–N rating scale with an optional
 * free-text comment; alternatively a comment-only "text" question (no rating scale), where the
 * free-text box is the sole answer — see {@link #text} and {@link #hasScale()}.
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
 * @param scaleMax     highest selectable rating (typically 10); a value {@code < scaleMin}
 *                     means "no rating scale" — a comment-only text question (then
 *                     {@code allowComment} must be true)
 * @param allowComment whether a free-text comment box is offered (optional for a scale
 *                     question; the required, sole answer for a text question)
 */
public record SurveyQuestion(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment) {

    public SurveyQuestion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("survey question id must be non-blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("survey question prompt must be non-blank");
        }
        // scaleMax < scaleMin encodes a "no rating scale" text question — valid only when a
        // comment box is offered, else the question would have no answer input at all.
        if (scaleMax < scaleMin && !allowComment) {
            throw new IllegalArgumentException("a question with no rating scale (scaleMax " + scaleMax
                    + " < scaleMin " + scaleMin + ") must allow a comment");
        }
    }

    /** The common 0–10 NPS-style question with an optional comment. */
    public static SurveyQuestion nps(String id, String prompt) {
        return new SurveyQuestion(id, prompt, 0, 10, true);
    }

    /**
     * A comment-only "text" question: no rating scale — the free-text box is the sole, required
     * answer. Encoded with the {@code scaleMax < scaleMin} sentinel so it needs no extra fields
     * on the wire (client and server ship together in the bundling mod's jar).
     */
    public static SurveyQuestion text(String id, String prompt) {
        return new SurveyQuestion(id, prompt, 0, -1, true);
    }

    /** Whether this question has a rating scale; {@code false} for a comment-only text question. */
    public boolean hasScale() {
        return scaleMax >= scaleMin;
    }

    /** Clamp a submitted score into this question's {@code [scaleMin, scaleMax]} range. */
    public int clampScore(int score) {
        return Math.max(scaleMin, Math.min(scaleMax, score));
    }
}
