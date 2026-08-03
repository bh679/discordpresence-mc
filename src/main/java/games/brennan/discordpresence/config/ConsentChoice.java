package games.brennan.discordpresence.config;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A bundling mod's customisation of the title-screen network-consent card (see
 * {@code client.NetworkConsentScreen}), supplied through
 * {@link DiscordCredentialsProvider#networkConsentChoice()}.
 *
 * <p>At minimum it adds one extra multiple-choice question to the card. That card is the one screen
 * every player answers exactly once, on their first launch, which makes it the natural home for a
 * second one-time question — asking it there costs no additional interruption, where a second card
 * would. Dungeon Train uses it for its Adult / Kid content mode. DP itself has no opinion on what
 * the question is: it renders the label and options it is handed and reports back an index.</p>
 *
 * <p>Two optional extras go with it. {@link #optionBullets} replaces the card's own bulleted list
 * with one list PER OPTION, so selecting a different option can change the lines, their markers and
 * their hover text — the point being that the card then shows what the choice actually does.
 * {@link #confirmLabel} collapses the card's two buttons into a single confirming one.</p>
 *
 * <p>The choice is <b>not</b> a second consent gate. It is recorded on every exit path — any button,
 * and Esc — so the bundling mod always learns the answer, and DP never stores it: {@code onChosen}
 * is the only place it goes, and the provider owns the persistence.</p>
 *
 * @param label         the question, shown above the option control (already localized by the provider).
 * @param options       the choices, in display order. Fewer than two options is meaningless and the
 *                      screen skips the control entirely rather than rendering a dead widget.
 * @param defaultIndex  which option starts selected; out-of-range values clamp to 0.
 * @param onChosen      called with the selected index as the card closes, on the client thread. Called
 *                      exactly once per showing, including when the player presses Esc (which answers
 *                      the consent question as DENIED but must still answer this one). May be null.
 * @param optionBullets one bullet list per entry of {@code options}, parallel by index. Empty (the
 *                      default) leaves the card drawing its own feature / non-feature blocks, so
 *                      standalone DP and other bundlers are unaffected. A list shorter than
 *                      {@code options} falls back to the card's own blocks for the missing entries.
 * @param confirmLabel  when non-null, the card shows ONE button with this label, which grants consent;
 *                      Esc remains the only way to decline. Null (the default) keeps the usual
 *                      Enable / Not now pair. A bundler choosing this is choosing to make refusal
 *                      invisible — see the note on {@code NetworkConsentScreen}.
 */
public record ConsentChoice(String label, List<String> options, int defaultIndex, IntConsumer onChosen,
                            List<List<ConsentBullet>> optionBullets, String confirmLabel) {

    /** Just the question — the card keeps its own bullets and its usual two buttons. */
    public ConsentChoice(String label, List<String> options, int defaultIndex, IntConsumer onChosen) {
        this(label, options, defaultIndex, onChosen, List.of(), null);
    }

    /** True when there is a real question to ask — at least a label and two options to pick between. */
    public boolean isRenderable() {
        return label != null && !label.isBlank() && options != null && options.size() >= 2;
    }

    /** {@link #defaultIndex} clamped into {@link #options}' range, so a bad value can't throw at render. */
    public int safeDefaultIndex() {
        if (options == null || options.isEmpty()) return 0;
        return Math.max(0, Math.min(defaultIndex, options.size() - 1));
    }

    /**
     * The bullet list for the option at {@code index}, or {@code null} when this choice supplies none
     * for it — in which case the card falls back to its own feature / non-feature blocks. Never throws
     * on a short or ragged {@code optionBullets}.
     */
    public List<ConsentBullet> bulletsFor(int index) {
        if (optionBullets == null || index < 0 || index >= optionBullets.size()) return null;
        List<ConsentBullet> bullets = optionBullets.get(index);
        return bullets == null || bullets.isEmpty() ? null : bullets;
    }

    /** True when the card should show a single confirming button instead of Enable / Not now. */
    public boolean hasConfirmLabel() {
        return confirmLabel != null && !confirmLabel.isBlank();
    }
}
