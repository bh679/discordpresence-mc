package games.brennan.discordpresence.reincarnation;

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
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Durable "post once" guard for outbound reincarnation records: the set of PlayerMob death keys
 * Discord Presence has already forwarded to the relay, so a death is posted exactly once even across
 * restarts. Without it, DP's periodic {@code recentDeaths} scrape would re-POST the newest deaths on
 * every server start, duplicating them in the pool.
 *
 * <p>A bounded, insertion-ordered set (oldest evicted past {@link #MAX_KEYS}). The cap is far larger
 * than the {@code recentDeaths} scrape window, so a key that could still reappear in a scrape is never
 * evicted — once a key falls out of the set it is also long gone from {@code recentDeaths}. Posting is
 * claimed <i>optimistically</i> (mark before the POST fires): a failed POST drops that one death rather
 * than risking a duplicate, matching DP's best-effort Discord I/O.</p>
 *
 * <p>Backed by a small JSON file ({@code discordpresence-reincarnation.json}): {@code {"posted":[...]}}.
 * Mirrors the {@code OnlinePresenceStore} write-through idiom (tmp file + atomic move). Best-effort: a
 * missing/corrupt file yields an empty set and never throws into game logic.</p>
 */
final class ReincarnationOutbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max remembered keys; comfortably exceeds the {@code recentDeaths} scrape window. */
    static final int MAX_KEYS = 1000;

    private final LinkedHashSet<String> posted = new LinkedHashSet<>();
    private volatile Path file;

    /** Replace the in-memory set from {@code file} (best-effort). */
    synchronized void load(Path file) {
        this.file = file;
        posted.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement arr = obj.get("posted");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    if (el != null && el.isJsonPrimitive()) {
                        posted.add(el.getAsString());
                    }
                }
            }
            trimOldest(posted, MAX_KEYS);
            LOGGER.info("Discord Presence: loaded {} reincarnation outbox key(s).", posted.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read reincarnation outbox {}; starting fresh.", file, e);
            posted.clear();
        }
    }

    /** Whether {@code key} has NOT yet been posted (a null/blank key is never postable). */
    synchronized boolean shouldPost(String key) {
        return key != null && !key.isBlank() && !posted.contains(key);
    }

    /** Mark {@code key} as posted (claim it) and write through, evicting the oldest past {@link #MAX_KEYS}. */
    synchronized void markPosted(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (posted.add(key)) {
            trimOldest(posted, MAX_KEYS);
            save();
        }
    }

    /** Test seam: current remembered key count. */
    synchronized int size() {
        return posted.size();
    }

    /** Evict the oldest entries (front of insertion order) until {@code set} is within {@code max}. Pure. */
    static void trimOldest(LinkedHashSet<String> set, int max) {
        if (max <= 0) {
            set.clear();
            return;
        }
        Iterator<String> it = set.iterator();
        while (set.size() > max && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (String key : posted) {
                arr.add(key);
            }
            JsonObject obj = new JsonObject();
            obj.add("posted", arr);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write reincarnation outbox {}.", target, e);
        }
    }
}
