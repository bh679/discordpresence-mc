package games.brennan.discordpresence.config;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One line of the title-screen consent card's bulleted list, when the bundling mod supplies its own
 * per-option lists through {@link ConsentChoice#optionBullets()}.
 *
 * <p>DP's original seams split the list in two — {@code networkConsentFeatures()} drew blue-dot
 * "what this is for" lines and {@code networkConsentNonFeatures()} drew red-✗ "won't do" lines — so
 * which marker a line got was decided by which list it arrived in. That cannot express a line whose
 * marker depends on which option the player has selected, which is exactly what an Adult/Kid style
 * choice needs. Here the marker travels WITH the line instead, as {@link #on}.</p>
 *
 * @param text    the line, already localized by the bundling mod. Wrapped to the card's inner width.
 * @param on      {@code true} draws the blue dot ("this happens"), {@code false} the red ✗ ("this
 *                does not"). The same line may be on under one option and off under another.
 * @param tooltip  optional hover text explaining the line, or {@code null} for none. Rendered as a
 *                 normal Minecraft tooltip at the cursor.
 * @param onToggle when non-null the line is a <b>switch</b>: the card draws an ON/OFF pill where the
 *                 marker would be, starting at {@link #on}, and calls this with the new state each time
 *                 the player clicks it (client thread). The card stores nothing — the bundler owns the
 *                 setting, and the pill resets to {@link #on} whenever the card rebuilds its lines (a
 *                 different option picked, a resize), so a bundler that wants defaults per option just
 *                 hands different {@link #on} values per option. {@code null} = plain marker line.
 */
public record ConsentBullet(String text, boolean on, String tooltip, Consumer<Boolean> onToggle) {

    /** A plain marker line with hover text. */
    public ConsentBullet(String text, boolean on, String tooltip) {
        this(text, on, tooltip, null);
    }

    /** A plain marker line with no hover text. */
    public ConsentBullet(String text, boolean on) {
        this(text, on, null, null);
    }

    /** A switchable line: ON/OFF pill, starting {@code on}, reporting each flip to {@code onToggle}. */
    public static ConsentBullet toggle(String text, boolean on, String tooltip, Consumer<Boolean> onToggle) {
        return new ConsentBullet(text, on, tooltip, Objects.requireNonNull(onToggle, "onToggle"));
    }

    /** True when this line has hover text worth rendering. */
    public boolean hasTooltip() {
        return tooltip != null && !tooltip.isBlank();
    }

    /** True when the card should draw this line as a clickable ON/OFF pill. */
    public boolean isToggle() {
        return onToggle != null;
    }
}
