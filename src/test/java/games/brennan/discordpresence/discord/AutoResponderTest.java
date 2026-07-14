package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void cooldownFlooredAtThirtySeconds() {
        assertEquals(30, AutoResponder.effectiveCooldownSeconds(0));
        assertEquals(30, AutoResponder.effectiveCooldownSeconds(10));
        assertEquals(30, AutoResponder.effectiveCooldownSeconds(30));
        assertEquals(300, AutoResponder.effectiveCooldownSeconds(300));
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

    // --- compose (alone whisper template) ---

    @Test
    void composeSubstitutesAllSlots() {
        assertEquals("Steve whispers into the darkness, is anyone there?",
                AutoResponder.compose("{player} {verb} into the {place}, {phrase}",
                        "Steve", "whispers", "darkness", "is anyone there?"));
    }

    @Test
    void composeNullTemplateReturnsNull() {
        assertNull(AutoResponder.compose(null, "Steve", "a", "b", "c"));
    }

    // --- translationKey: localized-pool key selection ---

    @Test
    void translationKeyIndexesFromBase() {
        assertEquals("base.0", AutoResponder.translationKey("base", 0, 3));
        assertEquals("base.1", AutoResponder.translationKey("base", 1, 3));
        assertEquals("base.2", AutoResponder.translationKey("base", 2, 3));
    }

    @Test
    void translationKeyWrapsRollModuloCount() {
        assertEquals("base.0", AutoResponder.translationKey("base", 3, 3)); // wraps
        assertEquals("base.1", AutoResponder.translationKey("base", 7, 3)); // 7 % 3 == 1
    }

    @Test
    void translationKeyHandlesNegativeRoll() {
        assertEquals("base.2", AutoResponder.translationKey("base", -1, 3)); // floorMod(-1, 3) == 2
    }

    @Test
    void translationKeyNullWhenCountNonPositive() {
        assertNull(AutoResponder.translationKey("base", 0, 0));
        assertNull(AutoResponder.translationKey("base", 5, -1));
    }

    // --- count guard: the *_KEY_COUNT constants must match the keys shipped in en_us.json ---

    @Test
    void keyCountsMatchLangFile() {
        String lang = readEnUs();
        assertKeyPoolContiguous(lang, "discordpresence.autoresponse.alone", AutoResponder.ALONE_KEY_COUNT);
        assertKeyPoolContiguous(lang, "discordpresence.autoresponse.group", AutoResponder.GROUP_KEY_COUNT);
        assertKeyPoolContiguous(lang, "discordpresence.autoresponse.mention_hint",
                AutoResponder.MENTION_HINT_KEY_COUNT);
    }

    /** Assert en_us has exactly keys {@code base.0 .. base.<count-1>} and no {@code base.<count>}. */
    private static void assertKeyPoolContiguous(String lang, String base, int count) {
        // Count "base.<int>" keys present, and record the highest index, to catch both drift directions.
        Pattern p = Pattern.compile('"' + Pattern.quote(base) + "\\.(\\d+)\"");
        Matcher m = p.matcher(lang);
        int found = 0;
        int max = -1;
        while (m.find()) {
            found++;
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        assertEquals(count, found, "key count for " + base + " must equal " + base.toUpperCase() + "_KEY_COUNT");
        assertEquals(count - 1, max, "keys for " + base + " must be contiguous 0.." + (count - 1));
    }

    private static String readEnUs() {
        try (InputStream in = AutoResponderTest.class.getResourceAsStream(
                "/assets/discordpresence/lang/en_us.json")) {
            assertNotNull(in, "en_us.json must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- hasActiveDiscordConversation: the engaged-only gate's "Discord is talking to this player" read ---

    @Test
    void noDiscordActivity_notInConversation() {
        AutoResponder ar = new AutoResponder();
        assertFalse(ar.hasActiveDiscordConversation(UUID.randomUUID()));
    }

    @Test
    void recentDiscordActivity_inConversation() {
        AutoResponder ar = new AutoResponder();
        UUID uuid = UUID.randomUUID();
        ar.onDiscordActivity(uuid); // just now → within the default 30-min rearm window
        assertTrue(ar.hasActiveDiscordConversation(uuid));
    }

    // --- default "tag the dev" hint pool ---

    @Test
    void defaultMentionHintPoolNonEmptyAndTemplated() {
        List<? extends String> hints = DiscordPresenceConfig.getAutoResponseMentionHintMessages();
        assertFalse(hints.isEmpty());
        for (String hint : hints) {
            assertTrue(hint.contains("{player}"), "hint should contain {player}: " + hint);
            assertTrue(hint.contains("@dev"), "hint should suggest tagging @dev: " + hint);
        }
    }
}
