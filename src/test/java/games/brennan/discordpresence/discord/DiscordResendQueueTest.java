package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.discord.DiscordResendQueue.QueuedPost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        q.flush(post -> {
            bodies.add(post.rootJson());
            return CompletableFuture.completedFuture(true);
        });
        assertEquals(DiscordResendQueue.MAX_ITEMS, bodies.size());
        assertFalse(bodies.contains("{\"n\":0}"), "the oldest entry was evicted");
        assertTrue(bodies.contains("{\"n\":" + DiscordResendQueue.MAX_ITEMS + "}"), "the newest was kept");
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
