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
 * Durable map of player UUID → their persistent Discord thread id, so a player's
 * thread is reused across server restarts.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-threads.json}) in the
 * server config dir. Loaded once on server start; every {@link #put} writes
 * through immediately (via a tmp file + move). Thread ids are not secrets.</p>
 *
 * <p>Best-effort: a missing or corrupt file simply yields an empty map (players
 * get fresh threads) — never throws into game logic. Writes happen on the
 * Discord daemon executor (the {@code put} callers run there), so they never
 * touch the server thread; reads/loads run on the server thread at startup,
 * before any player can join.</p>
 */
final class DiscordThreadStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, String> threads = new ConcurrentHashMap<>();
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
                try {
                    threads.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
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

    /** The player's persisted thread id, or {@code null} if none. */
    String get(UUID uuid) {
        return threads.get(uuid);
    }

    /** Record the player's thread id and write through to disk (best-effort). */
    void put(UUID uuid, String threadId) {
        threads.put(uuid, threadId);
        save();
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, String> e : threads.entrySet()) {
                obj.addProperty(e.getKey().toString(), e.getValue());
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
