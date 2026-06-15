package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure chat-tag highlight masking (no Minecraft runtime needed for the mask; component text is checked). */
class ChatTagHighlighterTest {

    @Test
    void masksTokenCaseInsensitive() {
        boolean[] m = ChatTagHighlighter.highlightMask("hi @Dev there", List.of("@dev"));
        assertFalse(m[0]);                                  // 'h'
        assertTrue(m[3] && m[4] && m[5] && m[6]);           // "@Dev"
        assertFalse(m[7]);                                  // space after
    }

    @Test
    void masksMultipleTokensAndOccurrences() {
        List<String> tokens = List.of("@dev", "@bren");
        String text = "@dev and @bren and @dev";
        assertTrue(ChatTagHighlighter.hasMatch(text, tokens));
        boolean[] m = ChatTagHighlighter.highlightMask(text, tokens);
        assertTrue(m[0] && m[3]);    // first @dev
        assertTrue(m[9] && m[13]);   // @bren
        assertTrue(m[19] && m[22]);  // last @dev
        assertFalse(m[4]);           // space
    }

    @Test
    void noMatchIsAllFalse() {
        assertFalse(ChatTagHighlighter.hasMatch("nothing here", List.of("@dev")));
        for (boolean b : ChatTagHighlighter.highlightMask("nothing here", List.of("@dev"))) {
            assertFalse(b);
        }
    }

    @Test
    void emptyOrNullInputs() {
        assertEquals(0, ChatTagHighlighter.highlightMask("", List.of("@dev")).length);
        assertEquals(0, ChatTagHighlighter.highlightMask(null, List.of("@dev")).length);
        assertFalse(ChatTagHighlighter.hasMatch("@dev", List.of()));
        assertFalse(ChatTagHighlighter.hasMatch("@dev", null));
    }

    @Test
    void toComponentPreservesText() {
        // Colour lives in styles; the visible string is unchanged.
        assertEquals("hi @dev there",
                ChatTagHighlighter.toComponent("hi @dev there", List.of("@dev")).getString());
        assertEquals("", ChatTagHighlighter.toComponent("", List.of("@dev")).getString());
    }
}
