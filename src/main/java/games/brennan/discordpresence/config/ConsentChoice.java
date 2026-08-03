package games.brennan.discordpresence.config;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * An extra multiple-choice question a bundling mod can put on the title-screen network-consent card
 * (see {@code client.NetworkConsentScreen}), supplied through
 * {@link DiscordCredentialsProvider#networkConsentChoice()}.
 *
 * <p>The card is the one screen every player answers exactly once, on their first launch, which makes
 * it the natural home for a second one-time question — asking it there costs no additional
 * interruption, where a second card would. Dungeon Train uses it for its Adult / Kid content mode.
 * DP itself has no opinion on what the question is: it renders the label and options it is handed and
 * reports back an index.</p>
 *
 * <p>The choice is <b>not</b> a second consent gate. It is recorded on every exit path — either
 * button, and Esc — so the bundling mod always learns the answer, and DP never stores it: {@code
 * onChosen} is the only place it goes, and the provider owns the persistence.</p>
 *
 * @param label        the question, shown above the option control (already localized by the provider).
 * @param options      the choices, in display order. Fewer than two options is meaningless and the
 *                     screen skips the control entirely rather than rendering a dead widget.
 * @param defaultIndex which option starts selected; out-of-range values clamp to 0.
 * @param onChosen     called with the selected index as the card closes, on the client thread. Called
 *                     exactly once per showing, including when the player presses Esc (which answers
 *                     the consent question as DENIED but must still answer this one). May be null,
 *                     though a choice nobody listens to is only useful for previewing layout.
 */
public record ConsentChoice(String label, List<String> options, int defaultIndex, IntConsumer onChosen) {

    /** True when there is a real question to ask — at least a label and two options to pick between. */
    public boolean isRenderable() {
        return label != null && !label.isBlank() && options != null && options.size() >= 2;
    }

    /** {@link #defaultIndex} clamped into {@link #options}' range, so a bad value can't throw at render. */
    public int safeDefaultIndex() {
        if (options == null || options.isEmpty()) return 0;
        return Math.max(0, Math.min(defaultIndex, options.size() - 1));
    }
}
