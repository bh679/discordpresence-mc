package games.brennan.discordpresence.survey;

import java.util.List;

/**
 * One survey question. Usually a prompt answered on a 0–N rating scale with an optional
 * free-text comment; alternatively a comment-only "text" question (no rating scale), where the
 * free-text box is the sole answer — see {@link #text} and {@link #hasScale()}; or a
 * multiple-choice question whose rating tiles are labelled by {@link #options()} — see
 * {@link #choice} and {@link #isChoice()}.
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
 * @param options      multiple-choice labels; non-empty makes this a choice question (the
 *                     submitted score is the chosen 0-based index into this list). Empty for
 *                     plain scale/text questions.
 */
public record SurveyQuestion(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment,
                             List<String> options) {

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
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** Back-compat constructor: a scale/text question with no multiple-choice options. */
    public SurveyQuestion(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment) {
        this(id, prompt, scaleMin, scaleMax, allowComment, List.of());
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

    /**
     * A multiple-choice question: the player picks exactly one of {@code options}; the chosen
     * 0-based index travels in the submit's {@code score}. Modelled as a rating scale of
     * {@code [0, options.size()-1]} so it reuses the existing score wire + selection path; the UI
     * renders the option labels instead of the numbers, and Discord posts the chosen label.
     *
     * @param allowComment whether to also offer a free-text comment box alongside the choices
     */
    public static SurveyQuestion choice(String id, String prompt, List<String> options, boolean allowComment) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("a choice question must have at least one option");
        }
        return new SurveyQuestion(id, prompt, 0, options.size() - 1, allowComment, List.copyOf(options));
    }

    /** Whether this question has a rating scale; {@code false} for a comment-only text question. */
    public boolean hasScale() {
        return scaleMax >= scaleMin;
    }

    /** Whether this is a multiple-choice question (its rating tiles are labelled by {@link #options()}). */
    public boolean isChoice() {
        return !options.isEmpty();
    }

    /** Clamp a submitted score into this question's {@code [scaleMin, scaleMax]} range. */
    public int clampScore(int score) {
        return Math.max(scaleMin, Math.min(scaleMax, score));
    }
}
