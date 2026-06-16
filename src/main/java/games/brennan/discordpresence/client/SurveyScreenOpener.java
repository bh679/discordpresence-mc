package games.brennan.discordpresence.client;

import games.brennan.discordpresence.network.SurveyQuestionPayload;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Client-only entry point for opening the feedback {@link SurveyScreen} on demand — the
 * {@code /feedback} command path. This is the single place that touches {@link Minecraft}
 * and {@link SurveyScreen} for that path, so the both-dist network handler
 * {@link games.brennan.discordpresence.network.SurveyOpenPayload} never class-loads client
 * GUI types on a dedicated server. Mirrors {@link DeathScreenSurveyButton}'s open logic.
 */
public final class SurveyScreenOpener {

    private SurveyScreenOpener() {}

    /** Open the survey over the current screen with the server-sent questions. */
    public static void open(List<SurveyQuestionPayload.Entry> questions) {
        if (questions == null || questions.isEmpty()) {
            return; // server already gated this; defensive only
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new SurveyScreen(mc.screen, questions));
    }
}
