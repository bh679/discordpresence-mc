package games.brennan.discordpresence.client;

import games.brennan.discordpresence.DiscordPresence;
import net.neoforged.fml.ModList;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * Decides where Discord Presence's top-left dev HUD starts so it stacks
 * <em>below</em> any sibling mod's dev HUD instead of overlapping it.
 *
 * <p>Discord Presence is bundled inside Dungeon Train (DT) via jarJar, and DT
 * draws its own dev HUD in the top-left. DP cannot read DT's live, per-frame
 * line count across the mod boundary — but every sibling that adopts this
 * convention bakes a {@code <modid>_version.properties} ({@code version} +
 * {@code branch}) into its jar, all of which land on the shared runtime
 * classpath. DP reads those to learn which higher-ranked siblings are present
 * <em>and</em> on a dev branch (i.e. actually drawing), and reserves a fixed
 * band per such sibling.
 *
 * <p>Reserving a fixed band (rather than tracking each sibling's exact height)
 * <strong>guarantees no overlap</strong> at the cost of a small gap when the
 * sibling above is collapsed to one line. {@link #RESERVED_LINES} is sized to a
 * sibling's realistic maximum block; tune it there if a sibling grows taller.
 *
 * <p>The branch lookup is injectable ({@link BranchLookup}) so the offset math
 * is unit-testable without a running client; production uses {@link #CLASSPATH}.
 */
public final class DevHudStack {

    /**
     * Mods that may draw a top-left dev HUD, highest-first. Index 0 owns the
     * very top of the screen; each later entry stacks below the ones before it
     * that are currently drawing. Only Dungeon Train ships a HUD today; the rest
     * are reserved slots so a future sibling HUD stacks instead of colliding.
     */
    static final List<String> HUD_ORDER = List.of(
            "dungeontrain",
            DiscordPresence.MOD_ID, // "discordpresence"
            "adventureitemnames",
            "adventureitemstats",
            "playermob");

    /** Top margin in px — matches Dungeon Train's {@code y = 4} anchor. */
    static final int TOP_MARGIN = 4;

    /**
     * Lines reserved per drawing sibling ranked above us. Sized to a sibling's
     * realistic max block (DT peaks at ~4 lines with debug flags on).
     */
    static final int RESERVED_LINES = 5;

    /** Resolves a mod-id to its baked git branch, or {@code null} if absent / unreadable. */
    @FunctionalInterface
    public interface BranchLookup {
        String branchOf(String modId);
    }

    private DevHudStack() {}

    /**
     * Count of HUD mods ranked above us that are currently drawing — present and
     * on a non-{@code main} branch. A {@code null}/blank/{@code "main"} branch
     * (absent, on a release build, or unreadable) does not reserve space.
     */
    static int drawingSiblingsAbove(BranchLookup lookup) {
        int count = 0;
        for (String id : HUD_ORDER) {
            if (id.equals(DiscordPresence.MOD_ID)) {
                break; // stop at ourselves — only mods ranked above reserve space
            }
            if (isDrawing(lookup.branchOf(id))) {
                count++;
            }
        }
        return count;
    }

    /** Top-left Y (px) where our HUD block should start, for the given font line height. */
    static int startY(BranchLookup lookup, int lineHeight) {
        return TOP_MARGIN + drawingSiblingsAbove(lookup) * RESERVED_LINES * lineHeight;
    }

    /** Convenience for the overlay: {@link #startY(BranchLookup, int)} via the real classpath lookup. */
    public static int startY(int lineHeight) {
        return startY(CLASSPATH, lineHeight);
    }

    private static boolean isDrawing(String branch) {
        return branch != null && !branch.isBlank() && !"main".equals(branch);
    }

    /**
     * Production lookup: reads {@code /<modid>_version.properties} off the shared
     * classpath, gated on the mod actually being loaded. Best-effort — anything
     * missing or unreadable resolves to {@code null} (treated as not drawing).
     */
    static final BranchLookup CLASSPATH = modId -> {
        if (!ModList.get().isLoaded(modId)) {
            return null;
        }
        try (InputStream in = DevHudStack.class.getResourceAsStream("/" + modId + "_version.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("branch");
        } catch (Exception e) {
            return null;
        }
    };
}
