package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure auto-response decision logic (no Minecraft runtime needed). */
class AutoResponderTest {

    private static final long T0 = 1_000_000_000_000L; // a fixed "now"

    // --- isArmed: armed unless a Discord reply landed within rearmMinutes ---

    @Test
    void armedWhenNoPriorActivity() {
        assertTrue(AutoResponder.isArmed(null, T0, 30));
    }

    @Test
    void disarmedWithinRearmWindow() {
        assertFalse(AutoResponder.isArmed(T0 - 5 * 60_000L, T0, 30));
    }

    @Test
    void rearmsAtExactlyRearmMinutes() {
        assertTrue(AutoResponder.isArmed(T0 - 30 * 60_000L, T0, 30));
    }

    @Test
    void rearmsAfterRearmWindow() {
        assertTrue(AutoResponder.isArmed(T0 - 31 * 60_000L, T0, 30));
    }

    // --- isAlone ---

    @Test
    void aloneWhenZeroOrOneOnline() {
        assertTrue(AutoResponder.isAlone(0));
        assertTrue(AutoResponder.isAlone(1));
    }

    @Test
    void notAloneWithOthersOnline() {
        assertFalse(AutoResponder.isAlone(2));
        assertFalse(AutoResponder.isAlone(10));
    }

    // --- cooldownElapsed ---

    @Test
    void cooldownElapsedWhenNoPriorWhisper() {
        assertTrue(AutoResponder.cooldownElapsed(null, T0, 30));
    }

    @Test
    void cooldownNotElapsedWithinWindow() {
        assertFalse(AutoResponder.cooldownElapsed(T0 - 10_000L, T0, 30));
    }

    @Test
    void cooldownElapsedAtBoundary() {
        assertTrue(AutoResponder.cooldownElapsed(T0 - 30_000L, T0, 30));
    }

    @Test
    void zeroCooldownAlwaysElapsed() {
        assertTrue(AutoResponder.cooldownElapsed(T0, T0, 0));
    }

    // --- pickAndFormat ---

    @Test
    void pickFormatsPlayerPlaceholder() {
        List<String> msgs = List.of("{player} whispers into the darkness...");
        assertEquals("Steve whispers into the darkness...",
                AutoResponder.pickAndFormat(msgs, "Steve", 0));
    }

    @Test
    void pickWrapsRollModuloSize() {
        List<String> msgs = List.of("a {player}", "b {player}", "c {player}");
        assertEquals("a Steve", AutoResponder.pickAndFormat(msgs, "Steve", 0));
        assertEquals("b Steve", AutoResponder.pickAndFormat(msgs, "Steve", 1));
        assertEquals("c Steve", AutoResponder.pickAndFormat(msgs, "Steve", 2));
        assertEquals("a Steve", AutoResponder.pickAndFormat(msgs, "Steve", 3)); // wraps
        assertEquals("b Steve", AutoResponder.pickAndFormat(msgs, "Steve", 7)); // 7 % 3 == 1
    }

    @Test
    void pickHandlesNegativeRoll() {
        List<String> msgs = List.of("a", "b", "c");
        assertEquals("c", AutoResponder.pickAndFormat(msgs, "Steve", -1)); // floorMod(-1, 3) == 2
    }

    @Test
    void pickReturnsNullForEmptyOrNull() {
        assertNull(AutoResponder.pickAndFormat(List.of(), "Steve", 0));
        assertNull(AutoResponder.pickAndFormat(null, "Steve", 0));
    }
}
