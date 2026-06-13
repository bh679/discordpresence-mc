package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.DiscordPresence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.slf4j.Logger;

/**
 * Client-only HUD overlay: draws "Discord Presence v&lt;version&gt; (&lt;branch&gt;)"
 * in the top-left corner in-game, so a dev build is always visually obvious.
 *
 * <p>Hidden on release builds — the overlay early-returns when the baked branch
 * is {@code main}. Respects F1 (hideGui); F3 debug draws over this, which is
 * intentional. Positioned via {@link DevHudStack} so it stacks below any sibling
 * mod's dev HUD (e.g. Dungeon Train, which bundles this mod) instead of
 * overlapping it.
 */
@EventBusSubscriber(
        modid = DiscordPresence.MOD_ID,
        value = Dist.CLIENT
)
public final class VersionHudOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VersionHudOverlay() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        LayeredDraw.Layer overlay = (graphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui) {
                return;
            }
            // Release builds run on `main`; the version/branch label is dev-only noise there.
            if ("main".equals(VersionInfo.BRANCH)) {
                return;
            }
            int startY = DevHudStack.startY(mc.font.lineHeight);
            graphics.drawString(mc.font, VersionInfo.DISPLAY, 4, startY, 0xFFFFFFFF, true);
        };

        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "version_hud"),
                overlay);
        LOGGER.info("Version HUD registered: {}", VersionInfo.DISPLAY);
    }
}
