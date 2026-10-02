package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.OptionalInt;

/**
 * Optional, reflective view of Dungeon Train's in-game dev HUD, so ours can sit
 * directly under whatever it drew this frame.
 *
 * <p>Discord Presence must load without Dungeon Train, so nothing here names a
 * DT type statically (the same approach as {@code reincarnation.PlayerMobSeam}).
 * The handles are resolved once, on first use. If DT is absent, or its HUD class
 * has changed shape, every call answers {@link OptionalInt#empty()} and the
 * caller falls back to {@link DevHudStack}'s fixed band — a gap, never a crash.
 *
 * <p>The three DT members read are the same ones DT's own train debug panel uses
 * to stack itself under that HUD: {@code VersionHudOverlay.isDrawing(Minecraft)},
 * {@code VersionHudOverlay.lineCount()} and {@code HudText.scaledLineHeight(Font)}.
 */
public final class DungeonTrainHudSeam {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "dungeontrain";
    private static final String PKG = "games.brennan.dungeontrain.client.";

    /** DT draws each line one px taller than its (scaled) font height. */
    static final int DT_LINE_GAP = 1;

    private record Handles(Method isDrawing, Method lineCount, Method scaledLineHeight) {}

    private static boolean resolved;
    private static Handles handles;

    private DungeonTrainHudSeam() {}

    /**
     * First free Y (px) under Dungeon Train's dev HUD this frame, or empty when
     * it cannot be measured. Render thread only.
     */
    public static OptionalInt nextFreeY(Minecraft mc, Font font) {
        Handles h = handles();
        if (h == null) {
            return OptionalInt.empty();
        }
        try {
            boolean drawing = (boolean) h.isDrawing().invoke(null, mc);
            int lines = (int) h.lineCount().invoke(null);
            int pitch = (int) h.scaledLineHeight().invoke(null, font) + DT_LINE_GAP;
            return OptionalInt.of(nextFreeY(drawing, lines, pitch));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("DungeonTrainHudSeam: measuring Dungeon Train's dev HUD failed — using the fixed band", e);
            handles = null;
            return OptionalInt.empty();
        }
    }

    /** The placement math: the top slot when DT draws nothing, else just under its last line. */
    static int nextFreeY(boolean drawing, int lines, int pitch) {
        if (!drawing || lines <= 0) {
            return DevHudStack.TOP_MARGIN;
        }
        return DevHudStack.TOP_MARGIN + lines * pitch + DevHudStack.STACK_GAP;
    }

    private static Handles handles() {
        if (!resolved) {
            resolved = true;
            handles = resolve();
        }
        return handles;
    }

    private static Handles resolve() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return null;
        }
        try {
            ClassLoader loader = DungeonTrainHudSeam.class.getClassLoader();
            Class<?> hud = Class.forName(PKG + "VersionHudOverlay", false, loader);
            Class<?> hudText = Class.forName(PKG + "HudText", false, loader);
            Method isDrawing = hud.getDeclaredMethod("isDrawing", Minecraft.class);
            Method lineCount = hud.getDeclaredMethod("lineCount");
            Method scaledLineHeight = hudText.getMethod("scaledLineHeight", Font.class);
            isDrawing.setAccessible(true);
            lineCount.setAccessible(true);
            LOGGER.info("DungeonTrainHudSeam: resolved — dev HUD stacks under Dungeon Train's live block");
            return new Handles(isDrawing, lineCount, scaledLineHeight);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("DungeonTrainHudSeam: Dungeon Train's dev HUD could not be resolved — using the fixed band", e);
            return null;
        }
    }
}
