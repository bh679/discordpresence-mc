package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.DiscordPresence;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides where Discord Presence's top-left dev HUD starts so it stacks
 * <em>below</em> any sibling mod's dev HUD instead of overlapping it.
 *
 * <p>Discord Presence is bundled inside Dungeon Train (DT) via jarJar, and DT
 * draws its own dev HUD in the top-left. DP cannot read DT's live, per-frame
 * line count across the mod boundary — but every sibling that adopts this
 * convention bakes a {@code <modid>_version.properties} ({@code version} +
 * {@code branch}) into its jar. DP reads those to learn which higher-ranked
 * siblings are present <em>and</em> on a dev branch (i.e. actually drawing), and
 * reserves a fixed band per such sibling.
 *
 * <p><b>The sibling's file is read through its mod file, not the classpath.</b>
 * NeoForge loads every mod as its own named Java module, and
 * {@link Class#getResourceAsStream} from a class in a named module searches only
 * that module — so asking a DP class for {@code /dungeontrain_version.properties}
 * always comes back {@code null}. {@link #MOD_FILE} asks FML for the sibling's
 * mod file instead, which needs no compile-time dependency on the sibling.
 *
 * <p>Reserving a fixed band (rather than tracking each sibling's exact height)
 * <strong>guarantees no overlap</strong> at the cost of a small gap when the
 * sibling above is collapsed to one line. {@link #RESERVED_LINES} is sized to a
 * sibling's realistic maximum block; tune it there if a sibling grows taller.
 *
 * <p>The branch lookup is injectable ({@link BranchLookup}) so the offset math
 * is unit-testable without a running client; production uses {@link #MOD_FILE}.
 */
public final class DevHudStack {

    private static final Logger LOGGER = LogUtils.getLogger();

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
     * realistic max block: DT peaks at 6 lines, each one px taller than ours
     * (title, Diff-Car, Diff-Level, Time, Travel, group gap).
     */
    static final int RESERVED_LINES = 7;

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

    /** Convenience for the overlay: {@link #startY(BranchLookup, int)} via the real mod-file lookup. */
    public static int startY(int lineHeight) {
        return startY(MOD_FILE, lineHeight);
    }

    private static boolean isDrawing(String branch) {
        return branch != null && !branch.isBlank() && !"main".equals(branch);
    }

    /** The {@code branch} value of a {@code <modid>_version.properties} stream, or {@code null} if it has none. */
    static String readBranch(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return props.getProperty("branch");
    }

    /**
     * Wraps a lookup so each mod-id is resolved once, {@code null} results
     * included. A baked branch cannot change while the game runs, and the
     * overlay asks every frame.
     */
    static BranchLookup memoized(BranchLookup delegate) {
        Map<String, Optional<String>> cache = new ConcurrentHashMap<>();
        return modId -> cache
                .computeIfAbsent(modId, id -> Optional.ofNullable(delegate.branchOf(id)))
                .orElse(null);
    }

    /**
     * Production lookup: reads {@code <modid>_version.properties} from the root
     * of the sibling's own mod file. A mod that is not loaded, or that does not
     * bake the file, resolves to {@code null} (treated as not drawing).
     */
    static final BranchLookup MOD_FILE = memoized(modId -> {
        try {
            IModFileInfo info = ModList.get().getModFileById(modId);
            if (info == null) {
                return null;
            }
            Path properties = info.getFile().findResource(modId + "_version.properties");
            if (!Files.isRegularFile(properties)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(properties)) {
                return readBranch(in);
            }
        } catch (Exception e) {
            LOGGER.warn("DevHudStack: could not read the dev branch of '{}' — not reserving HUD space for it",
                    modId, e);
            return null;
        }
    });
}
