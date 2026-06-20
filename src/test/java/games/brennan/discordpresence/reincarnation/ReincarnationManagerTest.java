package games.brennan.discordpresence.reincarnation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bridge's pure gating decision + its inert-reason reporting (no Minecraft / network). */
class ReincarnationManagerTest {

    @Test
    void bridgesOnlyWhenAllGatesHold() {
        assertTrue(ReincarnationManager.shouldBridge(true, true, true, true));
        assertFalse(ReincarnationManager.shouldBridge(false, true, true, true)); // config off
        assertFalse(ReincarnationManager.shouldBridge(true, false, true, true)); // PlayerMob absent
        assertFalse(ReincarnationManager.shouldBridge(true, true, false, true)); // not relay-mode
        assertFalse(ReincarnationManager.shouldBridge(true, true, true, false)); // network not permitted
    }

    @Test
    void inertReasonReportsTheFirstFailingGate() {
        assertEquals("disabled by config", ReincarnationManager.inertReason(false, true, true, true));
        assertEquals("PlayerMob not installed", ReincarnationManager.inertReason(true, false, true, true));
        assertEquals("not in relay-mode — no cross-world pool",
                ReincarnationManager.inertReason(true, true, false, true));
        assertEquals("network not permitted", ReincarnationManager.inertReason(true, true, true, false));
        assertEquals("active", ReincarnationManager.inertReason(true, true, true, true));
    }

    @Test
    void configGateIsCheckedBeforePlayerMobPresence() {
        // When DP's own toggle is off, that's the reported reason even if PlayerMob is also missing.
        assertEquals("disabled by config", ReincarnationManager.inertReason(false, false, false, false));
    }
}
