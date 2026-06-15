package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure mention-trigger parsing, matching, ping rewriting + allow-list (no Minecraft runtime needed). */
class MentionTriggerTest {

    // --- parse (the <@id> convention, matching the trusted join-suffix) ---

    @Test
    void parsesLiteralMentionValue() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@342110421114945537>"));
        assertEquals(1, ts.size());
        assertEquals("@dev", ts.get(0).token());
        assertEquals("342110421114945537", ts.get(0).userId());
    }

    @Test
    void parsesBareIdAndNicknameMention() {
        assertEquals("123", MentionTrigger.parse(List.of("@dev=123")).get(0).userId());
        assertEquals("456", MentionTrigger.parse(List.of("@dev=<@!456>")).get(0).userId()); // nickname form
    }

    @Test
    void gateOnlyWhenNoIdOrMalformed() {
        assertNull(MentionTrigger.parse(List.of("@vip")).get(0).userId());         // no '='
        assertNull(MentionTrigger.parse(List.of("@dev=notanid")).get(0).userId()); // malformed → gate-only
    }

    @Test
    void skipsBlankEntriesAndTrims() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("", "   ", "  @dev = <@7> "));
        assertEquals(1, ts.size());
        assertEquals("@dev", ts.get(0).token());
        assertEquals("7", ts.get(0).userId());
    }

    @Test
    void dedupesByTokenCaseInsensitiveKeepingFirst() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@111>", "@DEV=<@222>"));
        assertEquals(1, ts.size());
        assertEquals("111", ts.get(0).userId()); // first occurrence wins (admin config over provider)
    }

    @Test
    void parseEmptyOrNull() {
        assertTrue(MentionTrigger.parse(List.of()).isEmpty());
        assertTrue(MentionTrigger.parse(null).isEmpty());
    }

    // --- matches (case-insensitive substring) ---

    @Test
    void matchesCaseInsensitiveSubstring() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@1>"));
        assertTrue(MentionTrigger.matches("hey @DeV please help", ts));
        assertTrue(MentionTrigger.matches("@dev", ts));
        assertFalse(MentionTrigger.matches("no mention here", ts));
        assertFalse(MentionTrigger.matches("", ts));
    }

    @Test
    void matchesFalseWhenNoTriggers() {
        assertFalse(MentionTrigger.matches("@dev", List.of()));
    }

    // --- applyPings ---

    @Test
    void rewritesTokenToDiscordMention() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@342110421114945537>"));
        assertEquals("hi <@342110421114945537> there",
                MentionTrigger.applyPings("hi @dev there", ts));
    }

    @Test
    void rewriteIsCaseInsensitive() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@123>"));
        assertEquals("<@123>", MentionTrigger.applyPings("@DEV", ts));
    }

    @Test
    void gateOnlyTokenLeftAsPlainText() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@vip"));
        assertEquals("yo @vip", MentionTrigger.applyPings("yo @vip", ts));
    }

    @Test
    void applyPingsNoMatchUnchanged() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@123>"));
        assertEquals("nothing here", MentionTrigger.applyPings("nothing here", ts));
    }

    // --- pingUserIds (only configured triggers ping; distinct) ---

    @Test
    void collectsIdsOfPresentPingTokensDistinct() {
        List<MentionTrigger> ts = MentionTrigger.parse(List.of("@dev=<@111>", "@bren=<@222>", "@vip"));
        assertEquals(List.of("111"), MentionTrigger.pingUserIds("ping @dev", ts));
        assertEquals(List.of("111", "222"), MentionTrigger.pingUserIds("@dev and @bren", ts));
        assertTrue(MentionTrigger.pingUserIds("@vip only", ts).isEmpty()); // gate-only, no id
        assertTrue(MentionTrigger.pingUserIds("nobody", ts).isEmpty());
    }
}
