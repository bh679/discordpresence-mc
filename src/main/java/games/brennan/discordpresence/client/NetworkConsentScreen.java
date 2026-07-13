package games.brennan.discordpresence.client;

import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig.Consent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class NetworkConsentScreen extends Screen {

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

        footnoteLines = font.split(Component.translatable(KEY_FOOTNOTE), innerWidth);

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
        int contentH = font.lineHeight + GAP_TITLE
                + bodyLines.size() * LINE_STEP + GAP_BODY
                + bulletsH
                + (nonBulletBlocks.isEmpty() ? 0 : GAP_NEG + nonBulletsH)
                + GAP_BULLETS
                + footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE
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
        cursor += GAP_BULLETS;
        footnoteY = cursor;
        cursor += footnoteLines.size() * LINE_STEP + GAP_FOOTNOTE;

        // Two buttons in a row at the card bottom.
        int innerLeft = panelX + PAD;
        int buttonW = (innerWidth - BUTTON_GAP) / 2;
        int buttonY = cursor;
        addRenderableWidget(Button.builder(Component.translatable(KEY_ENABLE), b -> answer(Consent.GRANTED))
                .bounds(innerLeft, buttonY, buttonW, BUTTON_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable(KEY_NOT_NOW), b -> answer(Consent.DENIED))
                .bounds(innerLeft + buttonW + BUTTON_GAP, buttonY, innerWidth - buttonW - BUTTON_GAP, BUTTON_H)
                .build());
    }

    /** Persist the choice and return to whatever screen we opened over (the title screen). */
    private void answer(Consent consent) {
        DiscordPresenceClientConfig.setConsent(consent);
        this.minecraft.setScreen(previousScreen);
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

        int fy = footnoteY;
        for (FormattedCharSequence line : footnoteLines) {
            graphics.drawCenteredString(font, line, centerX, fy, COLOR_FOOTNOTE);
            fy += LINE_STEP;
        }
    }

    /** Esc behaves like "Not now": record DENIED so the prompt is answered, not re-shown. */
    @Override
    public void onClose() {
        answer(Consent.DENIED);
    }
}
