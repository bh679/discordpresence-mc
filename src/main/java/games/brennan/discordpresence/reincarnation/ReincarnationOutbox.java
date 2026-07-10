package games.brennan.discordpresence.reincarnation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.PostPayload;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Durable keep-until-2xx resend queue for outbound reincarnation records, plus the "post once" dedup
 * set that keeps a death forwarded to the relay <b>exactly once</b> across restarts.
 *
 * <p>Two collections, persisted together:</p>
 * <ul>
 *   <li>{@code delivered} — the death keys the relay has <i>confirmed</i> (a 2xx). Bounded, oldest
 *       evicted past {@link #MAX_KEYS}; the cap comfortably exceeds the {@code recentDeaths} scrape
 *       window, so a key that could still reappear in a scrape is never evicted. A delivered key is
 *       never enqueued or posted again — the never-double-post guarantee.</li>
 *   <li>{@code queue} — the full {@link PostPayload} of each death enqueued but not yet confirmed,
 *       keyed by death key. Retried from here every {@code ReincarnationManager} tick until a 2xx moves
 *       it into {@code delivered}. Bounded, oldest evicted past {@link #MAX_QUEUE}.</li>
 * </ul>
 *
 * <p>This flips the old mark-before-send guard — which recorded the key <i>before</i> the fire-and-forget
 * POST (at-most-once: a failed send silently lost a death) — to record-after-2xx: a death is kept and
 * retried until the relay accepts it (at-least-once). The only residual duplicate window is a crash
 * after the 2xx but before the removal persists, the same trade-off DP's other durable stores accept.
 * In-flight de-dup ({@link #tryBeginSend}) stops one tick's retry from racing the next.</p>
 *
 * <p>Backed by a small JSON file ({@code discordpresence-reincarnation.json}):
 * {@code {"posted":[...keys...], "queue":[{key,snapshot,...}, ...]}}. Older files with only
 * {@code "posted"} load fine (empty queue) — an upgrade never re-posts an already-delivered death.
 * Mirrors the {@code OnlinePresenceStore} write-through idiom (tmp file + atomic move). Best-effort: a
 * missing/corrupt file yields empty state and never throws into game logic.</p>
 */
final class ReincarnationOutbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max remembered delivered keys; comfortably exceeds the {@code recentDeaths} scrape window. */
    static final int MAX_KEYS = 1000;

    /** Max un-delivered queued payloads; caps the file so a long offline period can't grow it unbounded. */
    static final int MAX_QUEUE = 500;

    /** Confirmed-delivered death keys (dedup). Insertion-ordered, oldest evicted. Guarded by {@code this}. */
    private final LinkedHashSet<String> delivered = new LinkedHashSet<>();

    /** Enqueued-but-unconfirmed death payloads, keyed by death key. Insertion-ordered. Guarded by {@code this}. */
    private final LinkedHashMap<String, PostPayload> queue = new LinkedHashMap<>();

    /** Death keys with a POST in flight, so a retry never races itself across ticks. Guarded by {@code this}. */
    private final LinkedHashSet<String> inFlight = new LinkedHashSet<>();

    private volatile Path file;

    /** Replace the in-memory state from {@code file} (best-effort). */
    synchronized void load(Path file) {
        this.file = file;
        delivered.clear();
        queue.clear();
        inFlight.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement posted = obj.get("posted");
            if (posted != null && posted.isJsonArray()) {
                for (JsonElement el : posted.getAsJsonArray()) {
                    if (el != null && el.isJsonPrimitive()) {
                        delivered.add(el.getAsString());
                    }
                }
            }
            JsonElement q = obj.get("queue");
            if (q != null && q.isJsonArray()) {
                for (JsonElement el : q.getAsJsonArray()) {
                    QueueEntry e = parseQueueEntry(el);
                    // A death already confirmed delivered is never re-queued (dedup wins over a stale entry).
                    if (e != null && !delivered.contains(e.key())) {
                        queue.put(e.key(), e.payload());
                    }
                }
            }
            trimOldest(delivered, MAX_KEYS);
            trimOldestQueue();
            LOGGER.info("Discord Presence: loaded reincarnation outbox ({} delivered, {} queued).",
                    delivered.size(), queue.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read reincarnation outbox {}; starting fresh.", file, e);
            delivered.clear();
            queue.clear();
        }
    }

    /** Whether {@code key} is new work: a non-blank key that is neither delivered nor already queued. */
    synchronized boolean shouldPost(String key) {
        return key != null && !key.isBlank() && !delivered.contains(key) && !queue.containsKey(key);
    }

    /** Enqueue a death's full payload for delivery (write-through). No-op if already delivered/queued or invalid. */
    synchronized void enqueue(String key, PostPayload payload) {
        if (!shouldPost(key) || payload == null || payload.snapshot() == null || payload.snapshot().isBlank()) {
            return;
        }
        queue.put(key, payload);
        trimOldestQueue();
        save();
    }

    /** A snapshot copy of the queued payloads — safe to iterate while the live queue mutates. */
    synchronized Map<String, PostPayload> queued() {
        return new LinkedHashMap<>(queue);
    }

    /** Claim {@code key} for an in-flight POST; false if it's gone from the queue or already being sent. */
    synchronized boolean tryBeginSend(String key) {
        return key != null && queue.containsKey(key) && inFlight.add(key);
    }

    /** Release an in-flight claim (the POST completed, delivered or not). */
    synchronized void endSend(String key) {
        inFlight.remove(key);
    }

    /**
     * Record a confirmed 2xx: drop {@code key} from the queue and add it to the delivered dedup set, in
     * one write-through. After this {@link #shouldPost} is false for {@code key} forever (until eviction),
     * so the death is never posted again.
     */
    synchronized void markDelivered(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        queue.remove(key);
        delivered.add(key);
        trimOldest(delivered, MAX_KEYS);
        save();
    }

    /** Test/diagnostic seam: confirmed-delivered key count. */
    synchronized int deliveredCount() {
        return delivered.size();
    }

    /** Test/diagnostic seam: un-delivered queued payload count. */
    synchronized int queuedCount() {
        return queue.size();
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

    /** Evict the oldest queued payloads (front of insertion order) until within {@link #MAX_QUEUE}. */
    private void trimOldestQueue() {
        Iterator<Map.Entry<String, PostPayload>> it = queue.entrySet().iterator();
        while (queue.size() > MAX_QUEUE && it.hasNext()) {
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
            JsonArray postedArr = new JsonArray();
            for (String key : delivered) {
                postedArr.add(key);
            }
            JsonArray queueArr = new JsonArray();
            for (Map.Entry<String, PostPayload> e : queue.entrySet()) {
                queueArr.add(payloadToJson(e.getKey(), e.getValue()));
            }
            JsonObject obj = new JsonObject();
            obj.add("posted", postedArr);
            obj.add("queue", queueArr);
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

    // --- payload (de)serialization -------------------------------------------

    private record QueueEntry(String key, PostPayload payload) {}

    /** Serialize one queued payload losslessly (blank/absent optional fields simply omitted). */
    private static JsonObject payloadToJson(String key, PostPayload p) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("snapshot", p.snapshot());
        if (p.name() != null) {
            o.addProperty("name", p.name());
        }
        if (p.playerId() != null) {
            o.addProperty("playerId", p.playerId());
        }
        if (p.carriage() != null) {
            o.addProperty("carriage", p.carriage());
        }
        if (p.skinUrl() != null) {
            o.addProperty("skinUrl", p.skinUrl());
        }
        if (p.friends() != null && !p.friends().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String f : p.friends()) {
                if (f != null) {
                    arr.add(f);
                }
            }
            o.add("friends", arr);
        }
        return o;
    }

    /** Parse one queued payload; returns {@code null} when the key or the required snapshot is missing. */
    private static QueueEntry parseQueueEntry(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String key = optString(o, "key");
        String snapshot = optString(o, "snapshot");
        if (key == null || key.isBlank() || snapshot == null || snapshot.isBlank()) {
            return null;
        }
        List<String> friends = new ArrayList<>();
        JsonElement fr = o.get("friends");
        if (fr != null && fr.isJsonArray()) {
            for (JsonElement f : fr.getAsJsonArray()) {
                if (f != null && f.isJsonPrimitive()) {
                    friends.add(f.getAsString());
                }
            }
        }
        return new QueueEntry(key, new PostPayload(
                snapshot, optString(o, "name"), optString(o, "playerId"),
                optInt(o, "carriage"), optString(o, "skinUrl"), friends));
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && !e.isJsonNull() && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static Integer optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
