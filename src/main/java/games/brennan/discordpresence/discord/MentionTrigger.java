package games.brennan.discordpresence.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chat-relay mention trigger: a token a player types in-game ({@code @dev}) and, optionally, the
 * Discord user id to ping when they do. Configured as {@code "@dev=<@123…>"} (a literal mention — the
 * same form DP's trusted join-suffix ping uses) or {@code "@dev=123…"} (a bare id); a lone
 * {@code "@dev"} is gate-only (no ping). Drives the engaged-only relay gate in
 * {@link DiscordService#onGameChat}: a message containing any token bypasses the gate, and tokens
 * carrying a {@code userId} are rewritten to a real {@code <@id>} mention so that user is notified
 * (fed to {@code allowed_mentions.users} — a player-typed raw {@code <@id>} never pings).
 *
 * <p>All logic here is pure (no game / config / HTTP), so it is unit-tested directly.</p>
 *
 * @param token  the literal substring matched in chat (e.g. {@code "@dev"}); matching is case-insensitive
 * @param userId the Discord user id to ping, or {@code null} for a gate-only trigger (no ping)
 */
record MentionTrigger(String token, String userId) {

    /** A configured mention value: {@code <@123>} / {@code <@!123>}. Same shape as DiscordService.USER_MENTION. */
    private static final Pattern MENTION_VALUE = Pattern.compile("<@!?(\\d+)>");

    /**
     * Parse config / provider entries into triggers. Each entry is {@code "@token"} (gate-only) or
     * {@code "@token=<@id>"} / {@code "@token=id"} (gate + ping). Blank tokens are skipped; a malformed
     * id (neither {@code <@digits>} nor bare digits) degrades the entry to gate-only. Duplicate tokens
     * (case-insensitive) keep the first occurrence, so admin config can take precedence over a provider.
     */
    static List<MentionTrigger> parse(List<? extends String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, MentionTrigger> byToken = new LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String raw = entry.trim();
            if (raw.isEmpty()) {
                continue;
            }
            String token = raw;
            String userId = null;
            int eq = raw.indexOf('=');
            if (eq >= 0) {
                token = raw.substring(0, eq).trim();
                userId = parseUserId(raw.substring(eq + 1).trim());
            }
            if (token.isEmpty()) {
                continue;
            }
            byToken.putIfAbsent(token.toLowerCase(Locale.ROOT), new MentionTrigger(token, userId));
        }
        return List.copyOf(byToken.values());
    }

    /** The numeric id from a configured value: {@code <@123>} / {@code <@!123>} / bare {@code 123}, else null. */
    private static String parseUserId(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Matcher m = MENTION_VALUE.matcher(value);
        if (m.matches()) {
            return m.group(1);
        }
        return isDigits(value) ? value : null;
    }

    /** Whether {@code text} contains any trigger token (case-insensitive substring). */
    static boolean matches(String text, List<MentionTrigger> triggers) {
        if (text == null || text.isEmpty() || triggers == null || triggers.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (MentionTrigger t : triggers) {
            if (lower.contains(t.token().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrite each pingable trigger token present in {@code text} to a real {@code <@userId>} Discord
     * mention (case-insensitive, literal-token match). Gate-only triggers (no {@code userId}) are left
     * as plain text. Returns {@code text} unchanged when there is nothing to rewrite.
     */
    static String applyPings(String text, List<MentionTrigger> triggers) {
        if (text == null || text.isEmpty() || triggers == null || triggers.isEmpty()) {
            return text;
        }
        String out = text;
        for (MentionTrigger t : triggers) {
            if (t.userId() == null || t.token().isEmpty()) {
                continue;
            }
            Pattern p = Pattern.compile(Pattern.quote(t.token()), Pattern.CASE_INSENSITIVE);
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement("<@" + t.userId() + ">"));
        }
        return out;
    }

    /**
     * The distinct Discord user ids allowed to ping for {@code text}: the {@code userId} of every
     * pingable trigger whose token appears in the line. Feeds {@code allowed_mentions.users} (DP's
     * trusted-mention path), so only configured triggers ping — never a player-typed raw {@code <@id>}.
     */
    static List<String> pingUserIds(String text, List<MentionTrigger> triggers) {
        if (text == null || text.isEmpty() || triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        for (MentionTrigger t : triggers) {
            if (t.userId() != null && lower.contains(t.token().toLowerCase(Locale.ROOT)) && !ids.contains(t.userId())) {
                ids.add(t.userId());
            }
        }
        return List.copyOf(ids);
    }

    /** A non-empty run of ASCII digits. */
    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
