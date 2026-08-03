package games.brennan.discordpresence.config;

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
 * @param tooltip optional hover text explaining the line, or {@code null} for none. Rendered as a
 *                normal Minecraft tooltip at the cursor.
 */
public record ConsentBullet(String text, boolean on, String tooltip) {

    /** A bullet with no hover text. */
    public ConsentBullet(String text, boolean on) {
        this(text, on, null);
    }

    /** True when this line has hover text worth rendering. */
    public boolean hasTooltip() {
        return tooltip != null && !tooltip.isBlank();
    }
}
