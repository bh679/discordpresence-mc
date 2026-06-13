package games.brennan.discordpresence.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable map of player UUID → their thread's <b>top-level anchor message</b> — the
 * message the thread was started from, which lives in the main channel and whose id
 * equals the thread id. Stored as a {@link DiscordMessageRef} (parent {@code channelId}
 * + {@code messageId}) so the online/death reactions can target that top-level message
 * across server restarts, and so the player's thread is reused.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-threads.json}) in the
 * server config dir: {@code { "<uuid>": { "channelId": "...", "messageId": "..." } }}.
 * Loaded once on server start; every {@link #put} writes through immediately (via a
 * tmp file + move). These ids are not secrets.</p>
 *
 * <p><b>Backward compatible:</b> entries written before the parent channel was
 * persisted are bare thread-id strings ({@code { "<uuid>": "<threadId>" }}); those load
 * as {@code DiscordMessageRef(null, threadId)} (parent channel unknown until backfilled)
 * and self-upgrade to the object form on the next write.</p>
 *
 * <p>Best-effort: a missing or corrupt file simply yields an empty map (players
 * get fresh threads) — never throws into game logic. Writes happen on the
 * Discord daemon executor (the {@code put} callers run there), so they never
 * touch the server thread; reads/loads run on the server thread at startup,
 * before any player can join.</p>
 */
final class DiscordThreadStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, DiscordMessageRef> threads = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    void load(Path file) {
        this.file = file;
        threads.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no thread store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                DiscordMessageRef ref = parseEntry(e.getValue());
                if (ref == null) {
                    LOGGER.warn("Discord Presence: skipping invalid thread-store entry '{}'.", e.getKey());
                    continue;
                }
                try {
                    threads.put(UUID.fromString(e.getKey()), ref);
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid thread-store entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded {} persisted player thread(s).", threads.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read thread store {}; starting fresh.", file, e);
            threads.clear();
        }
    }

    /** The player's persisted anchor ref (parent channel + thread id), or {@code null} if none. */
    DiscordMessageRef get(UUID uuid) {
        return threads.get(uuid);
    }

    /** The player's persisted thread id (== anchor message id), or {@code null} if none. */
    String threadId(UUID uuid) {
        DiscordMessageRef ref = threads.get(uuid);
        return ref == null ? null : ref.messageId();
    }

    /** Record the player's anchor ref and write through to disk (best-effort). */
    void put(UUID uuid, DiscordMessageRef ref) {
        if (ref == null) {
            return;
        }
        threads.put(uuid, ref);
        save();
    }

    /**
     * Parse one stored value into a ref: the new object form
     * {@code {channelId, messageId}}, or the legacy bare thread-id string
     * (parent channel unknown). Returns {@code null} when unusable.
     */
    private static DiscordMessageRef parseEntry(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            String channelId = o.has("channelId") && !o.get("channelId").isJsonNull()
                    ? o.get("channelId").getAsString() : null;
            String messageId = o.has("messageId") && !o.get("messageId").isJsonNull()
                    ? o.get("messageId").getAsString() : null;
            return (messageId == null || messageId.isBlank()) ? null : new DiscordMessageRef(channelId, messageId);
        }
        if (value.isJsonPrimitive()) {
            // Legacy form: a bare thread-id string; parent channel backfilled on first use.
            String threadId = value.getAsString();
            return threadId.isBlank() ? null : new DiscordMessageRef(null, threadId);
        }
        return null;
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, DiscordMessageRef> e : threads.entrySet()) {
                DiscordMessageRef ref = e.getValue();
                JsonObject o = new JsonObject();
                if (ref.channelId() != null) {
                    o.addProperty("channelId", ref.channelId());
                }
                o.addProperty("messageId", ref.messageId());
                obj.add(e.getKey().toString(), o);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write thread store {}.", target, e);
        }
    }
}
