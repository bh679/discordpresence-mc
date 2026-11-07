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
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * Durable resend queue for Discord webhook posts (death / disconnect / survey / relayed chat + join
 * embeds, with their photos) that a relay stall, an outage or an offline period would otherwise drop.
 * The fast path stays {@link DiscordHttp#sendWithRetry}'s 4 in-memory attempts; this is the slow,
 * on-disk fallback that survives a restart: when a send fails in a way
 * {@link DiscordHttp#isQueueableFailure} says is safe to repeat, the fully-built webhook body + target
 * is spooled here and re-POSTed on the next flush (server start, the 60&nbsp;s tick, or clean shutdown).
 *
 * <p><b>At-least-once.</b> An entry is removed <i>only</i> when its sender reports it finished; a failed
 * resend keeps it for the next flush. Entries with a {@code postId} can't double-post, because the relay
 * dedupes on it. One without can only duplicate if the process dies after the 2xx but before the removal
 * persists — the same trade-off the menu-chat outbox accepts. The random per-entry {@code key} is an
 * in-flight de-dup handle so one flush never sends the same entry twice concurrently.</p>
 *
 * <p><b>Photos.</b> A post's photo is kept in a file beside the queue ({@value #PHOTO_DIR}), never in the
 * JSON, which is rewritten on every change. Past {@link #MAX_PHOTO_BYTES} the oldest posts go text-only.
 * Bug-report file attachments stay best-effort, and bot reactions / thread creation are transient UI
 * state and are never queued.</p>
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

    /** Total size of kept photos; past it the oldest queued posts lose theirs. About 30 death photos. */
    static final long MAX_PHOTO_BYTES = 32L * 1024 * 1024;

    /** Posts sent per flush, oldest first, so every client re-sending at once can't flatten a relay just back up. */
    static final int MAX_SENDS_PER_FLUSH = 5;

    static final String PHOTO_DIR = "discordpresence-resend-photos";

    /** What a photo file may be called; the name comes back off disk, so it can't be allowed to walk out of the folder. */
    private static final Pattern PHOTO_FILE = Pattern.compile("[0-9a-f-]{36}\\.png");

    /**
     * One queued webhook post: the effective webhook base + optional thread + the JSON body, plus the
     * relay {@code postId} and the saved photo's {@code photoFile} and attachment {@code filename},
     * each {@code null} when absent.
     */
    record QueuedPost(String key, String url, String threadId, String rootJson, long createdAtMs,
                      String postId, String photoFile, String filename) {

        private QueuedPost textOnly() {
            return new QueuedPost(key, url, threadId, withoutImage(rootJson), createdAtMs, postId, null, null);
        }
    }

    /** How a flush delivers one entry; the future resolves {@code true} when the queue is finished with it. */
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

    private final long maxPhotoBytes;

    /** Package-private for the singleton and for tests (a fresh instance per test). */
    DiscordResendQueue() {
        this(MAX_PHOTO_BYTES);
    }

    DiscordResendQueue(long maxPhotoBytes) {
        this.maxPhotoBytes = maxPhotoBytes;
    }

    /** Replace the in-memory queue from {@code file} (best-effort), evicting stale + overflow entries. */
    synchronized void load(Path file) {
        this.file = file;
        pending.clear();
        inFlight.clear();
        if (file == null || !Files.exists(file)) {
            deleteOrphanPhotos();
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
                        pending.put(p.key(), p.photoFile() != null && !photoExists(p) ? p.textOnly() : p);
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
        deleteOrphanPhotos();
    }

    /** As {@link #enqueue(String, String, String, String, byte[], String)} with no post id and no photo. */
    synchronized void enqueue(String url, String threadId, String rootJson) {
        enqueue(url, threadId, rootJson, null, null, null);
    }

    /**
     * Spool a failed webhook post for later resend (best-effort, write-through). {@code url} is the
     * effective webhook base (pre-{@code ?wait}/{@code thread_id}); {@code rootJson} is the body to POST.
     * Blank url / body are ignored. {@code photo} is saved beside the queue; if it can't be, the post
     * goes text-only.
     */
    synchronized void enqueue(String url, String threadId, String rootJson, String postId,
                              byte[] photo, String filename) {
        if (url == null || url.isBlank() || rootJson == null || rootJson.isBlank()) {
            return;
        }
        String key = UUID.randomUUID().toString();
        boolean hasPhoto = photo != null && photo.length > 0 && filename != null;
        String photoFile = hasPhoto ? writePhoto(key, photo) : null;
        QueuedPost post = new QueuedPost(key, url, threadId, rootJson, System.currentTimeMillis(),
                postId, photoFile, photoFile == null ? null : filename);
        pending.put(key, hasPhoto && photoFile == null ? post.textOnly() : post);
        pruneExpired(System.currentTimeMillis());
        trimOldest();
        trimPhotos();
        save();
    }

    /** The post's saved photo, or {@code null} when it has none or the file is gone. */
    byte[] readPhoto(QueuedPost post) {
        Path photo = photoPath(post.photoFile());
        if (photo == null || !Files.exists(photo)) {
            return null;
        }
        try {
            return Files.readAllBytes(photo);
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to read queued photo {}.", photo, e);
            return null;
        }
    }

    /**
     * Try to deliver the oldest {@link #MAX_SENDS_PER_FLUSH} pending posts via {@code sender}. Each entry
     * is sent at most once per flush (in-flight de-dup) and removed <i>only</i> when the sender reports it
     * finished; failures are kept for the next flush. Stale entries are pruned first. Never throws.
     */
    void flush(Sender sender) {
        List<QueuedPost> snapshot;
        synchronized (this) {
            if (pruneExpired(System.currentTimeMillis())) {
                save();
            }
            snapshot = new ArrayList<>(pending.values());
        }
        int sends = 0;
        for (QueuedPost post : snapshot) {
            if (sends >= MAX_SENDS_PER_FLUSH) {
                return;
            }
            synchronized (this) {
                if (!pending.containsKey(post.key()) || !inFlight.add(post.key())) {
                    continue; // removed meanwhile, or already being resent
                }
            }
            sends++;
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
        if (err != null || !Boolean.TRUE.equals(delivered)) {
            return; // stays queued for the next flush
        }
        QueuedPost removed = pending.remove(key);
        if (removed != null) {
            deletePhoto(removed);
            save();
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
            QueuedPost post = it.next().getValue();
            if (now - post.createdAtMs() > MAX_AGE_MS) {
                deletePhoto(post);
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
            deletePhoto(it.next().getValue());
            it.remove();
            changed = true;
        }
        return changed;
    }

    /** Make the oldest posts text-only until the kept photos fit {@link #maxPhotoBytes}. */
    private void trimPhotos() {
        long total = 0L;
        for (QueuedPost post : pending.values()) {
            total += photoSize(post);
        }
        for (Map.Entry<String, QueuedPost> entry : pending.entrySet()) {
            if (total <= maxPhotoBytes) {
                return;
            }
            QueuedPost post = entry.getValue();
            if (post.photoFile() != null) {
                total -= photoSize(post);
                deletePhoto(post);
                entry.setValue(post.textOnly());
            }
        }
    }

    /** The post's photo path, or {@code null} when it has none or the stored name isn't one of ours. */
    private Path photoPath(String photoFile) {
        Path target = this.file;
        if (target == null || photoFile == null || !PHOTO_FILE.matcher(photoFile).matches()) {
            return null;
        }
        return target.resolveSibling(PHOTO_DIR).resolve(photoFile);
    }

    private boolean photoExists(QueuedPost post) {
        Path photo = photoPath(post.photoFile());
        return photo != null && Files.exists(photo);
    }

    private long photoSize(QueuedPost post) {
        Path photo = photoPath(post.photoFile());
        try {
            return photo != null && Files.exists(photo) ? Files.size(photo) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Save a photo beside the queue; returns its file name, or {@code null} when it couldn't be saved. */
    private String writePhoto(String key, byte[] photo) {
        String name = key + ".png";
        Path path = photoPath(name);
        if (path == null) {
            return null;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, photo);
            return name;
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: failed to save queued photo {}.", path, e);
            return null;
        }
    }

    private void deletePhoto(QueuedPost post) {
        Path photo = photoPath(post.photoFile());
        if (photo == null) {
            return;
        }
        try {
            Files.deleteIfExists(photo);
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: failed to delete queued photo {}: {}", photo, e.toString());
        }
    }

    /** Remove photo files no entry refers to, left by a crash between writing one and saving the queue. */
    private void deleteOrphanPhotos() {
        Path target = this.file;
        Path dir = target == null ? null : target.resolveSibling(PHOTO_DIR);
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        Set<String> kept = new HashSet<>();
        for (QueuedPost post : pending.values()) {
            kept.add(post.photoFile());
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> PHOTO_FILE.matcher(f.getFileName().toString()).matches())
                    .filter(f -> !kept.contains(f.getFileName().toString()))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Exception e) {
                            LOGGER.debug("Discord Presence: failed to delete orphan photo {}: {}", f, e.toString());
                        }
                    });
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: failed to list queued photos: {}", e.toString());
        }
    }

    /** {@code rootJson} without its {@code attachment://} image, for a post whose photo is gone. */
    private static String withoutImage(String rootJson) {
        try {
            return DiscordWebhookClient.durableBody(JsonParser.parseString(rootJson).getAsJsonObject(), true);
        } catch (Exception e) {
            return rootJson;
        }
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
        return new QueuedPost(key, url, threadId, rootJson, created,
                optString(o, "postId"), optString(o, "photoFile"), optString(o, "filename"));
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
                if (p.postId() != null) {
                    o.addProperty("postId", p.postId());
                }
                if (p.photoFile() != null) {
                    o.addProperty("photoFile", p.photoFile());
                    o.addProperty("filename", p.filename());
                }
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
