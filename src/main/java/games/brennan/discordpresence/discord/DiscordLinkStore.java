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
 * Durable map of player UUID → their verified Discord user id, established via the
 * {@code /discordpresence link} flow. The persistent counterpart to the in-memory
 * {@link LinkCodes}: once a code is consumed, the resulting link is recorded here
 * so it survives restarts.
 *
 * <p>Mirrors {@link DiscordThreadStore} exactly — a small JSON file
 * ({@code discordpresence-links.json}) in the server config dir, loaded once on
 * server start, every {@link #put}/{@link #remove} writing through via a tmp file
 * + atomic move. Discord ids are not secrets. Best-effort: a missing or corrupt
 * file yields an empty map and never throws into game logic.</p>
 *
 * <p>{@link #getUuidForDiscord} is the reverse lookup the future inbound path
 * (Discord message → in-game) will use to resolve a Discord author back to a
 * player.</p>
 */
final class DiscordLinkStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, String> links = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    void load(Path file) {
        this.file = file;
        links.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no link store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try {
                    links.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid link-store entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded {} verified account link(s).", links.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read link store {}; starting fresh.", file, e);
            links.clear();
        }
    }

    /** The player's linked Discord user id, or {@code null} if unlinked. */
    String getDiscordId(UUID uuid) {
        return links.get(uuid);
    }

    /** The player UUID linked to {@code discordId}, or {@code null} if none. */
    UUID getUuidForDiscord(String discordId) {
        if (discordId == null) {
            return null;
        }
        for (Map.Entry<UUID, String> e : links.entrySet()) {
            if (e.getValue().equals(discordId)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Record (or overwrite) the player's link and write through to disk. */
    void put(UUID uuid, String discordId) {
        links.put(uuid, discordId);
        save();
    }

    /** Remove the player's link (if any) and write through. Returns whether one existed. */
    boolean remove(UUID uuid) {
        if (links.remove(uuid) != null) {
            save();
            return true;
        }
        return false;
    }

    int size() {
        return links.size();
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, String> e : links.entrySet()) {
                obj.addProperty(e.getKey().toString(), e.getValue());
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write link store {}.", target, e);
        }
    }
}
