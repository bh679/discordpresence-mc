package games.brennan.discordpresence.client;

import games.brennan.discordpresence.client.DevHudStack.BranchLookup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the dev-HUD stacking math — that Discord Presence sits one line under a
 * higher-ranked sibling on the title screen, directly under its live block
 * in-game, and otherwise below a fixed band reserved only for siblings that are
 * actually drawing (present + non-main), so its HUD never overlaps theirs. Uses an injected {@link BranchLookup} so no
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

    private static InputStream properties(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void readBranch_returnsBakedBranch() throws IOException {
        assertEquals("dev/hud-stack",
                DevHudStack.readBranch(properties("version=0.60.1\nbranch=dev/hud-stack\n")));
    }

    @Test
    void readBranch_withoutBranchKey_isNull() throws IOException {
        assertNull(DevHudStack.readBranch(properties("version=0.60.1\n")));
    }

    @Test
    void memoized_resolvesEachModOnce() {
        List<String> asked = new ArrayList<>();
        BranchLookup counting = modId -> {
            asked.add(modId);
            return "dungeontrain".equals(modId) ? "dev/a" : null;
        };
        BranchLookup memoized = DevHudStack.memoized(counting);

        for (int frame = 0; frame < 3; frame++) {
            assertEquals("dev/a", memoized.branchOf("dungeontrain"));
            assertNull(memoized.branchOf("playermob")); // a null result is cached too
        }

        assertEquals(List.of("dungeontrain", "playermob"), asked);
    }

    @Test
    void noSiblings_takesTopSlot() {
        BranchLookup none = lookup(Map.of());
        assertEquals(0, DevHudStack.drawingSiblingsAbove(none));
        assertEquals(expectedStartY(0), DevHudStack.inGameStartY(OptionalInt.empty(), none, LINE_HEIGHT));
    }

    @Test
    void dungeonTrainOnDevBranch_reservesOneSlot() {
        BranchLookup dtDev = lookup(Map.of("dungeontrain", "claude/busy-raman-3d6507"));
        assertEquals(1, DevHudStack.drawingSiblingsAbove(dtDev));
        assertEquals(expectedStartY(1), DevHudStack.inGameStartY(OptionalInt.empty(), dtDev, LINE_HEIGHT));
    }

    @Test
    void dungeonTrainOnMain_reservesNothing() {
        BranchLookup dtMain = lookup(Map.of("dungeontrain", "main"));
        assertEquals(0, DevHudStack.drawingSiblingsAbove(dtMain));
        assertEquals(expectedStartY(0), DevHudStack.inGameStartY(OptionalInt.empty(), dtMain, LINE_HEIGHT));
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
        assertEquals(expectedStartY(0), DevHudStack.inGameStartY(OptionalInt.empty(), belowDev, LINE_HEIGHT));
    }

    @Test
    void onlyHigherRankedDrawingMods_count() {
        // DT (above, dev) reserves; a below-ranked sibling on dev does not add to it.
        BranchLookup mixed = lookup(Map.of(
                "dungeontrain", "dev/a",
                "adventureitemnames", "dev/b"));
        assertEquals(1, DevHudStack.drawingSiblingsAbove(mixed));
        assertEquals(expectedStartY(1), DevHudStack.inGameStartY(OptionalInt.empty(), mixed, LINE_HEIGHT));
    }

    @Test
    void title_dungeonTrainLoaded_sitsOneLineDown_onAnyBranch() {
        // DT shows its version line on the title screen on release builds too.
        int oneLineDown = DevHudStack.TOP_MARGIN + LINE_HEIGHT + DevHudStack.TITLE_LINE_GAP;
        assertEquals(oneLineDown, DevHudStack.titleStartY(lookup(Map.of("dungeontrain", "main")), LINE_HEIGHT));
        assertEquals(oneLineDown, DevHudStack.titleStartY(lookup(Map.of("dungeontrain", "dev/a")), LINE_HEIGHT));
    }

    @Test
    void title_withoutSiblingsAbove_takesTopSlot() {
        assertEquals(DevHudStack.TOP_MARGIN, DevHudStack.titleStartY(lookup(Map.of()), LINE_HEIGHT));
        assertEquals(DevHudStack.TOP_MARGIN,
                DevHudStack.titleStartY(lookup(Map.of("adventureitemnames", "dev/b")), LINE_HEIGHT));
    }

    @Test
    void inGame_measuredBlockAbove_winsOverTheFixedBand() {
        BranchLookup dtDev = lookup(Map.of("dungeontrain", "dev/a"));
        assertEquals(25, DevHudStack.inGameStartY(OptionalInt.of(25), dtDev, LINE_HEIGHT));
    }

    @Test
    void seam_dungeonTrainNotDrawing_leavesTheTopSlot() {
        assertEquals(DevHudStack.TOP_MARGIN, DungeonTrainHudSeam.nextFreeY(false, 3, 10));
        assertEquals(DevHudStack.TOP_MARGIN, DungeonTrainHudSeam.nextFreeY(true, 0, 10));
    }

    @Test
    void seam_sitsDirectlyUnderTheLastLine() {
        int pitch = LINE_HEIGHT + DungeonTrainHudSeam.DT_LINE_GAP;
        assertEquals(DevHudStack.TOP_MARGIN + pitch + DevHudStack.STACK_GAP,
                DungeonTrainHudSeam.nextFreeY(true, 1, pitch));
        assertEquals(DevHudStack.TOP_MARGIN + 6 * pitch + DevHudStack.STACK_GAP,
                DungeonTrainHudSeam.nextFreeY(true, 6, pitch));
    }
}
