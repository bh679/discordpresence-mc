package games.brennan.discordpresence.survey;

import com.google.gson.JsonArray;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable per-player record of which survey questions a player has already answered,
 * so each death surfaces the NEXT unanswered question and an answered question is
 * never asked again. Keyed by player UUID → set of answered question ids.
 *
 * <p>Backed by a small JSON file ({@code discordpresence-surveys.json}) in the server
 * config dir: {@code { "<uuid>": ["discordpresence:nps", ...] }}. Loaded once on
 * server start; every {@link #markAnswered} writes through immediately (tmp file +
 * atomic move). Mirrors the shape of {@code DiscordThreadStore}.</p>
 *
 * <p>Best-effort: a missing/corrupt file yields an empty map (every question is
 * unanswered) — it never throws into game logic.</p>
 */
public final class SurveyStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConcurrentHashMap<UUID, Set<String>> answered = new ConcurrentHashMap<>();
    private volatile Path file;

    /** Replace the in-memory map from {@code file} (best-effort). */
    public void load(Path file) {
        this.file = file;
        answered.clear();
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
                    Set<String> ids = ConcurrentHashMap.newKeySet();
                    if (e.getValue().isJsonArray()) {
                        for (JsonElement el : e.getValue().getAsJsonArray()) {
                            if (el != null && el.isJsonPrimitive()) {
                                String id = el.getAsString();
                                if (!id.isBlank()) {
                                    ids.add(id);
                                }
                            }
                        }
                    }
                    if (!ids.isEmpty()) {
                        answered.put(uuid, ids);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Discord Presence: skipping invalid survey-store entry '{}'.", e.getKey());
                }
            }
            LOGGER.info("Discord Presence: loaded survey answers for {} player(s).", answered.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read survey store {}; starting fresh.", file, e);
            answered.clear();
        }
    }

    /** Whether the player has already answered the question with this id. */
    public boolean hasAnswered(UUID uuid, String id) {
        Set<String> ids = answered.get(uuid);
        return ids != null && ids.contains(id);
    }

    /** Record that the player answered this question and write through to disk (best-effort). */
    public void markAnswered(UUID uuid, String id) {
        if (uuid == null || id == null || id.isBlank()) {
            return;
        }
        answered.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(id);
        save();
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            for (Map.Entry<UUID, Set<String>> e : answered.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String id : e.getValue()) {
                    arr.add(id);
                }
                obj.add(e.getKey().toString(), arr);
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
