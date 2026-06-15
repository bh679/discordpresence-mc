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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable map of player UUID → their live <b>online presence</b>: the message that carries
 * this session's online reaction (parent {@code channelId} + {@code messageId}) plus the
 * epoch-millis the reaction was last refreshed ({@code lastSeen}). Lets the green "online"
 * reaction survive a crash: after an unclean shutdown the in-memory session state is gone,
 * but this on-disk record still names the message whose stale reaction must be removed.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-presence.json}) in the server config
 * dir: {@code { "<uuid>": { "channelId": "...", "messageId": "...", "lastSeen": 1718… } }}.
 * Loaded once on server start; mutating calls write through immediately (tmp file + atomic
 * move). These ids are not secrets. Structurally a sibling of {@link DiscordThreadStore}.</p>
 *
 * <p>Best-effort: a missing or corrupt file simply yields an empty map — never throws into
 * game logic. Writes happen on the Discord daemon executor (the callers run there); the
 * initial {@link #load} runs on the server thread at startup, before any player can join.</p>
 */
final class OnlinePresenceStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One player's online presence: the reaction-bearing message + when it was last refreshed. */
    record PresenceEntry(DiscordMessageRef ref, long lastSeen) {}

    private final ConcurrentHashMap<UUID, PresenceEntry> presence = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    void load(Path file) {
        this.file = file;
        presence.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no online-presence store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                PresenceEntry entry = parseEntry(e.getValue());
                if (entry == null) {
                    LOGGER.warn("Discord Presence: skipping invalid presence entry '{}'.", e.getKey());
                    continue;
                }
                try {
                    presence.put(UUID.fromString(e.getKey()), entry);
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid presence entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded {} persisted online-presence entr(ies).", presence.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read presence store {}; starting fresh.", file, e);
            presence.clear();
        }
    }

    /** A snapshot copy of all current entries — safe to iterate while the live map mutates. */
    Map<UUID, PresenceEntry> entries() {
        return new HashMap<>(presence);
    }

    boolean isEmpty() {
        return presence.isEmpty();
    }

    /** Record/refresh a player as online on {@code ref} and write through (best-effort). */
    void recordOnline(UUID uuid, DiscordMessageRef ref, long now) {
        if (uuid == null || ref == null) {
            return;
        }
        presence.put(uuid, new PresenceEntry(ref, now));
        save();
    }

    /** Drop a player (they went offline) and write through if anything changed. */
    void markOffline(UUID uuid) {
        if (uuid != null && presence.remove(uuid) != null) {
            save();
        }
    }

    /** Refresh {@code lastSeen} for every still-connected player that has an entry, in one write. */
    void touch(Collection<UUID> connected, long now) {
        if (connected == null || connected.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (UUID uuid : connected) {
            PresenceEntry e = presence.get(uuid);
            if (e != null) {
                presence.put(uuid, new PresenceEntry(e.ref(), now));
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /**
     * Drop entries that are still <b>unchanged</b> since {@code stale} was snapshotted, in one
     * write. Compare-and-remove ({@link ConcurrentHashMap#remove(Object, Object)}): if a player
     * reconnected after the snapshot — replacing their entry with a fresh one — their key is left
     * intact, so a reconcile can never clobber a live session. {@link PresenceEntry} is a record,
     * so equality is by (ref, lastSeen).
     */
    void dropStale(Map<UUID, PresenceEntry> stale) {
        if (stale == null || stale.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<UUID, PresenceEntry> e : stale.entrySet()) {
            if (presence.remove(e.getKey(), e.getValue())) {
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Parse one stored value: {@code {channelId, messageId, lastSeen}}. Returns {@code null} when unusable. */
    private static PresenceEntry parseEntry(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        String channelId = o.has("channelId") && !o.get("channelId").isJsonNull()
                ? o.get("channelId").getAsString() : null;
        String messageId = o.has("messageId") && !o.get("messageId").isJsonNull()
                ? o.get("messageId").getAsString() : null;
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        long lastSeen = 0L;
        if (o.has("lastSeen") && o.get("lastSeen").isJsonPrimitive()) {
            try {
                lastSeen = o.get("lastSeen").getAsLong();
            } catch (NumberFormatException ignored) {
                lastSeen = 0L;
            }
        }
        return new PresenceEntry(new DiscordMessageRef(channelId, messageId), lastSeen);
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, PresenceEntry> e : presence.entrySet()) {
                PresenceEntry entry = e.getValue();
                DiscordMessageRef ref = entry.ref();
                JsonObject o = new JsonObject();
                if (ref.channelId() != null) {
                    o.addProperty("channelId", ref.channelId());
                }
                o.addProperty("messageId", ref.messageId());
                o.addProperty("lastSeen", entry.lastSeen());
                obj.add(e.getKey().toString(), o);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write presence store {}.", target, e);
        }
    }
}
