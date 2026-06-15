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
 * The feedback survey window: walks the player's unanswered questions one screen at a
 * time. Each question is a 0–N rating + an optional comment; Submit sends the answer to
 * the server (which posts it to Discord) and advances to the next question; Skip advances
 * without recording (re-offered next death). After the last question the screen closes
 * back to the death screen.
 */
public final class SurveyScreen extends Screen {

    private static final int CONTENT_WIDTH = 280;
    private static final int SCORE_GAP = 2;
    private static final int SCORE_H = 20;
    private static final int SELECT_COLOR = 0xFF55FF55;
    private static final int NO_SCORE = Integer.MIN_VALUE;

    private final Screen parent;
    private final List<SurveyQuestionPayload.Entry> questions;

    private int index = 0;
    private int selectedScore = NO_SCORE;
    private Button[] scoreButtons = new Button[0];
    private EditBox commentBox;
    private Button submitButton;

    public SurveyScreen(Screen parent, List<SurveyQuestionPayload.Entry> questions) {
        super(Component.literal("Feedback")); // narration title
        this.parent = parent;
        this.questions = List.copyOf(questions);
    }

    private SurveyQuestionPayload.Entry current() {
        return questions.get(index);
    }

    @Override
    protected void init() {
        SurveyQuestionPayload.Entry q = current();
        int centerX = this.width / 2;
        int left = centerX - CONTENT_WIDTH / 2;

        int promptLines = this.font.split(Component.literal(q.prompt()), CONTENT_WIDTH).size();
        int promptBottom = promptTop() + promptLines * (this.font.lineHeight + 2);

        // 0–N score buttons in a single centered row.
        int count = Math.max(1, q.scaleMax() - q.scaleMin() + 1);
        scoreButtons = new Button[count];
        int cellW = (CONTENT_WIDTH - (count - 1) * SCORE_GAP) / count;
        int rowW = count * cellW + (count - 1) * SCORE_GAP;
        int rowX = centerX - rowW / 2;
        int scoreY = promptBottom + 12;
        for (int i = 0; i < count; i++) {
            int value = q.scaleMin() + i;
            int x = rowX + i * (cellW + SCORE_GAP);
            Button b = Button.builder(Component.literal(Integer.toString(value)), btn -> selectScore(value))
                    .bounds(x, scoreY, cellW, SCORE_H)
                    .build();
            scoreButtons[i] = b;
            addRenderableWidget(b);
        }

        int y = scoreY + SCORE_H + 10;

        // Optional comment box (fresh per question).
        if (q.allowComment()) {
            commentBox = new EditBox(this.font, left, y, CONTENT_WIDTH, 20, Component.literal("Comment"));
            commentBox.setMaxLength(256);
            commentBox.setHint(Component.literal("What's the main reason for your score? (optional)"));
            addRenderableWidget(commentBox);
            y += 28;
        } else {
            commentBox = null;
        }

        // Submit (+ advance) + Skip.
        int btnW = (CONTENT_WIDTH - 8) / 2;
        boolean last = index == questions.size() - 1;
        submitButton = Button.builder(Component.literal(last ? "Submit" : "Submit & next"), b -> submit())
                .bounds(left, y, btnW, 20)
                .build();
        submitButton.active = false;
        addRenderableWidget(submitButton);
        addRenderableWidget(Button.builder(Component.literal("Skip"), b -> skip())
                .bounds(left + btnW + 8, y, btnW, 20)
                .build());
    }

    private int promptTop() {
        return Math.max(36, this.height / 2 - 70);
    }

    private void selectScore(int value) {
        this.selectedScore = value;
        this.submitButton.active = true;
    }

    private void submit() {
        if (selectedScore == NO_SCORE) {
            return;
        }
        SurveyQuestionPayload.Entry q = current();
        String comment = commentBox == null ? "" : commentBox.getValue().trim();
        DPNetwork.sendToServer(new SurveySubmitPayload(q.id(), selectedScore, comment));
        advance();
    }

    private void skip() {
        advance();
    }

    /** Move to the next question (resetting per-question state), or finish and close. */
    private void advance() {
        index++;
        selectedScore = NO_SCORE;
        if (index < questions.size()) {
            this.rebuildWidgets(); // re-run init() for the next question
        } else {
            SurveyClientState.clear(); // walked them all — hide the button
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background blur/dim + widgets first; our text goes on top (drawing before
        // super.render() lets the background pass blur it).
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Progress indicator when there's more than one question to walk.
        if (questions.size() > 1) {
            graphics.drawCenteredString(this.font,
                    Component.literal("Question " + (index + 1) + " of " + questions.size()),
                    this.width / 2, promptTop() - 14, 0xA0A0A0);
        }

        // Wrapped question prompt, centered above the score row (drawn last → crisp).
        List<FormattedCharSequence> lines = this.font.split(Component.literal(current().prompt()), CONTENT_WIDTH);
        int ly = promptTop();
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, ly, 0xFFFFFF);
            ly += this.font.lineHeight + 2;
        }

        // Highlight the selected score with an accent outline (drawn over the buttons).
        if (selectedScore != NO_SCORE) {
            int idx = selectedScore - current().scaleMin();
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
