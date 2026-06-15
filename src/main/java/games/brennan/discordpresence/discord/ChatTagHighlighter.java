package games.brennan.discordpresence.discord;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

/**
 * Highlights configured chat-tag tokens (e.g. {@code @dev}) inside a chat line. The token-span logic
 * ({@link #highlightMask}) is pure and unit-tested; {@link #toComponent} builds the yellow-highlighted
 * in-game broadcast message from it (server-side, so every client — even vanilla — sees it). The client
 * input box builds its own {@code FormattedCharSequence} from {@link #highlightMask} in the chat mixin,
 * keeping client-only render types out of this both-dist class.
 */
public final class ChatTagHighlighter {

    private ChatTagHighlighter() {}

    /**
     * A mask over {@code text}: {@code mask[i]} is true when char {@code i} falls inside any
     * (case-insensitive) occurrence of a token. Empty/blank inputs yield an all-false mask.
     */
    public static boolean[] highlightMask(String text, List<String> tokens) {
        boolean[] mask = new boolean[text == null ? 0 : text.length()];
        if (text == null || text.isEmpty() || tokens == null || tokens.isEmpty()) {
            return mask;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            String t = token.toLowerCase(Locale.ROOT);
            int from = 0;
            int idx;
            while ((idx = lower.indexOf(t, from)) >= 0) {
                for (int i = idx; i < idx + t.length(); i++) {
                    mask[i] = true;
                }
                from = idx + t.length();
            }
        }
        return mask;
    }

    /** Whether {@code text} contains at least one token occurrence. */
    public static boolean hasMatch(String text, List<String> tokens) {
        for (boolean b : highlightMask(text, tokens)) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code text} as a component with token runs coloured {@link ChatFormatting#YELLOW} and the rest
     * left plain. Used for the in-game broadcast message (server-side).
     */
    public static MutableComponent toComponent(String text, List<String> tokens) {
        MutableComponent out = Component.empty();
        if (text == null || text.isEmpty()) {
            return out;
        }
        boolean[] mask = highlightMask(text, tokens);
        int i = 0;
        while (i < text.length()) {
            boolean hi = mask[i];
            int j = i;
            while (j < text.length() && mask[j] == hi) {
                j++;
            }
            String run = text.substring(i, j);
            out.append(hi ? Component.literal(run).withStyle(ChatFormatting.YELLOW) : Component.literal(run));
            i = j;
        }
        return out;
    }
}
