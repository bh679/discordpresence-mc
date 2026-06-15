package games.brennan.discordpresence.client;

import games.brennan.discordpresence.network.DPNetwork;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.discordpresence.network.SurveySubmitPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The feedback survey window: one question (a 0–N rating row + an optional comment),
 * opened from the death-screen Feedback button. Submitting sends the answer to the
 * server (which posts it to Discord) and returns to the death screen; skipping just
 * returns — the question is offered again on the next death.
 */
public final class SurveyScreen extends Screen {

    private static final int CONTENT_WIDTH = 280;
    private static final int SCORE_GAP = 2;
    private static final int SCORE_H = 20;
    private static final int SELECT_COLOR = 0xFF55FF55;
    private static final int NO_SCORE = Integer.MIN_VALUE;

    private final Screen parent;
    private final SurveyQuestionPayload question;
    private final Button[] scoreButtons;

    private int selectedScore = NO_SCORE;
    private EditBox commentBox;
    private Button submitButton;

    public SurveyScreen(Screen parent, SurveyQuestionPayload question) {
        super(Component.literal("Feedback")); // narration title
        this.parent = parent;
        this.question = question;
        this.scoreButtons = new Button[Math.max(1, question.scaleMax() - question.scaleMin() + 1)];
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int left = centerX - CONTENT_WIDTH / 2;

        int promptLines = this.font.split(Component.literal(question.prompt()), CONTENT_WIDTH).size();
        int promptBottom = promptTop() + promptLines * (this.font.lineHeight + 2);

        // 0–N score buttons in a single centered row.
        int count = scoreButtons.length;
        int cellW = (CONTENT_WIDTH - (count - 1) * SCORE_GAP) / count;
        int rowW = count * cellW + (count - 1) * SCORE_GAP;
        int rowX = centerX - rowW / 2;
        int scoreY = promptBottom + 12;
        for (int i = 0; i < count; i++) {
            int value = question.scaleMin() + i;
            int x = rowX + i * (cellW + SCORE_GAP);
            Button b = Button.builder(Component.literal(Integer.toString(value)), btn -> selectScore(value))
                    .bounds(x, scoreY, cellW, SCORE_H)
                    .build();
            scoreButtons[i] = b;
            addRenderableWidget(b);
        }

        int y = scoreY + SCORE_H + 10;

        // Optional comment box.
        if (question.allowComment()) {
            commentBox = new EditBox(this.font, left, y, CONTENT_WIDTH, 20, Component.literal("Comment"));
            commentBox.setMaxLength(256);
            commentBox.setHint(Component.literal("Optional comment…"));
            addRenderableWidget(commentBox);
            y += 28;
        }

        // Submit + Skip.
        int btnW = (CONTENT_WIDTH - 8) / 2;
        submitButton = Button.builder(Component.literal("Submit"), b -> submit())
                .bounds(left, y, btnW, 20)
                .build();
        submitButton.active = false;
        addRenderableWidget(submitButton);
        addRenderableWidget(Button.builder(Component.literal("Skip"), b -> onClose())
                .bounds(left + btnW + 8, y, btnW, 20)
                .build());
    }

    private int promptTop() {
        return Math.max(30, this.height / 2 - 70);
    }

    private void selectScore(int value) {
        this.selectedScore = value;
        this.submitButton.active = true;
    }

    private void submit() {
        if (selectedScore == NO_SCORE) {
            return;
        }
        String comment = commentBox == null ? "" : commentBox.getValue().trim();
        DPNetwork.sendToServer(new SurveySubmitPayload(question.questionId(), selectedScore, comment));
        SurveyClientState.clear(); // hide the button immediately; the server confirms with a clear too
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Wrapped question prompt, centered above the score row.
        List<FormattedCharSequence> lines = this.font.split(Component.literal(question.prompt()), CONTENT_WIDTH);
        int ly = promptTop();
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, ly, 0xFFFFFF);
            ly += this.font.lineHeight + 2;
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        // Highlight the selected score with an accent outline (drawn over the buttons).
        if (selectedScore != NO_SCORE) {
            int idx = selectedScore - question.scaleMin();
            if (idx >= 0 && idx < scoreButtons.length) {
                Button b = scoreButtons[idx];
                int x1 = b.getX() - 1;
                int y1 = b.getY() - 1;
                int x2 = b.getX() + b.getWidth() + 1;
                int y2 = b.getY() + b.getHeight() + 1;
                graphics.fill(x1, y1, x2, y1 + 1, SELECT_COLOR);
                graphics.fill(x1, y2 - 1, x2, y2, SELECT_COLOR);
                graphics.fill(x1, y1, x1 + 1, y2, SELECT_COLOR);
                graphics.fill(x2 - 1, y1, x2, y2, SELECT_COLOR);
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
