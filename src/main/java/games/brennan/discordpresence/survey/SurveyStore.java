package games.brennan.discordpresence.survey;

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
 * Durable per-player record of how many times a player has answered each survey question:
 * the death screen hides the survey once every question has been answered at least once, and
 * every Discord post can say how many times this player has answered that question. Keyed by
 * player UUID → question id → answer count.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-surveys.json}) in the server config
 * dir: {@code { "<uuid>": { "discordpresence:nps": 3, ... } }}. Older files stored a plain
 * array of answered ids ({@code { "<uuid>": ["discordpresence:nps", ...] }}); those load as a
 * count of one per id, and the next save writes the current shape. Loaded once on server
 * start; every {@link #markAnswered} writes through immediately (tmp file + atomic move).
 * Mirrors the shape of {@code DiscordThreadStore}.</p>
 *
 * <p>Best-effort: a missing/corrupt file yields an empty map (every question is unanswered)
 * — it never throws into game logic.</p>
 */
public final class SurveyStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Integer>> counts = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    public void load(Path file) {
        this.file = file;
        counts.clear();
        if (file == null || !Files.exists(file)) {
            LOGGER.info("Discord Presence: no survey store yet ({}) — starting fresh.", file);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    ConcurrentHashMap<String, Integer> ids = parsePlayer(e.getValue());
                    if (!ids.isEmpty()) {
                        counts.put(uuid, ids);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid survey-store entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded survey answers for {} player(s).", counts.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read survey store {}; starting fresh.", file, e);
            counts.clear();
        }
    }

    /**
     * One player's entry: the current {@code {id: count}} object, or the legacy {@code [id, ...]}
     * array (each id counted once). Anything else yields an empty map.
     */
    static ConcurrentHashMap<String, Integer> parsePlayer(JsonElement value) {
        ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
        if (value == null) {
            return ids;
        }
        if (value.isJsonArray()) {
            for (JsonElement el : value.getAsJsonArray()) {
                if (el != null && el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                    ids.put(el.getAsString(), 1);
                }
            }
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> q : value.getAsJsonObject().entrySet()) {
                JsonElement n = q.getValue();
                if (q.getKey().isBlank() || n == null || !n.isJsonPrimitive()) {
                    continue;
                }
                int count = n.getAsJsonPrimitive().isNumber() ? n.getAsInt() : 0;
                if (count > 0) {
                    ids.put(q.getKey(), count);
                }
            }
        }
        return ids;
    }

    /** Whether the player has answered the question with this id at least once. */
    public boolean hasAnswered(UUID uuid, String id) {
        return count(uuid, id) > 0;
    }

    /** How many times the player has answered the question with this id (0 = never). */
    public int count(UUID uuid, String id) {
        Map<String, Integer> ids = counts.get(uuid);
        if (ids == null || id == null) {
            return 0;
        }
        return ids.getOrDefault(id, 0);
    }

    /**
     * Record one more answer to this question by this player and write through to disk
     * (best-effort). Returns the new count, including this answer; {@code 0} when the arguments
     * were unusable and nothing was recorded.
     */
    public int markAnswered(UUID uuid, String id) {
        if (uuid == null || id == null || id.isBlank()) {
            return 0;
        }
        int now = counts.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .merge(id, 1, Integer::sum);
        save();
        return now;
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, ConcurrentHashMap<String, Integer>> e : counts.entrySet()) {
                JsonObject per = new JsonObject();
                for (Map.Entry<String, Integer> q : e.getValue().entrySet()) {
                    per.addProperty(q.getKey(), q.getValue());
                }
                obj.add(e.getKey().toString(), per);
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write survey store {}.", target, e);
        }
    }
}
