package games.brennan.discordpresence.discord;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable resend queue for plain-JSON Discord webhook posts (death / disconnect / survey / relayed
 * chat + join embeds) that a genuine relay outage or offline period would otherwise drop. The fast
 * path stays {@link DiscordHttp#sendWithRetry}'s 4 in-memory attempts; this is the slow, on-disk
 * fallback that survives a restart: when a post's send fails on a provably-pre-send connection
 * failure (relay/DNS unreachable), the fully-built webhook body + target is spooled here and
 * re-POSTed on the next flush (server start, the 60&nbsp;s tick, or clean shutdown).
 *
 * <p><b>At-least-once.</b> An entry is removed <i>only</i> after a confirmed 2xx (a non-null
 * {@link DiscordMessageRef}); a failed resend keeps it for the next flush. So a message is never
 * lost, at the cost of a possible duplicate if the process dies after the 2xx but before the removal
 * persists — the same trade-off the menu-chat outbox accepts. The random per-entry {@code key} is an
 * in-flight de-dup handle so one flush never sends the same entry twice concurrently.</p>
 *
 * <p><b>Boundary.</b> Only plain-JSON webhook bodies are made durable. Composed-image / multipart
 * posts (the death-gear PNG, bug-report file attachments) stay best-effort — a death report that
 * carried a gear image is re-queued as the embed <i>without</i> the image (the PNG isn't persisted).
 * Bot reactions / thread creation are transient UI state and are never queued.</p>
 *
 * <p>Backed by a small JSON file ({@code discordpresence-resend-queue.json}) in the server config
 * dir, one object with a {@code "pending"} array; write-through is tmp file + atomic move, mirroring
 * {@link OnlinePresenceStore}. Best-effort: a missing / corrupt file yields an empty queue and never
 * throws into game logic. Bounded by {@link #MAX_ITEMS} (oldest evicted) and {@link #MAX_AGE_MS} (so
 * a permanently-offline client can't grow the file and stale posts aren't replayed weeks late).</p>
 *
 * <p><b>Note.</b> The stored {@code url} is the effective webhook (which in direct mode is a secret);
 * this file lives in the same gitignored runtime config dir as the secrets config that already holds
 * it, so it crosses no new trust boundary and is never committed.</p>
 */
final class DiscordResendQueue {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max queued posts; oldest evicted past this so a long outage can't grow the file unbounded. */
    static final int MAX_ITEMS = 500;

    /** Drop entries older than this rather than replay stale posts after a very long offline period. */
    static final long MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000; // 14 days

    /** One queued webhook post: the effective webhook base + optional thread + the plain JSON body. */
    record QueuedPost(String key, String url, String threadId, String rootJson, long createdAtMs) {}

    /** How a flush delivers one entry; the future resolves {@code true} only on a confirmed 2xx. */
    interface Sender {
        CompletableFuture<Boolean> send(QueuedPost post);
    }

    private static final DiscordResendQueue INSTANCE = new DiscordResendQueue();

    /** The process-wide queue shared by the webhook client (enqueue) and {@code DiscordService} (flush). */
    static DiscordResendQueue get() {
        return INSTANCE;
    }

    /** Insertion-ordered pending posts keyed by their idempotency {@code key}. Guarded by {@code this}. */
    private final LinkedHashMap<String, QueuedPost> pending = new LinkedHashMap<>();

    /** Keys currently being resent, so one flush never double-sends the same entry. Guarded by {@code this}. */
    private final Set<String> inFlight = new HashSet<>();

    private volatile Path file;

    /** Package-private for the singleton and for tests (a fresh instance per test). */
    DiscordResendQueue() {}

    /** Replace the in-memory queue from {@code file} (best-effort), evicting stale + overflow entries. */
    synchronized void load(Path file) {
        this.file = file;
        pending.clear();
        inFlight.clear();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement arr = obj.get("pending");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement el : arr.getAsJsonArray()) {
                    QueuedPost p = parseEntry(el);
                    if (p != null) {
                        pending.put(p.key(), p);
                    }
                }
            }
            boolean changed = pruneExpired(System.currentTimeMillis()) | trimOldest();
            if (changed) {
                save();
            }
            LOGGER.info("Discord Presence: loaded {} queued webhook resend(s).", pending.size());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read resend queue {}; starting fresh.", file, e);
            pending.clear();
        }
    }

    /**
     * Spool a failed plain-JSON webhook post for later resend (best-effort, write-through). {@code url}
     * is the effective webhook base (pre-{@code ?wait}/{@code thread_id}); {@code rootJson} is the body
     * to POST. Blank url / body are ignored.
     */
    synchronized void enqueue(String url, String threadId, String rootJson) {
        if (url == null || url.isBlank() || rootJson == null || rootJson.isBlank()) {
            return;
        }
        String key = UUID.randomUUID().toString();
        pending.put(key, new QueuedPost(key, url, threadId, rootJson, System.currentTimeMillis()));
        pruneExpired(System.currentTimeMillis());
        trimOldest();
        save();
    }

    /**
     * Try to deliver every pending post via {@code sender}. Each entry is sent at most once per flush
     * (in-flight de-dup) and removed <i>only</i> on a confirmed 2xx; failures are kept for the next
     * flush. Stale entries are pruned first. Never throws.
     */
    void flush(Sender sender) {
        List<QueuedPost> snapshot;
        synchronized (this) {
            if (pruneExpired(System.currentTimeMillis())) {
                save();
            }
            snapshot = new ArrayList<>(pending.values());
        }
        for (QueuedPost post : snapshot) {
            synchronized (this) {
                if (!pending.containsKey(post.key()) || !inFlight.add(post.key())) {
                    continue; // removed meanwhile, or already being resent
                }
            }
            CompletableFuture<Boolean> sent;
            try {
                sent = sender.send(post);
            } catch (Exception e) {
                synchronized (this) {
                    inFlight.remove(post.key());
                }
                LOGGER.debug("Discord Presence: resend send() threw for {}: {}", post.key(), e.toString());
                continue;
            }
            sent.whenComplete((delivered, err) -> onResendComplete(post.key(), delivered, err));
        }
    }

    private synchronized void onResendComplete(String key, Boolean delivered, Throwable err) {
        inFlight.remove(key);
        if (err == null && Boolean.TRUE.equals(delivered) && pending.remove(key) != null) {
            save(); // confirmed 2xx → drop it; anything else stays queued for the next flush
        }
    }

    /** Test/diagnostic seam: current queued-post count. */
    synchronized int size() {
        return pending.size();
    }

    // --- helpers (callers hold the monitor) ----------------------------------

    /** Evict entries older than {@link #MAX_AGE_MS}. Returns whether anything was removed. */
    private boolean pruneExpired(long now) {
        boolean changed = false;
        Iterator<Map.Entry<String, QueuedPost>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAtMs() > MAX_AGE_MS) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** Evict the oldest entries (front of insertion order) until within {@link #MAX_ITEMS}. */
    private boolean trimOldest() {
        boolean changed = false;
        Iterator<Map.Entry<String, QueuedPost>> it = pending.entrySet().iterator();
        while (pending.size() > MAX_ITEMS && it.hasNext()) {
            it.next();
            it.remove();
            changed = true;
        }
        return changed;
    }

    private static QueuedPost parseEntry(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String key = optString(o, "key");
        String url = optString(o, "url");
        String rootJson = optString(o, "rootJson");
        if (key == null || key.isBlank() || url == null || url.isBlank()
                || rootJson == null || rootJson.isBlank()) {
            return null;
        }
        String threadId = optString(o, "threadId");
        long created = 0L;
        if (o.has("createdAtMs") && o.get("createdAtMs").isJsonPrimitive()) {
            try {
                created = o.get("createdAtMs").getAsLong();
            } catch (NumberFormatException ignored) {
                created = 0L;
            }
        }
        return new QueuedPost(key, url, threadId, rootJson, created);
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && !e.isJsonNull() && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private void save() {
        Path target = this.file;
        if (target == null) {
            return;
        }
        try {
            JsonArray arr = new JsonArray();
            for (QueuedPost p : pending.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("key", p.key());
                o.addProperty("url", p.url());
                if (p.threadId() != null) {
                    o.addProperty("threadId", p.threadId());
                }
                o.addProperty("rootJson", p.rootJson());
                o.addProperty("createdAtMs", p.createdAtMs());
                arr.add(o);
            }
            JsonObject obj = new JsonObject();
            obj.add("pending", arr);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to write resend queue {}.", target, e);
        }
    }
}
