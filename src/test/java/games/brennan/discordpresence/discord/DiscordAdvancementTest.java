package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the advancement filter + message templating. */
class DiscordAdvancementTest {

    @Test
    void onlyDisplaySkipsNoDisplayAdvancements() {
        // Recipe/hidden advancements have no display info → skipped when onlyDisplay is on.
        assertFalse(DiscordService.shouldPostAdvancement("minecraft", false, Set.of(), true));
        assertTrue(DiscordService.shouldPostAdvancement("minecraft", true, Set.of(), true));
    }

    @Test
    void onlyDisplayOffAllowsNoDisplay() {
        assertTrue(DiscordService.shouldPostAdvancement("minecraft", false, Set.of(), false));
    }

    @Test
    void emptyNamespacesAllowsAll() {
        assertTrue(DiscordService.shouldPostAdvancement("anything", true, Set.of(), true));
        assertTrue(DiscordService.shouldPostAdvancement("dungeontrain", true, Set.of(), true));
    }

    @Test
    void namespaceFilterRestrictsToConfigured() {
        Set<String> only = Set.of("dungeontrain");
        assertTrue(DiscordService.shouldPostAdvancement("dungeontrain", true, only, true));
        assertFalse(DiscordService.shouldPostAdvancement("minecraft", true, only, true));
    }

    @Test
    void formatsPlayerAndAdvancement() {
        assertEquals("Dev earned **Stone Age**",
                DiscordService.formatAdvancement("{player} earned **{advancement}**", "Dev", "Stone Age"));
    }

    @Test
    void formatReplacesPlayer() {
        assertEquals("🎮 **Dev** started the game",
                DiscordService.format("🎮 **{player}** started the game", "Dev"));
    }
}
