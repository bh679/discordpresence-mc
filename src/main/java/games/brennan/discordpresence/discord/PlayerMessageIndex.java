package games.brennan.discordpresence.discord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reverse index mapping a Discord {@code messageId → playerUUID} for every
 * message the mod posts on a player's behalf (the join message and each relayed
 * chat line). It is the complement of {@link DiscordService}'s per-UUID map and
 * the routing key for inbound chat: a Discord <b>reply</b> to one of these ids —
 * or a message in the <b>thread</b> spun off it (a message-thread's id equals its
 * source message id) — is what gets relayed back into the game.
 *
 * <p>Bounded LRU (access-order {@link LinkedHashMap} with {@code removeEldestEntry}),
 * so a long-running server with heavy chat can never grow it without limit; the
 * oldest, least-referenced messages fall out first. Wrapped in
 * {@link Collections#synchronizedMap} because writers (the HTTP executor thread,
 * on webhook completion) and the reader (the gateway WebSocket thread) differ.</p>
 */
final class PlayerMessageIndex {

    /** Cap on tracked messages; ~100 bytes/entry, so a few hundred KB worst case. */
    static final int DEFAULT_CAPACITY = 1000;

    private final Map<String, UUID> map;

    PlayerMessageIndex() {
        this(DEFAULT_CAPACITY);
    }

    PlayerMessageIndex(int capacity) {
        this.map = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UUID> eldest) {
                return size() > capacity;
            }
        });
    }

    void put(String messageId, UUID player) {
        if (messageId != null && player != null) {
            map.put(messageId, player);
        }
    }

    /** @return the player a tracked message belongs to, or {@code null} if untracked. Counts as an access (refreshes LRU). */
    UUID get(String messageId) {
        return messageId == null ? null : map.get(messageId);
    }

    boolean contains(String messageId) {
        return get(messageId) != null;
    }

    int size() {
        return map.size();
    }

    void clear() {
        map.clear();
    }
}
