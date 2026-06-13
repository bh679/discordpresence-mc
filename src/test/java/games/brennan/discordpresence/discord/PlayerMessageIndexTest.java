package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded LRU reverse index behaviour. */
class PlayerMessageIndexTest {

    @Test
    void storesAndRetrieves() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        UUID u = UUID.randomUUID();
        idx.put("m1", u);
        assertTrue(idx.contains("m1"));
        assertEquals(u, idx.get("m1"));
        assertFalse(idx.contains("nope"));
    }

    @Test
    void ignoresNulls() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        idx.put(null, UUID.randomUUID());
        idx.put("m", null);
        assertEquals(0, idx.size());
        assertFalse(idx.contains(null));
        assertNull(idx.get(null));
    }

    @Test
    void evictsEldestBeyondCapacity() {
        PlayerMessageIndex idx = new PlayerMessageIndex(3);
        idx.put("a", UUID.randomUUID());
        idx.put("b", UUID.randomUUID());
        idx.put("c", UUID.randomUUID());
        idx.put("d", UUID.randomUUID()); // evicts "a"
        assertEquals(3, idx.size());
        assertFalse(idx.contains("a"));
        assertTrue(idx.contains("d"));
    }

    @Test
    void accessRefreshesRecency() {
        PlayerMessageIndex idx = new PlayerMessageIndex(3);
        idx.put("a", UUID.randomUUID());
        idx.put("b", UUID.randomUUID());
        idx.put("c", UUID.randomUUID());
        idx.get("a");                    // touch "a" — now most-recently used
        idx.put("d", UUID.randomUUID()); // should evict "b", not "a"
        assertTrue(idx.contains("a"));
        assertFalse(idx.contains("b"));
    }

    @Test
    void concurrentPutsAndGetsDoNotThrow() throws InterruptedException {
        PlayerMessageIndex idx = new PlayerMessageIndex(500);
        Runnable writer = () -> {
            for (int i = 0; i < 2000; i++) {
                idx.put("m" + i, UUID.randomUUID());
            }
        };
        Runnable reader = () -> {
            for (int i = 0; i < 2000; i++) {
                idx.contains("m" + i);
            }
        };
        Thread t1 = new Thread(writer);
        Thread t2 = new Thread(reader);
        Thread t3 = new Thread(writer);
        t1.start();
        t2.start();
        t3.start();
        t1.join();
        t2.join();
        t3.join();
        assertTrue(idx.size() <= 500);
    }
}
