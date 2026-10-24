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

    @Test
    void inboundBandIsWideEnoughNotToRunDry() {
        // PlayerMob keeps up to 20 eligible lives per band (after dropping met + Free Play mismatches);
        // a band of 5 ran dry on quiet stretches. The relay caps a query at 50.
        assertEquals(20, ReincarnationManager.INBOUND_LIMIT);
        assertTrue(ReincarnationManager.INBOUND_LIMIT <= 50, "relay MAX_LIMIT is 50");
        String url = RelayReincarnationClient.buildQueryUrl("https://relay.example", 12, 30, null,
                ReincarnationManager.INBOUND_LIMIT);
        assertTrue(url.contains("&limit=20"), url);
    }
}
