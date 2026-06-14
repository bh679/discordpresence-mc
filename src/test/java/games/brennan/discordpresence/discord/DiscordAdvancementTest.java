package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void buildsVanillaIconUrlFromPath() {
        // The default vanilla CDN template uses only {path}.
        assertEquals("https://static.minecraftitemids.com/64/stone.png",
                DiscordService.advancementIconUrl(
                        "https://static.minecraftitemids.com/64/{path}.png", "minecraft", "stone"));
    }

    @Test
    void iconUrlSubstitutesNamespaceAndPath() {
        // A modded override template uses both placeholders.
        assertEquals("https://host/dungeontrain/relic.png",
                DiscordService.advancementIconUrl(
                        "https://host/{namespace}/{path}.png", "dungeontrain", "relic"));
    }

    @Test
    void iconUrlNullWhenTemplateBlank() {
        assertNull(DiscordService.advancementIconUrl("", "minecraft", "stone"));
        assertNull(DiscordService.advancementIconUrl("   ", "minecraft", "stone"));
    }

    @Test
    void iconUrlNullWhenPathBlank() {
        assertNull(DiscordService.advancementIconUrl("https://host/{path}.png", "minecraft", ""));
    }

    // --- advancement requirements field --------------------------------------

    @Test
    void requirementsListsNovelCriteriaSortedAndCapped() {
        // Description names none of the sub-goals → all are "novel"; sorted, capped at 2 with "+N more".
        List<String> keys = List.of("minecraft:badlands", "birch_forest", "beach", "desert");
        assertEquals("Badlands, Beach, +2 more",
                DiscordService.advancementRequirements(keys, "Discover all biomes", 2));
    }

    @Test
    void requirementsFilterOmitsCriteriaAlreadyInDescription() {
        // "Desert" is named in the description (case-insensitive) → dropped; "Badlands" survives.
        List<String> keys = List.of("badlands", "desert");
        assertEquals("Badlands",
                DiscordService.advancementRequirements(keys, "Visit the Desert biome", 10));
    }

    @Test
    void requirementsNullWhenAllCriteriaInDescription() {
        assertNull(DiscordService.advancementRequirements(List.of("desert"), "Reach the desert", 10));
    }

    @Test
    void requirementsNullWhenEmptyOrNull() {
        assertNull(DiscordService.advancementRequirements(Set.of(), "anything", 10));
        assertNull(DiscordService.advancementRequirements(null, "anything", 10));
    }

    @Test
    void requirementsNoSuffixAtCapBoundary() {
        List<String> keys = List.of("apple", "bread", "carrot");
        // size == max → no "+more"
        assertEquals("Apple, Bread, Carrot",
                DiscordService.advancementRequirements(keys, "", 3));
        // size == max + 1 → "+1 more"
        assertEquals("Apple, Bread, +1 more",
                DiscordService.advancementRequirements(keys, "", 2));
    }

    @Test
    void requirementsKeepsShortNamesThatCannotMatchReliably() {
        // "tnt" (3 chars) is filtered when present; "a" (1 char) is always kept (too short to match).
        List<String> keys = List.of("a", "tnt");
        assertEquals("A",
                DiscordService.advancementRequirements(keys, "uses a and tnt", 10));
    }

    @Test
    void prettifyCriterionStripsNamespacePathAndTitleCases() {
        assertEquals("Birch Forest", DiscordService.prettifyCriterion("minecraft:birch_forest"));
        assertEquals("Kill A Mob", DiscordService.prettifyCriterion("minecraft:adventure/kill_a_mob"));
        assertEquals("Get Stone", DiscordService.prettifyCriterion("get_stone"));
    }

    @Test
    void prettifyCriterionBlankForNullOrEmpty() {
        assertEquals("", DiscordService.prettifyCriterion(null));
        assertEquals("", DiscordService.prettifyCriterion("   "));
    }

    @Test
    void normalizeForMatchLowercasesCollapsesAndTrims() {
        assertEquals("birch forest", DiscordService.normalizeForMatch("  Birch__Forest!! "));
        assertEquals("", DiscordService.normalizeForMatch(null));
    }
}
