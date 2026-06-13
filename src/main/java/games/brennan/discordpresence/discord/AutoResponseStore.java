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
 * Durable map of player UUID → the epoch-millis of the last Discord reply relayed
 * for that player. It backs the auto-response "armed" decision, so the disarmed
 * state survives restarts and — because it lives in the instance config dir — also
 * carries across worlds for local (singleplayer) games.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-autoresponse.json}) in the
 * config dir. Loaded once on server start; every {@link #put} writes through
 * immediately (tmp file + atomic move). The timestamps are not secrets.</p>
 *
 * <p>Best-effort: a missing or corrupt file simply yields an empty map (players
 * start armed) — never throws into game logic. A {@link #put} can land on the
 * gateway thread (on a relayed Discord reply) while the server thread reads via
 * {@link #get}; the {@link ConcurrentHashMap} + atomic file move keep that safe.
 * Mirrors {@link DiscordThreadStore}.</p>
 */
final class AutoResponseStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    void load(Path file) {
        this.file = file;
        lastActivity.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no auto-response store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try {
                    lastActivity.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid auto-response entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded {} auto-response timestamp(s).", lastActivity.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read auto-response store {}; starting fresh.", file, e);
            lastActivity.clear();
        }
    }

    /** The epoch-millis of the player's last relayed Discord reply, or {@code null} if none. */
    Long get(UUID uuid) {
        return lastActivity.get(uuid);
    }

    /** Record the player's last Discord activity and write through to disk (best-effort). */
    void put(UUID uuid, long epochMillis) {
        lastActivity.put(uuid, epochMillis);
        save();
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, Long> e : lastActivity.entrySet()) {
                obj.addProperty(e.getKey().toString(), e.getValue());
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write auto-response store {}.", target, e);
        }
    }
}
