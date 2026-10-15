package games.brennan.discordpresence.compat;

import games.brennan.discordpresence.compat.DiscordReactionHooks.ReactionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fire semantics of the host seams: every handler runs, results OR together, and a throwing handler
 * never stops the others. Hooks are static registries, so each test uses a fresh marker in the
 * content/id to keep handlers from earlier tests out of its assertions.
 */
class HookFireTest {

    private static InboundContext ctx(String content) {
        return new InboundContext("m1", "c1", "a1", "Alice", content, null);
    }

    @Test
    void commandHandlersAndContextHandlersBothRunAndOr() {
        String marker = "cmd-or-" + UUID.randomUUID();
        AtomicInteger legacyCalls = new AtomicInteger();
        List<InboundContext> seen = new ArrayList<>();
        DiscordCommandHooks.install((id, name, content, target, reply) -> {
            if (content.equals(marker)) {
                legacyCalls.incrementAndGet();
            }
            return false;
        });
        DiscordCommandHooks.installContext((c, reply) -> {
            if (c.content().equals(marker)) {
                seen.add(c);
                return true;
            }
            return false;
        });
        DiscordCommandHooks.installContext((c, reply) -> {
            if (c.content().equals(marker)) {
                throw new IllegalStateException("boom");
            }
            return false;
        });

        assertTrue(DiscordCommandHooks.fire(ctx(marker), text -> {}));
        assertEquals(1, legacyCalls.get());
        assertEquals(1, seen.size());
        assertEquals("m1", seen.get(0).messageId());
        assertEquals("c1", seen.get(0).channelId());
    }

    @Test
    void commandFireIsFalseWhenNoHandlerClaims() {
        assertFalse(DiscordCommandHooks.fire(ctx("unclaimed-" + UUID.randomUUID()), text -> {}));
        assertFalse(DiscordCommandHooks.fire(null, text -> {}));
    }

    @Test
    void postedListenersAllRunDespiteThrow() {
        UUID player = UUID.randomUUID();
        List<String> got = new ArrayList<>();
        DiscordChatHooks.installPosted((p, raw, ch, id) -> {
            if (p.equals(player)) {
                throw new RuntimeException("boom");
            }
        });
        DiscordChatHooks.installPosted((p, raw, ch, id) -> {
            if (p.equals(player)) {
                got.add(raw + "|" + ch + "|" + id);
            }
        });
        DiscordChatHooks.firePosted(player, "hello @dev", "chan", "msg");
        assertEquals(List.of("hello @dev|chan|msg"), got);
    }

    @Test
    void deliverersOrAndIsolateExceptions() {
        String marker = "deliver-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        DiscordChatHooks.installDeliverer((r, c, line) -> {
            if (c.content().equals(marker)) {
                throw new RuntimeException("boom");
            }
            return false;
        });
        DiscordChatHooks.installDeliverer((r, c, line) -> {
            if (c.content().equals(marker)) {
                calls.incrementAndGet();
                return true;
            }
            return false;
        });
        assertTrue(DiscordChatHooks.fireDeliver(null, ctx(marker), null));
        assertEquals(1, calls.get());
        assertFalse(DiscordChatHooks.fireDeliver(null, ctx("nobody-" + UUID.randomUUID()), null));
    }

    @Test
    void reactionListenersIsolateExceptions() {
        String messageId = "r-" + UUID.randomUUID();
        List<ReactionSnapshot> got = new ArrayList<>();
        DiscordReactionHooks.install(s -> {
            if (s.messageId().equals(messageId)) {
                throw new RuntimeException("boom");
            }
        });
        DiscordReactionHooks.install(s -> {
            if (s.messageId().equals(messageId)) {
                got.add(s);
            }
        });
        DiscordReactionHooks.fire(new ReactionSnapshot(messageId, null, null, null));
        DiscordReactionHooks.fire(null);
        assertEquals(1, got.size());
        assertTrue(got.get(0).list().isEmpty());
    }
}
