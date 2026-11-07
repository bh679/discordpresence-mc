package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.discord.DiscordResendQueue.QueuedPost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The durable webhook resend queue: persistence across reload, the at-least-once contract (removed only
 * on a confirmed 2xx, kept otherwise), in-flight de-dup, and bounded oldest-eviction. The {@code Sender}
 * seam lets these run with no network — a completed future resolves the flush inline.
 */
class DiscordResendQueueTest {

    private static final String URL = "https://relay.example/hook";
    private static final String BODY = "{\"content\":\"hello\"}";

    private static Path queueFile(Path dir) {
        return dir.resolve("discordpresence-resend-queue.json");
    }

    @Test
    void enqueuePersistsAndSurvivesReload(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, "thread-1", BODY);
        assertEquals(1, q.size());

        // A fresh instance loading the same file recovers the queued post (restart-safe).
        DiscordResendQueue reloaded = new DiscordResendQueue();
        reloaded.load(queueFile(dir));
        assertEquals(1, reloaded.size());

        // …and the url / threadId / body round-trip exactly, so the replay hits the same destination.
        List<QueuedPost> sent = drainCollecting(reloaded, true);
        assertEquals(1, sent.size());
        assertEquals(URL, sent.get(0).url());
        assertEquals("thread-1", sent.get(0).threadId());
        assertEquals(BODY, sent.get(0).rootJson());
    }

    @Test
    void nullThreadIdRoundTripsAsNull(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY); // a top-level post (no thread)

        DiscordResendQueue reloaded = new DiscordResendQueue();
        reloaded.load(queueFile(dir));
        List<QueuedPost> sent = drainCollecting(reloaded, true);
        assertEquals(1, sent.size());
        assertNull(sent.get(0).threadId());
    }

    @Test
    void blankUrlOrBodyIsIgnored(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(null, "t", BODY);
        q.enqueue("", "t", BODY);
        q.enqueue(URL, "t", null);
        q.enqueue(URL, "t", "");
        assertEquals(0, q.size(), "a blank url / body is never queued");
    }

    @Test
    void removedOnlyOnConfirmed2xxAndPersists(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY);

        q.flush(post -> CompletableFuture.completedFuture(true)); // delivered
        assertEquals(0, q.size());

        // The removal is durable — a reload doesn't resurrect a delivered post.
        DiscordResendQueue reloaded = new DiscordResendQueue();
        reloaded.load(queueFile(dir));
        assertEquals(0, reloaded.size());
    }

    @Test
    void keptWhenDeliveryReportsFailure(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY);

        q.flush(post -> CompletableFuture.completedFuture(false)); // not a 2xx
        assertEquals(1, q.size(), "a failed resend is held for the next flush");
    }

    @Test
    void keptWhenSenderCompletesExceptionally(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY);

        q.flush(post -> CompletableFuture.failedFuture(new RuntimeException("outage")));
        assertEquals(1, q.size(), "an exceptional send never removes the entry");
    }

    @Test
    void keptWhenSenderThrowsSynchronously(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY);

        q.flush(post -> {
            throw new RuntimeException("boom");
        });
        assertEquals(1, q.size(), "a throwing sender can't lose the entry");
    }

    @Test
    void inFlightDeDupWithinAFlush(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, BODY);

        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        DiscordResendQueue.Sender sender = post -> {
            calls.incrementAndGet();
            return pending; // never completes → stays in flight
        };

        q.flush(sender);
        q.flush(sender); // same entry still in flight → must be skipped
        assertEquals(1, calls.get(), "an in-flight entry is not sent twice");

        pending.complete(true); // now the first send confirms → entry drops
        assertEquals(0, q.size());
    }

    @Test
    void oldestEvictedPastMaxItems(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        for (int i = 0; i <= DiscordResendQueue.MAX_ITEMS; i++) { // one over the cap
            q.enqueue(URL, null, "{\"n\":" + i + "}");
        }
        assertEquals(DiscordResendQueue.MAX_ITEMS, q.size());

        Set<String> bodies = ConcurrentHashMap.newKeySet();
        while (q.size() > 0) { // each flush sends only a few
            q.flush(post -> {
                bodies.add(post.rootJson());
                return CompletableFuture.completedFuture(true);
            });
        }
        assertEquals(DiscordResendQueue.MAX_ITEMS, bodies.size());
        assertFalse(bodies.contains("{\"n\":0}"), "the oldest entry was evicted");
        assertTrue(bodies.contains("{\"n\":" + DiscordResendQueue.MAX_ITEMS + "}"), "the newest was kept");
    }

    private static final byte[] PNG = {1, 2, 3, 4};
    private static final String PHOTO_BODY =
            "{\"embeds\":[{\"title\":\"t\",\"image\":{\"url\":\"attachment://photo.png\"}}]}";
    private static final String POST_ID = "6a9e8815-b87e-45a3-b473-1c0b7e6e14b6";

    private static Path photoDir(Path dir) {
        return dir.resolve(DiscordResendQueue.PHOTO_DIR);
    }

    private static long photoCount(Path dir) throws IOException {
        if (!Files.isDirectory(photoDir(dir))) return 0;
        try (java.util.stream.Stream<Path> files = Files.list(photoDir(dir))) {
            return files.count();
        }
    }

    @Test
    void postIdAndPhotoSurviveReload(@TempDir Path dir) throws IOException {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, PHOTO_BODY, POST_ID, PNG, "photo.png");
        assertFalse(Files.readString(queueFile(dir)).contains("AQIDBA"), "the photo must not be in the JSON");

        DiscordResendQueue reloaded = new DiscordResendQueue();
        reloaded.load(queueFile(dir));
        QueuedPost post = drainCollecting(reloaded, false).get(0);
        assertEquals(POST_ID, post.postId());
        assertEquals("photo.png", post.filename());
        assertEquals(PHOTO_BODY, post.rootJson());
        assertArrayEquals(PNG, reloaded.readPhoto(post));
    }

    @Test
    void deliveringAPostDeletesItsPhoto(@TempDir Path dir) throws IOException {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, PHOTO_BODY, POST_ID, PNG, "photo.png");
        assertEquals(1, photoCount(dir));
        drainCollecting(q, true);
        assertEquals(0, q.size());
        assertEquals(0, photoCount(dir));
    }

    @Test
    void pastThePhotoCapTheOldestPostsGoTextOnly(@TempDir Path dir) throws IOException {
        DiscordResendQueue q = new DiscordResendQueue(2L * PNG.length);
        q.load(queueFile(dir));
        for (int i = 0; i < 3; i++) {
            q.enqueue(URL, null, PHOTO_BODY, POST_ID, PNG, "photo.png");
        }
        assertEquals(2, photoCount(dir));
        List<QueuedPost> posts = drainCollecting(q, false);
        assertEquals(3, posts.size(), "going text-only must not lose the post");
        assertNull(posts.get(0).photoFile(), "the oldest loses its photo");
        assertFalse(posts.get(0).rootJson().contains("attachment://"), "and its now-broken image ref");
        assertNotNull(posts.get(2).photoFile());
    }

    @Test
    void aMissingPhotoFileMakesThePostTextOnlyOnLoad(@TempDir Path dir) throws IOException {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        q.enqueue(URL, null, PHOTO_BODY, POST_ID, PNG, "photo.png");
        try (java.util.stream.Stream<Path> files = Files.list(photoDir(dir))) {
            for (Path f : files.toList()) Files.delete(f);
        }
        DiscordResendQueue reloaded = new DiscordResendQueue();
        reloaded.load(queueFile(dir));
        QueuedPost post = drainCollecting(reloaded, false).get(0);
        assertNull(post.photoFile());
        assertFalse(post.rootJson().contains("attachment://"));
    }

    @Test
    void loadRemovesPhotosNoEntryRefersTo(@TempDir Path dir) throws IOException {
        Files.createDirectories(photoDir(dir));
        Path orphan = photoDir(dir).resolve(POST_ID + ".png");
        Files.write(orphan, PNG);
        new DiscordResendQueue().load(queueFile(dir));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void aPhotoNameOffDiskCannotWalkOutOfTheFolder(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.png");
        Files.write(secret, PNG);
        Files.writeString(queueFile(dir), "{\"pending\":[{\"key\":\"k\",\"url\":\"" + URL + "\",\"rootJson\":"
                + "\"{}\",\"createdAtMs\":" + System.currentTimeMillis()
                + ",\"photoFile\":\"../secret.png\",\"filename\":\"photo.png\"}]}");
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        QueuedPost post = drainCollecting(q, true).get(0);
        assertNull(post.photoFile());
        assertTrue(Files.exists(secret), "delivering the entry must not delete a file outside the folder");
    }

    @Test
    void aQueueFileFromAnOlderVersionStillLoads(@TempDir Path dir) throws IOException {
        Files.writeString(queueFile(dir), "{\"pending\":[{\"key\":\"k\",\"url\":\"" + URL + "\",\"threadId\":\"t\","
                + "\"rootJson\":\"{}\",\"createdAtMs\":" + System.currentTimeMillis() + "}]}");
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        QueuedPost post = drainCollecting(q, true).get(0);
        assertEquals("t", post.threadId());
        assertNull(post.postId());
        assertNull(post.photoFile());
    }

    @Test
    void oneFlushSendsOnlyTheOldestFew(@TempDir Path dir) {
        DiscordResendQueue q = new DiscordResendQueue();
        q.load(queueFile(dir));
        int total = DiscordResendQueue.MAX_SENDS_PER_FLUSH + 3;
        for (int i = 0; i < total; i++) {
            q.enqueue(URL, null, "{\"content\":\"" + i + "\"}");
        }
        List<QueuedPost> first = drainCollecting(q, true);
        assertEquals(DiscordResendQueue.MAX_SENDS_PER_FLUSH, first.size());
        assertEquals("{\"content\":\"0\"}", first.get(0).rootJson(), "oldest first");
        assertEquals(3, q.size());
        assertEquals(3, drainCollecting(q, true).size(), "the rest go on the next flush");
    }

    /** Flush with an always-delivered sender, collecting the posts it saw (order not guaranteed). */
    private static List<QueuedPost> drainCollecting(DiscordResendQueue q, boolean deliver) {
        List<QueuedPost> seen = new ArrayList<>();
        q.flush(post -> {
            seen.add(post);
            return CompletableFuture.completedFuture(deliver);
        });
        return seen;
    }
}
