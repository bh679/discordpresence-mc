package games.brennan.discordpresence.survey;

/**
 * Derives the client-side translation keys for a survey question's prompt and multiple-choice
 * option labels from its stable {@link SurveyQuestion#id() id}, so the survey UI can localize them
 * (the client {@code SurveyScreen} resolves the key, falling back to the literal) while the raw
 * literal still drives the server-side Discord embed unchanged.
 *
 * <p>Key form: {@code discordpresence.survey.q.<path>}, where {@code <path>} is the id's path (the
 * part after the {@code namespace:} prefix, with {@code /} flattened to {@code .}); option labels
 * append {@code .option.<index>}. Examples:</p>
 * <ul>
 *   <li>{@code discordpresence:nps} → {@code discordpresence.survey.q.nps}</li>
 *   <li>{@code dungeontrain:bug_report} → {@code discordpresence.survey.q.bug_report}
 *       (+ {@code .option.0} … one per choice)</li>
 * </ul>
 *
 * <p>The derived key is a stable public contract: any bundling mod or datapack can localize its own
 * {@code dp_surveys} question by shipping a lang entry for the derived key — no code required.</p>
 *
 * <p>Caveat: the {@code namespace:} prefix is stripped and {@code /} is flattened to {@code .}, so
 * two ids that differ only in those separators derive the same key. Harmless for the curated
 * built-in NPS + Dungeon Train questions; a pathological third-party id could collide (the last
 * merged lang entry wins).</p>
 */
public final class SurveyKeys {

    /** Shared prefix for every survey question's derived translation key. */
    public static final String PREFIX = "discordpresence.survey.q.";

    private SurveyKeys() {}

    /** The translation key for this question id's prompt (see the class javadoc for the form). */
    public static String promptKey(String id) {
        return PREFIX + path(id);
    }

    /** The translation key for the {@code index}-th multiple-choice option label of this question. */
    public static String optionKey(String id, int index) {
        return promptKey(id) + ".option." + index;
    }

    /** The id's path (after {@code namespace:}), with {@code /} flattened to {@code .}. */
    private static String path(String id) {
        String s = id == null ? "" : id;
        int colon = s.indexOf(':');
        String p = colon >= 0 ? s.substring(colon + 1) : s;
        return p.replace('/', '.');
    }
}
