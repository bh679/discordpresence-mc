package games.brennan.discordpresence.client;

import games.brennan.discordpresence.client.DevHudStack.BranchLookup;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the dev-HUD stacking math — that Discord Presence reserves a band only
 * for higher-ranked sibling mods that are actually drawing (present + non-main),
 * so its HUD never overlaps theirs. Uses an injected {@link BranchLookup} so no
 * running client / classpath resources are needed.
 */
class DevHudStackTest {

    private static final int LINE_HEIGHT = 9;

    /** Builds a lookup from a fixed id→branch map (missing keys resolve to null). */
    private static BranchLookup lookup(Map<String, String> branches) {
        return branches::get;
    }

    private static int expectedStartY(int siblingsAbove) {
        return DevHudStack.TOP_MARGIN + siblingsAbove * DevHudStack.RESERVED_LINES * LINE_HEIGHT;
    }

    @Test
    void noSiblings_takesTopSlot() {
        BranchLookup none = lookup(Map.of());
        assertEquals(0, DevHudStack.drawingSiblingsAbove(none));
        assertEquals(expectedStartY(0), DevHudStack.startY(none, LINE_HEIGHT));
    }

    @Test
    void dungeonTrainOnDevBranch_reservesOneSlot() {
        BranchLookup dtDev = lookup(Map.of("dungeontrain", "claude/busy-raman-3d6507"));
        assertEquals(1, DevHudStack.drawingSiblingsAbove(dtDev));
        assertEquals(expectedStartY(1), DevHudStack.startY(dtDev, LINE_HEIGHT));
    }

    @Test
    void dungeonTrainOnMain_reservesNothing() {
        BranchLookup dtMain = lookup(Map.of("dungeontrain", "main"));
        assertEquals(0, DevHudStack.drawingSiblingsAbove(dtMain));
        assertEquals(expectedStartY(0), DevHudStack.startY(dtMain, LINE_HEIGHT));
    }

    @Test
    void dungeonTrainBlankBranch_reservesNothing() {
        // Blank branch (unreadable / not yet baked) is treated as not drawing.
        assertEquals(0, DevHudStack.drawingSiblingsAbove(lookup(Map.of("dungeontrain", ""))));
        assertEquals(0, DevHudStack.drawingSiblingsAbove(lookup(Map.of("dungeontrain", "   "))));
    }

    @Test
    void lowerRankedSibling_doesNotReserveAboveUs() {
        // adventureitemnames ranks BELOW discordpresence — even on a dev branch it
        // must not push our HUD down.
        BranchLookup belowDev = lookup(Map.of("adventureitemnames", "feature/x"));
        assertEquals(0, DevHudStack.drawingSiblingsAbove(belowDev));
        assertEquals(expectedStartY(0), DevHudStack.startY(belowDev, LINE_HEIGHT));
    }

    @Test
    void onlyHigherRankedDrawingMods_count() {
        // DT (above, dev) reserves; a below-ranked sibling on dev does not add to it.
        BranchLookup mixed = lookup(Map.of(
                "dungeontrain", "dev/a",
                "adventureitemnames", "dev/b"));
        assertEquals(1, DevHudStack.drawingSiblingsAbove(mixed));
        assertEquals(expectedStartY(1), DevHudStack.startY(mixed, LINE_HEIGHT));
    }
}
