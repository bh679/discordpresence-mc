package games.brennan.discordpresence.discord;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of pending account-link codes ({@code code → player UUID}
 * with an expiry). A player runs {@code /discordpresence link} in-game to mint a
 * code, then posts it in the Discord link channel; the poller in
 * {@link LinkService} matches the posted text back to a UUID here.
 *
 * <p>Properties that make the public-channel flow safe enough for the threat
 * model (a small game server):</p>
 * <ul>
 *   <li><b>Single-use</b> — {@link #consume} removes the code, so a sniped code
 *       can't be reused.</li>
 *   <li><b>Short-lived</b> — codes carry an absolute expiry; {@link #pruneExpired}
 *       drops stale ones.</li>
 *   <li><b>One per player</b> — {@link #issue} replaces a player's previous code,
 *       bounding spam and keeping the pending set small.</li>
 *   <li><b>Unguessable</b> — {@link SecureRandom} over an unambiguous charset.</li>
 * </ul>
 *
 * <p>Both the server thread (command) and the poll thread touch this, so the
 * compound mutators are {@code synchronized} over a {@link ConcurrentHashMap}.
 * The {@code static} helpers ({@link #generate}/{@link #match}) are pure and
 * unit-tested; instance methods take an explicit {@code nowMs} so expiry is
 * testable without a clock.</p>
 */
final class LinkCodes {

    /** No 0/O/1/I/L — unambiguous when read off a screen and typed into Discord. */
    static final String CHARSET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    private static final int UNIQUE_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** A code's owner and the epoch-millis instant it stops being valid. */
    record Pending(UUID uuid, long expiresAtMs) {}

    /** Mint a fresh code for {@code uuid}, replacing any prior pending code of theirs. */
    synchronized String issue(UUID uuid, long nowMs, long ttlMs) {
        pending.entrySet().removeIf(e -> e.getValue().uuid().equals(uuid));
        String code = uniqueCode();
        pending.put(code, new Pending(uuid, nowMs + ttlMs));
        return code;
    }

    /**
     * If {@code content} carries a valid, non-expired code, consume it (single-use)
     * and return the owning UUID; otherwise {@code null}.
     */
    synchronized UUID consume(String content, long nowMs) {
        String code = match(content, pending.keySet());
        if (code == null) {
            return null;
        }
        Pending p = pending.remove(code);
        if (p == null || p.expiresAtMs() <= nowMs) {
            return null; // expired between match and removal — treat as miss
        }
        return p.uuid();
    }

    /** Drop every code whose expiry has passed. */
    synchronized void pruneExpired(long nowMs) {
        pending.entrySet().removeIf(e -> e.getValue().expiresAtMs() <= nowMs);
    }

    /** Whether any code is currently pending (drives the poller's start/stop). */
    boolean hasPending() {
        return !pending.isEmpty();
    }

    void clear() {
        pending.clear();
    }

    private String uniqueCode() {
        for (int i = 0; i < UNIQUE_ATTEMPTS; i++) {
            String c = generate(random);
            if (!pending.containsKey(c)) {
                return c;
            }
        }
        return generate(random); // astronomically unlikely to still collide
    }

    // --- pure helpers (unit-tested) ---------------------------------------

    /** A {@code CODE_LENGTH} code drawn from {@link #CHARSET} using {@code rnd}. */
    static String generate(Random rnd) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARSET.charAt(rnd.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }

    /**
     * The first known code that appears as a whitespace-delimited token in
     * {@code content} (case-insensitive), or {@code null}. Token matching lets a
     * player post "my code is ABC123" — the codes' length + charset make an
     * accidental token collision negligible.
     */
    static String match(String content, Set<String> codes) {
        if (content == null || content.isBlank() || codes.isEmpty()) {
            return null;
        }
        for (String token : content.strip().toUpperCase(Locale.ROOT).split("\\s+")) {
            if (codes.contains(token)) {
                return token;
            }
        }
        return null;
    }

    /** Test-only view of the live pending map size. */
    int pendingCount() {
        return pending.size();
    }
}
