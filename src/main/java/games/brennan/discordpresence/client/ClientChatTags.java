package games.brennan.discordpresence.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Client-side source of chat-tag autocomplete tokens (e.g. {@code @dev}) for the chat box, read by
 * {@code CommandSuggestionsMixin}. Tokens come from
 * {@link DiscordPresenceClientConfig#getChatTagSuggestions()} (a CLIENT config list) — the client,
 * especially on a dedicated server, cannot read the SERVER {@code gameRelayMentions}.
 *
 * <p>Some tokens are <b>localized for display</b>: a base token (e.g. {@code @dev}) maps to a lang
 * key, so a zh_cn client's popup / highlight shows the translated alias (e.g. {@code @开发者}) while
 * an English client keeps {@code @dev}. The server still matches the raw substring it receives, so
 * the bundling mod's provider must accept the localized alias too — the client only chooses which
 * alias to <i>show</i>. The locale lookup is passed in as a {@link UnaryOperator} (an {@code I18n}
 * facade at the mixin boundary), keeping this class free of Minecraft types and unit-testable.</p>
 */
public final class ClientChatTags {

    private ClientChatTags() {}

    /**
     * Base token → lang key for the localized display alias. Only these tokens are translated; any
     * other configured token (including {@code @brennanhatton}, a proper name) is shown as-is. Keyed
     * lower-case for case-insensitive lookup.
     */
    private static final Map<String, String> TOKEN_LANG_KEYS =
            Map.of("@dev", "discordpresence.chattag.dev");

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
     * The tokens to <b>display</b> in the popup for the client's active locale: each configured token,
     * with a localizable base token (e.g. {@code @dev}) replaced by its translated alias (e.g.
     * {@code @开发者}). {@code translate} maps a lang key to its resolved string (echoing the key back
     * when there is no translation); an alias is only used when it is non-blank, starts with
     * {@code @}, and is not the key echoed back — otherwise the base token is kept. Tokens without a
     * lang key (including {@code @brennanhatton}) are returned unchanged.
     */
    public static List<String> displayTokens(UnaryOperator<String> translate) {
        List<String> out = new ArrayList<>();
        for (String token : tokens()) {
            out.add(localize(token, translate));
        }
        return out;
    }

    /**
     * The tokens to <b>highlight</b> in the input box / chat line: the order-preserving union of the
     * base {@link #tokens()} and their {@link #displayTokens(UnaryOperator) localized aliases}, so a
     * player typing either form (e.g. {@code @dev} or {@code @开发者}) is coloured even though only
     * the localized alias is offered in the popup.
     */
    public static List<String> highlightTokens(UnaryOperator<String> translate) {
        Set<String> merged = new LinkedHashSet<>(tokens());
        merged.addAll(displayTokens(translate));
        return new ArrayList<>(merged);
    }

    /** The localized display alias for a single token, or the token unchanged. See {@link #displayTokens}. */
    private static String localize(String token, UnaryOperator<String> translate) {
        if (token == null || translate == null) {
            return token;
        }
        String key = TOKEN_LANG_KEYS.get(token.toLowerCase(Locale.ROOT));
        if (key == null) {
            return token;
        }
        String alias = translate.apply(key);
        if (alias == null) {
            return token;
        }
        alias = alias.trim();
        if (alias.isEmpty() || !alias.startsWith("@") || alias.equals(key)) {
            return token;
        }
        return alias;
    }

    /**
     * Vanilla's chat-suggestion candidates plus our configured tokens. Returns {@code vanilla}
     * unchanged when there are no tokens. The mixin hands the result to
     * {@code SharedSuggestionProvider.suggest(...)}, which does the prefix-matching + rendering, so a
     * token only surfaces when the word being typed is a prefix of it (tokens start with {@code @},
     * so they never collide with player-name completion).
     */
    public static Iterable<String> augment(Iterable<String> vanilla) {
        return augment(vanilla, tokens());
    }

    /**
     * Like {@link #augment(Iterable)} but with an explicit token list, so the mixin can supply the
     * locale-specific {@link #displayTokens(UnaryOperator) display tokens}. Returns {@code vanilla}
     * unchanged when {@code tokens} is empty.
     */
    public static Iterable<String> augment(Iterable<String> vanilla, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
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
