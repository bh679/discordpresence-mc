package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.DiscordPresence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Client-only dev HUD: draws "Discord Presence v&lt;version&gt; (&lt;branch&gt;)"
 * in the top-left on any non-{@code main} branch — both <b>in-game</b> (a GUI
 * layer) and on the <b>title screen</b> (a screen-render hook) — and nothing on
 * a {@code main} release build, so a dev build is always visually obvious.
 *
 * <p>Positioned via {@link DevHudStack} so it stacks below any sibling mod's dev
 * HUD (e.g. Dungeon Train, which bundles this mod) instead of overlapping it.
 * Respects F1 (hideGui) in-game; F3 debug draws over it, which is intentional.
 */
@EventBusSubscriber(
        modid = DiscordPresence.MOD_ID,
        value = Dist.CLIENT
)
public final class VersionHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VersionHudOverlay() {}

    /** In-game HUD layer. */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            draw(graphics, mc.font);
        };

        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "version_hud"),
                overlay);
        LOGGER.info("Version HUD registered: {}", VersionInfo.DISPLAY);
    }

    /** Title screen (main menu) — the in-game GUI layer does not render here. */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        draw(event.getGuiGraphics(), Minecraft.getInstance().font);
    }

    /**
     * Draws the dev label top-left, stacked below sibling HUDs. Shared by the
     * in-game layer and the title screen. Early-returns on {@code main} (release
     * builds), where the version/branch label is dev-only noise.
     */
    private static void draw(GuiGraphics graphics, Font font) {
        if ("main".equals(VersionInfo.BRANCH)) {
            return;
        }
        int startY = DevHudStack.startY(font.lineHeight);
        graphics.drawString(font, VersionInfo.DISPLAY, 4, startY, 0xFFFFFFFF, true);
    }
}
