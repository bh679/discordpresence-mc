package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure inbound relay-decision + sanitisation logic (no Minecraft runtime needed). */
class DiscordServiceRelayTest {

    private static InboundMessage msg(String channelId, String refId, boolean bot, boolean webhook) {
        return new InboundMessage("alice", "hello", bot, webhook, channelId, refId);
    }

    @Test
    void relaysReplyToTrackedMessage() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        idx.put("join1", UUID.randomUUID());
        assertTrue(DiscordService.isRelayable(msg("chanX", "join1", false, false), idx));
    }

    @Test
    void relaysThreadAnchoredToTrackedMessage() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        idx.put("line5", UUID.randomUUID());
        // message in the thread whose id == the tracked message id (no reply reference)
        assertTrue(DiscordService.isRelayable(msg("line5", null, false, false), idx));
    }

    @Test
    void ignoresUnanchoredMessage() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        idx.put("join1", UUID.randomUUID());
        assertFalse(DiscordService.isRelayable(msg("randomChan", "otherMsg", false, false), idx));
    }

    @Test
    void ignoresBotAndWebhookEvenWhenAnchored() {
        PlayerMessageIndex idx = new PlayerMessageIndex(10);
        idx.put("join1", UUID.randomUUID());
        assertFalse(DiscordService.isRelayable(msg("chanX", "join1", true, false), idx));   // bot
        assertFalse(DiscordService.isRelayable(msg("join1", null, false, true), idx));       // webhook
    }

    @Test
    void ignoresNull() {
        assertFalse(DiscordService.isRelayable(null, new PlayerMessageIndex(10)));
    }

    @Test
    void sanitizeStripsNewlinesAndCaps() {
        assertEquals("a b c", DiscordService.sanitize("a\nb\rc"));
        String out = DiscordService.sanitize("x".repeat(300));
        assertTrue(out.length() <= 257); // 256 chars + the ellipsis
        assertTrue(out.endsWith("…"));
        assertEquals("", DiscordService.sanitize(null));
    }
}
