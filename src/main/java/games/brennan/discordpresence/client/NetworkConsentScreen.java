package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.ConsentBullet;
import games.brennan.discordpresence.config.ConsentChoice;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig.Consent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The one-time network-access confirmation, shown on the title screen the first time consent is
 * still {@code UNSET} (see {@code ClientPresenceEvents}). Replaces the old generic vanilla
 * {@code ConfirmScreen} with a small, flat, custom card that lists <i>what</i> the connection is
 * for — the bullet lines come from the bundling mod via
 * {@link DiscordCredentials#providerNetworkConsentFeatures()} (standalone DP shows a generic
 * fallback line, so DP stays generic and hard-codes no host-specific reasons).
 *
 * <p>Either button records the answer in {@link DiscordPresenceClientConfig} and returns to the
 * screen we came from; Esc / {@link #onClose()} behaves like "Not now" so the prompt is answered
 * (DENIED) rather than re-shown. Client-only — never class-loaded on a dedicated server.</p>
 *
 * <p>A bundling mod may customise the card through
 * {@link DiscordCredentials#providerNetworkConsentChoice()} (see {@link ConsentChoice}): it adds one
 * extra question, and may additionally replace the bullet list with a per-option one
 * ({@link ConsentChoice#optionBullets()}) and collapse the two buttons into a single confirming one
 * ({@link ConsentChoice#confirmLabel()}). Supply nothing and the card lays out exactly as it always
 * did, so standalone DP and other bundlers are unaffected.</p>
 *
 * <p><b>Note on {@code confirmLabel}.</b> A single confirming button leaves Esc as the only way to
 * decline, which means the refusal still exists but is invisible. That is a deliberate choice for the
 * bundler to make and own — DP neither prevents it nor does it by default.</p>
 */
public final class NetworkConsentScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Localisable text keys (English defaults live in assets/discordpresence/lang/en_us.json). The
    // feature bullets come from the bundling mod as raw Strings; only the standalone-DP fallback
    // bullet is a lang key here.
    private static final String KEY_TITLE = "discordpresence.consent.title";
    private static final String KEY_BODY = "discordpresence.consent.body";
    private static final String KEY_FEATURE_FALLBACK = "discordpresence.consent.feature_fallback";
    private static final String KEY_FOOTNOTE = "discordpresence.consent.footnote";
    private static final String KEY_ENABLE = "discordpresence.consent.enable";
    private static final String KEY_NOT_NOW = "discordpresence.consent.not_now";

    // Flat card geometry.
    private static final int CARD_W = 300;
    private static final int PAD = 14;          // inner padding
    private static final int LINE_STEP = 12;    // font lineHeight (9) + 3 breathing room
    private static final int BULLET_GAP = 2;    // extra space between bullet blocks
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 8;
    private static final int DOT_INSET = 3;     // dot x offset from inner-left
    private static final int BULLET_TEXT_INSET = 12; // bullet text x offset from inner-left

    // Section gaps.
    private static final int GAP_TITLE = 9;
    private static final int GAP_BODY = 7;
    private static final int GAP_BULLETS = 10;
    private static final int GAP_FOOTNOTE = 12;
    private static final int GAP_NEG = 4;       // gap between positive bullets and the red "won't do" block
    private static final int GAP_CHOICE = 10;   // gap above the optional provider question's control
    // With a provider question present, the footnote loses the GAP_BULLETS it normally sits under (the
    // question block consumes it) and ends up flush against the option buttons. These restore the
    // breathing room above it and tighten it below, so it reads as a note on the question rather than
    // a caption on the confirm button. Both apply ONLY when a question rendered — without one the
    // footnote keeps its original spacing exactly.
    private static final int GAP_FOOTNOTE_ABOVE_CHOICE = 9;
    private static final int GAP_FOOTNOTE_BELOW_CHOICE = 7;

    // Flat colours (no gradients).
    private static final int BACKDROP_DIM = 0x99000000;
    private static final int CARD_BG = 0xF01A1A1E;
    private static final int CARD_BORDER = 0xFF3A3A42;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_BODY = 0xFFE0E0E0;
    private static final int COLOR_BULLET = 0xFFC8C8C8;
    private static final int COLOR_DOT = 0xFF6FB1FF;
    private static final int COLOR_FOOTNOTE = 0xFF808080;
    private static final int COLOR_NEG = 0xFFFF5555;    // red ✗ marker for "won't do" lines (text stays grey)

    private final Screen previousScreen;

    // Layout, computed in init() and consumed by render().
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int centerX;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<List<FormattedCharSequence>> bulletBlocks = List.of();
    private List<List<FormattedCharSequence>> nonBulletBlocks = List.of();
    private List<FormattedCharSequence> footnoteLines = List.of();
    private int titleY;
    private int bodyY;
    private int bulletsY;
    private int nonBulletsY;
    private int footnoteY;

    /** The bundling mod's optional customisation, or {@code null} when there is none. */
    private ConsentChoice choice;
    /** Which option of {@link #choice} is currently selected; meaningless when {@code choice} is null. */
    private int choiceIndex;
    /** Set once {@link #choice}'s answer has been reported, so no exit path can report it twice. */
    private boolean choiceReported;
    private int choiceLabelY;

    /**
     * One laid-out provider bullet: its wrapped lines, its marker, its hover text, and the screen rect
     * the hover is tested against. Rebuilt whenever the selected option changes, because a different
     * option can wrap to a different number of lines and therefore shift everything below it.
     */
    private record LaidOutBullet(List<FormattedCharSequence> lines, boolean on, String tooltip,
                                 int x, int y, int w, int h) {
        boolean hasTooltipText() {
            return tooltip != null && !tooltip.isBlank();
        }
    }

    /** Per-option bullets for the CURRENT selection; empty when the provider supplied none. */
    private List<LaidOutBullet> optionBullets = List.of();
    /** The option {@link #optionBullets} was laid out for, so render can notice a toggle and re-init. */
    private int optionBulletsFor = -1;

    public NetworkConsentScreen(Screen previousScreen) {
        super(Component.translatable(KEY_TITLE)); // narration title
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        int innerWidth = CARD_W - 2 * PAD;
        int bulletTextWidth = innerWidth - BULLET_TEXT_INSET;

        // Wrap each text section to the card's inner width.
        bodyLines = font.split(Component.translatable(KEY_BODY), innerWidth);

        // Optional customisation from the bundling mod (Dungeon Train's Adult / Kid content mode).
        // Absent ⇒ every measurement below adds zero and the card is laid out byte-identically to
        // before, which is what keeps standalone DP and other bundlers untouched. Preserve a selection
        // already made this showing, so a toggle-driven re-init doesn't snap back to the default.
        ConsentChoice previous = choice;
        choice = DiscordCredentials.providerNetworkConsentChoice();
        if (choice == null) {
            choiceIndex = 0;
        } else if (previous == null) {
            choiceIndex = choice.safeDefaultIndex();
            choiceReported = false;
        } else {
            choiceIndex = Math.max(0, Math.min(choiceIndex, choice.options().size() - 1));
        }

        // The provider's own bullets for the selected option, when it supplies them, replace BOTH of
        // the card's blocks below — that is how one line's marker and wording can differ per option.
        List<ConsentBullet> supplied = choice == null ? null : choice.bulletsFor(choiceIndex);
        if (supplied != null) {
            bulletBlocks = List.of();
            nonBulletBlocks = List.of();
        } else {
            // Provider-fed feature bullets are host content (raw Strings → literal); the standalone-DP
            // fallback is the one translatable bullet.
            List<String> providerFeatures = DiscordCredentials.providerNetworkConsentFeatures();
            List<Component> features;
            if (providerFeatures == null || providerFeatures.isEmpty()) {
                features = List.of(Component.translatable(KEY_FEATURE_FALLBACK));
            } else {
                features = new ArrayList<>(providerFeatures.size());
                for (String feature : providerFeatures) {
                    features.add(Component.literal(feature));
                }
            }
            List<List<FormattedCharSequence>> blocks = new ArrayList<>(features.size());
            for (Component feature : features) {
                blocks.add(font.split(feature, bulletTextWidth));
            }
            bulletBlocks = blocks;

            // Optional "won't do" lines, rendered with a red ✗ below the positive bullets. Empty = no
            // section, so the layout below stays identical to before when the bundler supplies none.
            List<String> nonFeatures = DiscordCredentials.providerNetworkConsentNonFeatures();
            List<List<FormattedCharSequence>> negBlocks = new ArrayList<>(nonFeatures == null ? 0 : nonFeatures.size());
            if (nonFeatures != null) {
                for (String nonFeature : nonFeatures) {
                    negBlocks.add(font.split(Component.literal(nonFeature), bulletTextWidth));
                }
            }
            nonBulletBlocks = negBlocks;
        }

        // DP's own footnote names DP's /chatconnect command, which is wrong for a bundler whose players
        // change the setting somewhere else — so a provider may replace the line wholesale.
        String providerFootnote = DiscordCredentials.providerNetworkConsentFootnote();
        footnoteLines = font.split(
                providerFootnote != null ? Component.literal(providerFootnote) : Component.translatable(KEY_FOOTNOTE),
                innerWidth);

        // Sum content heights + gaps to size the panel, then centre it.
        int bulletsH = 0;
        for (List<FormattedCharSequence> block : bulletBlocks) {
            bulletsH += block.size() * LINE_STEP + BULLET_GAP;
        }
        if (!bulletBlocks.isEmpty()) {
            bulletsH -= BULLET_GAP; // no trailing gap after the last bullet
        }
        int nonBulletsH = 0;
        for (List<FormattedCharSequence> block : nonBulletBlocks) {
            nonBulletsH += block.size() * LINE_STEP + BULLET_GAP;
        }
        if (!nonBulletBlocks.isEmpty()) {
            nonBulletsH -= BULLET_GAP; // no trailing gap after the last "won't do" line
        }
        // Provider bullets are measured from the SAME wrap the render uses, so a long line that wraps
        // to three rows grows the card instead of overflowing it.
        List<List<FormattedCharSequence>> suppliedWrapped = new ArrayList<>();
        int suppliedH = 0;
        if (supplied != null) {
            for (ConsentBullet bullet : supplied) {
                List<FormattedCharSequence> lines = font.split(Component.literal(bullet.text()), bulletTextWidth);
                suppliedWrapped.add(lines);
                suppliedH += lines.size() * LINE_STEP + BULLET_GAP;
            }
            if (!suppliedWrapped.isEmpty()) suppliedH -= BULLET_GAP;
        }

        // Label line + the cycle button under it, when a question was supplied.
        int choiceH = choice == null ? 0 : GAP_CHOICE + font.lineHeight + 2 + BUTTON_H;
        int footnoteAbove = choice == null ? 0 : GAP_FOOTNOTE_ABOVE_CHOICE;
        int footnoteBelow = choice == null ? GAP_FOOTNOTE : GAP_FOOTNOTE_BELOW_CHOICE;
        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + bulletsH
                + (nonBulletBlocks.isEmpty() ? 0 : GAP_NEG + nonBulletsH)
                + suppliedH
                + GAP_BULLETS
                + choiceH
                + footnoteAbove
                + footnoteLines.size() * LINE_STEP + footnoteBelow
                + BUTTON_H;

        panelW = CARD_W;
        panelH = PAD + contentH + PAD;
        panelX = (width - panelW) / 2;
        panelY = Math.max(16, (height - panelH) / 2);
        centerX = panelX + panelW / 2;

        // Absolute Y of each section, walking a cursor down from the top padding.
        int cursor = panelY + PAD;
        titleY = cursor;
        cursor += font.lineHeight + GAP_TITLE;
        bodyY = cursor;
        cursor += bodyLines.size() * LINE_STEP + GAP_BODY;
        bulletsY = cursor;
        cursor += bulletsH;
        if (!nonBulletBlocks.isEmpty()) {
            cursor += GAP_NEG;
            nonBulletsY = cursor;
            cursor += nonBulletsH;
        }

        // Provider bullets occupy the same slot the card's own blocks would have; only one or the
        // other is ever non-empty. Rects are captured here so render() can hit-test hovers.
        int innerLeft = panelX + PAD;
        List<LaidOutBullet> laid = new ArrayList<>();
        if (supplied != null) {
            for (int i = 0; i < supplied.size(); i++) {
                ConsentBullet bullet = supplied.get(i);
                List<FormattedCharSequence> lines = suppliedWrapped.get(i);
                int h = lines.size() * LINE_STEP;
                laid.add(new LaidOutBullet(lines, bullet.on(), bullet.tooltip(),
                        innerLeft, cursor, innerWidth, h));
                cursor += h + BULLET_GAP;
            }
            if (!laid.isEmpty()) cursor -= BULLET_GAP;
        }
        optionBullets = List.copyOf(laid);
        optionBulletsFor = choiceIndex;

        cursor += GAP_BULLETS;

        // The provider's question sits between the bullets and the footnote: after what the connection
        // does, and above the answer button that also commits it.
        if (choice != null) {
            cursor += GAP_CHOICE;
            choiceLabelY = cursor;
            cursor += font.lineHeight + 2;
            // One button PER OPTION, side by side, rather than a cycle button. A cycle button shows
            // only the current value, so the player cannot see what else is on offer without clicking
            // it — on a card answered exactly once, that hides half the decision. The SELECTED option's
            // button is deactivated: greyed and unclickable is how vanilla already reads "this is the
            // current one", and it makes the remaining button the only thing to press.
            int count = choice.options().size();
            int totalGaps = BUTTON_GAP * (count - 1);
            int optionW = (innerWidth - totalGaps) / count;
            for (int i = 0; i < count; i++) {
                final int index = i;
                // Last button absorbs the rounding remainder so the row ends flush with the card.
                int w = (i == count - 1) ? innerWidth - (optionW + BUTTON_GAP) * i : optionW;
                Button option = addRenderableWidget(
                        Button.builder(Component.literal(choice.options().get(i)), b -> onOptionPicked(index))
                                .bounds(innerLeft + (optionW + BUTTON_GAP) * i, cursor, w, BUTTON_H)
                                .build());
                option.active = i != choiceIndex;
            }
            cursor += BUTTON_H;
        }

        cursor += footnoteAbove;
        footnoteY = cursor;
        cursor += footnoteLines.size() * LINE_STEP + footnoteBelow;

        int buttonY = cursor;
        if (choice != null && choice.hasConfirmLabel()) {
            // Single confirming button. Esc stays the only decline — see the class note.
            addRenderableWidget(Button.builder(Component.literal(choice.confirmLabel()), b -> answer(Consent.GRANTED))
                    .bounds(innerLeft, buttonY, innerWidth, BUTTON_H)
                    .build());
        } else {
            // Two buttons in a row at the card bottom.
            int buttonW = (innerWidth - BUTTON_GAP) / 2;
            addRenderableWidget(Button.builder(Component.translatable(KEY_ENABLE), b -> answer(Consent.GRANTED))
                    .bounds(innerLeft, buttonY, buttonW, BUTTON_H)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable(KEY_NOT_NOW), b -> answer(Consent.DENIED))
                    .bounds(innerLeft + buttonW + BUTTON_GAP, buttonY, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                    .build());
        }
    }

    /**
     * The player picked a different option. When the provider supplies per-option bullets the whole
     * card can change height — different lines, different wrapping — so the layout is rebuilt rather
     * than patched. {@link #init} preserves {@link #choiceIndex} across the rebuild.
     */
    private void onOptionPicked(int index) {
        if (index == choiceIndex) return;
        choiceIndex = index;
        // Always rebuild: even with no per-option bullets, the option buttons themselves have to swap
        // which one is deactivated, and that is decided during init().
        rebuildWidgets();
    }

    /** Persist the choice and return to whatever screen we opened over (the title screen). */
    private void answer(Consent consent) {
        reportChoice();
        DiscordPresenceClientConfig.setConsent(consent);
        this.minecraft.setScreen(previousScreen);
    }

    /**
     * Hand the bundling mod its answer, exactly once. Called from {@link #answer} so it covers every
     * exit path — every button and Esc alike: the extra question is not itself a consent gate, so
     * declining the connection (or dismissing the card) must still answer it rather than leaving the
     * provider with nothing. DP stores nothing; the provider owns persistence.
     */
    private void reportChoice() {
        if (choice == null || choiceReported) return;
        choiceReported = true;
        IntConsumer sink = choice.onChosen();
        if (sink == null) return;
        try {
            sink.accept(choiceIndex);
        } catch (Throwable t) {
            // A misbehaving bundler must not trap the player on the consent card.
            LOGGER.warn("Consent-choice listener threw; the answer was dropped.", t);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Dim the backdrop, then an extra flat dim so the card reads the same over any background.
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, BACKDROP_DIM);

        // Flat card: solid fill + a 1px border (drawn as four edge rects).
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, CARD_BG);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, CARD_BORDER);
        graphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelH, CARD_BORDER);
        graphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, CARD_BORDER);

        // Buttons (and any widgets) on top of the card background.
        super.render(graphics, mouseX, mouseY, partialTick);

        // Text on top of the card.
        graphics.drawCenteredString(font, Component.translatable(KEY_TITLE), centerX, titleY, COLOR_TITLE);

        int y = bodyY;
        for (FormattedCharSequence line : bodyLines) {
            graphics.drawCenteredString(font, line, centerX, y, COLOR_BODY);
            y += LINE_STEP;
        }

        int innerLeft = panelX + PAD;
        int by = bulletsY;
        for (List<FormattedCharSequence> block : bulletBlocks) {
            // Small flat dot marker, vertically centred on the bullet's first line.
            int dotY = by + (font.lineHeight - 3) / 2;
            graphics.fill(innerLeft + DOT_INSET, dotY, innerLeft + DOT_INSET + 3, dotY + 3, COLOR_DOT);
            for (FormattedCharSequence line : block) {
                graphics.drawString(font, line, innerLeft + BULLET_TEXT_INSET, by, COLOR_BULLET, false);
                by += LINE_STEP;
            }
            by += BULLET_GAP;
        }

        // "Won't do" lines: a red ✗ marker (where the blue dot would be) + normal bullet-grey text.
        int ny = nonBulletsY;
        for (List<FormattedCharSequence> block : nonBulletBlocks) {
            graphics.drawString(font, Component.literal("✗"), innerLeft + DOT_INSET - 1, ny, COLOR_NEG, false);
            for (FormattedCharSequence line : block) {
                graphics.drawString(font, line, innerLeft + BULLET_TEXT_INSET, ny, COLOR_BULLET, false);
                ny += LINE_STEP;
            }
            ny += BULLET_GAP;
        }

        // Provider per-option bullets. Same two markers as above, but chosen per LINE rather than per
        // block, so one line can be on under one option and off under another.
        String hovered = null;
        for (LaidOutBullet bullet : optionBullets) {
            int ly = bullet.y();
            if (bullet.on()) {
                int dotY = ly + (font.lineHeight - 3) / 2;
                graphics.fill(innerLeft + DOT_INSET, dotY, innerLeft + DOT_INSET + 3, dotY + 3, COLOR_DOT);
            } else {
                graphics.drawString(font, Component.literal("✗"), innerLeft + DOT_INSET - 1, ly, COLOR_NEG, false);
            }
            for (FormattedCharSequence line : bullet.lines()) {
                graphics.drawString(font, line, innerLeft + BULLET_TEXT_INSET, ly, COLOR_BULLET, false);
                ly += LINE_STEP;
            }
            // These are drawn strings, not widgets, so the hover is a manual hit-test against the rect
            // captured at layout time. Resolved here and rendered last, so the tooltip sits above
            // everything else on the card.
            if (bullet.hasTooltipText() && contains(bullet, mouseX, mouseY)) {
                hovered = bullet.tooltip();
            }
        }

        int fy = footnoteY;
        for (FormattedCharSequence line : footnoteLines) {
            graphics.drawCenteredString(font, line, centerX, fy, COLOR_FOOTNOTE);
            fy += LINE_STEP;
        }

        // The provider question's label. Its CycleButton is a widget, so super.render() already drew it.
        if (choice != null) {
            graphics.drawString(font, Component.literal(choice.label()), innerLeft, choiceLabelY, COLOR_BODY, false);
        }

        if (hovered != null) {
            graphics.renderTooltip(font, font.split(Component.literal(hovered), 200), mouseX, mouseY);
        }
    }

    /** Whether {@code (mx, my)} falls inside a laid-out bullet's row. */
    private static boolean contains(LaidOutBullet bullet, int mx, int my) {
        return mx >= bullet.x() && mx < bullet.x() + bullet.w()
                && my >= bullet.y() && my < bullet.y() + bullet.h();
    }

    /** Esc behaves like "Not now": record DENIED so the prompt is answered, not re-shown. */
    @Override
    public void onClose() {
        answer(Consent.DENIED);
    }
}
