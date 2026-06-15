package games.brennan.discordpresence.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure client chat-tag token logic (no Minecraft runtime; CLIENT config unloaded → defaults apply). */
class ClientChatTagsTest {

    @Test
    void tokensDefaultNonEmptyAndPrefixed() {
        List<String> tokens = ClientChatTags.tokens();
        assertFalse(tokens.isEmpty());
        for (String t : tokens) {
            assertTrue(t.startsWith("@"), "token should start with @: " + t);
        }
    }

    @Test
    void augmentAppendsTokensToVanilla() {
        List<String> vanilla = List.of("Alice", "Bob");
        List<String> tokens = ClientChatTags.tokens();
        List<String> out = new ArrayList<>();
        ClientChatTags.augment(vanilla).forEach(out::add);
        assertTrue(out.containsAll(vanilla), "vanilla candidates preserved");
        assertTrue(out.containsAll(tokens), "configured tokens appended");
        assertEquals(vanilla.size() + tokens.size(), out.size());
    }

    @Test
    void augmentDefaultsIncludeDev() {
        List<String> out = new ArrayList<>();
        ClientChatTags.augment(List.of()).forEach(out::add);
        assertTrue(out.contains("@dev"), "default tokens include @dev");
    }

    // --- shouldAutoShow: auto-open the popup only while typing an @tag in plain chat ---

    @Test
    void autoShowWhileTypingTag() {
        assertTrue(ClientChatTags.shouldAutoShow("@", 1));
        assertTrue(ClientChatTags.shouldAutoShow("@d", 2));
        assertTrue(ClientChatTags.shouldAutoShow("hi @de", 6));
        assertTrue(ClientChatTags.shouldAutoShow("@dev", 2)); // cursor mid-tag
    }

    @Test
    void noAutoShowForPlainWordsCommandsSpacesOrEmpty() {
        assertFalse(ClientChatTags.shouldAutoShow("hello", 5));     // ordinary word
        assertFalse(ClientChatTags.shouldAutoShow("/msg", 4));      // command
        assertFalse(ClientChatTags.shouldAutoShow("", 0));          // empty
        assertFalse(ClientChatTags.shouldAutoShow("hi @dev ", 8));  // cursor after a trailing space
    }
}
