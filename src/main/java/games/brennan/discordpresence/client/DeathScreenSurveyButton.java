package games.brennan.discordpresence.client;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;

/**
 * Client-only: injects a "Give Feedback" button onto the vanilla death screen whenever
 * the server has sent this player one or more survey questions (see
 * {@link SurveyClientState}). The button opens the {@link SurveyScreen}, which walks
 * through them.
 *
 * <p>Added to every death screen but kept invisible until questions are cached, and its
 * visibility is re-evaluated every frame — so a packet that arrives just after the screen
 * initialises still surfaces the button, and finishing the walk (which clears the cache)
 * hides it again. Anchored bottom-centre, clear of vanilla's button stack and Dungeon
 * Train's death recap.</p>
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID, value = Dist.CLIENT)
public final class DeathScreenSurveyButton {

    private static final int WIDTH = 150;
    private static final int HEIGHT = 20;

    private static Button button;

    private DeathScreenSurveyButton() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DeathScreen death)) {
            return;
        }
        int x = death.width / 2 - WIDTH / 2;
        int y = death.height - 30;
        button = Button.builder(Component.literal("Give Feedback"), b -> openSurvey())
                .bounds(x, y, WIDTH, HEIGHT)
                .build();
        button.visible = SurveyClientState.hasQuestions();
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (button != null && event.getScreen() instanceof DeathScreen) {
            button.visible = SurveyClientState.hasQuestions();
        }
    }

    private static void openSurvey() {
        List<SurveyQuestionPayload.Entry> questions = SurveyClientState.questions();
        if (questions.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new SurveyScreen(mc.screen, questions));
    }
}
