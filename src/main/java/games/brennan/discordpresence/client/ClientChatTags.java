package games.brennan.discordpresence.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side source of chat-tag autocomplete tokens (e.g. {@code @dev}) for the chat box, read by
 * {@code CommandSuggestionsMixin}. Tokens come from
 * {@link DiscordPresenceClientConfig#getChatTagSuggestions()} (a CLIENT config list) — the client,
 * especially on a dedicated server, cannot read the SERVER {@code gameRelayMentions}.
 *
 * <p>Pure and free of Minecraft types, so it is unit-testable; the merge logic lives here rather than
 * in the mixin.</p>
 */
public final class ClientChatTags {

    private ClientChatTags() {}

    /** The configured chat-tag tokens, trimmed and with blanks dropped. */
    public static List<String> tokens() {
        List<String> out = new ArrayList<>();
        for (String s : DiscordPresenceClientConfig.getChatTagSuggestions()) {
            if (s != null) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * Vanilla's chat-suggestion candidates plus our configured tokens. Returns {@code vanilla}
     * unchanged when there are no tokens. The mixin hands the result to
     * {@code SharedSuggestionProvider.suggest(...)}, which does the prefix-matching + rendering, so a
     * token only surfaces when the word being typed is a prefix of it (tokens start with {@code @},
     * so they never collide with player-name completion).
     */
    public static Iterable<String> augment(Iterable<String> vanilla) {
        List<String> tokens = tokens();
        if (tokens.isEmpty()) {
            return vanilla;
        }
        List<String> merged = new ArrayList<>();
        if (vanilla != null) {
            for (String s : vanilla) {
                merged.add(s);
            }
        }
        merged.addAll(tokens);
        return merged;
    }

    /**
     * Whether the suggestion popup should auto-open for the chat box right now: plain chat (not a
     * {@code /}command) where the word at the cursor begins a tag ({@code @…}) and at least one token
     * is configured. Vanilla auto-opens the popup only for commands; this lets {@code @tags} do the
     * same without the player pressing Tab, while leaving ordinary chat words untouched.
     */
    public static boolean shouldAutoShow(String value, int cursor) {
        if (value == null || value.startsWith("/") || tokens().isEmpty()) {
            return false;
        }
        int c = Math.max(0, Math.min(cursor, value.length()));
        int start = c;
        while (start > 0 && !Character.isWhitespace(value.charAt(start - 1))) {
            start--;
        }
        return c > start && value.charAt(start) == '@';
    }
}
