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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable map of <b>Discord user id → their last-seen online presence</b>: the user's last known
 * Discord {@code status} ({@code online}/{@code idle}/{@code dnd}/{@code offline}) plus the
 * epoch-millis we last observed them <i>online</i> ({@code lastOnlineMillis}, frozen when they go
 * offline). Fed by the gateway's {@code GUILD_CREATE} presence snapshot + {@code PRESENCE_UPDATE}
 * events (see {@link DiscordGateway}) and queried by {@link DiscordService}'s public seam so a
 * consumer mod can render "last seen online X ago".
 *
 * <p>Backed by a small JSON file ({@code discordpresence-discord-presence.json}) in the server
 * config dir — deliberately distinct from the UUID-keyed {@code discordpresence-presence.json}
 * ({@link OnlinePresenceStore}), which tracks a different thing (the bot's own online reaction).
 * Shape: {@code { "<discordUserId>": { "status": "online", "lastOnlineMillis": 1718… } }}. Loaded
 * once on server start; mutating calls write through immediately (tmp file + atomic move). These
 * ids are not secrets.</p>
 *
 * <p>Best-effort, exactly like {@link OnlinePresenceStore}: a missing or corrupt file simply yields
 * an empty map — it never throws into game logic or the gateway loop. Writes happen on the Discord
 * daemon threads (the gateway callbacks run there); the initial {@link #load} runs on the server
 * thread at startup, before any presence event can arrive.</p>
 */
final class DiscordPresenceStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One user's last-seen presence: their last known status + when they were last seen online. */
    record Entry(String status, long lastOnlineMillis) {}

    private final ConcurrentHashMap<String, Entry> presence = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    void load(Path file) {
        this.file = file;
        presence.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no Discord-presence store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                Entry entry = parseEntry(e.getValue());
                if (entry == null || e.getKey() == null || e.getKey().isBlank()) {
                    LOGGER.warn("Discord Presence: skipping invalid Discord-presence entry '{}'.", e.getKey());
                    continue;
                }
                presence.put(e.getKey(), entry);
            }
            LOGGER.info("Discord Presence: loaded {} persisted Discord-presence entr(ies).", presence.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read Discord-presence store {}; starting fresh.", file, e);
            presence.clear();
        }
    }

    /**
     * Record a user's current Discord {@code status} at {@code now}. A non-blank status other than
     * {@code offline} (online/idle/dnd) bumps {@code lastOnlineMillis} to {@code now}; {@code offline}
     * (or blank/null) keeps the previous {@code lastOnlineMillis} as the freeze point — so a later
     * "last seen online" answer is the moment they were last seen online, not when they went offline.
     * Writes through (best-effort).
     */
    void record(String userId, String status, long now) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        boolean online = status != null && !status.isBlank() && !PresenceUpdate.OFFLINE.equals(status);
        String resolvedStatus = (status == null || status.isBlank()) ? PresenceUpdate.OFFLINE : status;
        presence.compute(userId, (k, prev) -> {
            long lastOnline = online ? now : (prev != null ? prev.lastOnlineMillis() : 0L);
            return new Entry(resolvedStatus, lastOnline);
        });
        save();
    }

    /**
     * Relay-mode: store the relay's <i>authoritative</i> presence for a user — its current
     * {@code status} plus the {@code lastOnlineMillis} the relay already computed (the freeze point).
     * Unlike {@link #record}, this does not derive the timestamp from "now": in relay-mode the relay
     * tracks the live gateway and is the source of truth, so its value is stored verbatim (clamped
     * non-negative; blank/null status reads as offline). Writes through (best-effort).
     */
    void setRelay(String userId, String status, long lastOnlineMillis) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String resolvedStatus = (status == null || status.isBlank()) ? PresenceUpdate.OFFLINE : status;
        presence.put(userId, new Entry(resolvedStatus, Math.max(0L, lastOnlineMillis)));
        save();
    }

    /** The epoch-millis the user was last seen online, or empty when never observed online. */
    Optional<Long> lastOnlineMillis(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Entry e = presence.get(userId);
        return (e != null && e.lastOnlineMillis() > 0) ? Optional.of(e.lastOnlineMillis()) : Optional.empty();
    }

    /** The user's last known Discord status, or empty when we have no record for them. */
    Optional<String> status(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Entry e = presence.get(userId);
        return (e != null) ? Optional.ofNullable(e.status()) : Optional.empty();
    }

    /** A snapshot copy of all current entries — safe to iterate while the live map mutates. */
    Map<String, Entry> entries() {
        return new HashMap<>(presence);
    }

    boolean isEmpty() {
        return presence.isEmpty();
    }

    /** Parse one stored value: {@code {status, lastOnlineMillis}}. Returns {@code null} when unusable. */
    private static Entry parseEntry(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject o = value.getAsJsonObject();
        String status = o.has("status") && !o.get("status").isJsonNull() ? o.get("status").getAsString() : null;
        long lastOnline = 0L;
        if (o.has("lastOnlineMillis") && o.get("lastOnlineMillis").isJsonPrimitive()) {
            try {
                lastOnline = o.get("lastOnlineMillis").getAsLong();
            } catch (NumberFormatException ignored) {
                lastOnline = 0L;
            }
        }
        // An entry with neither a status nor a last-online timestamp carries no information.
        if ((status == null || status.isBlank()) && lastOnline <= 0) {
            return null;
        }
        return new Entry(status, lastOnline);
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Entry> e : presence.entrySet()) {
                Entry entry = e.getValue();
                JsonObject o = new JsonObject();
                if (entry.status() != null) {
                    o.addProperty("status", entry.status());
                }
                o.addProperty("lastOnlineMillis", entry.lastOnlineMillis());
                obj.add(e.getKey(), o);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write Discord-presence store {}.", target, e);
        }
    }
}
