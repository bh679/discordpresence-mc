package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the relay's gateway JSON into {@link InboundMessage} (pure, no socket). */
class RelayGatewayParseTest {

    @Test
    void parsesAllSixFields() {
        InboundMessage m = RelayGateway.parse(
                "{\"authorName\":\"Bob\",\"content\":\"hello\",\"bot\":false,\"hasWebhookId\":false,"
                        + "\"channelId\":\"123\",\"referencedMessageId\":\"456\"}");
        assertEquals("Bob", m.authorName());
        assertEquals("hello", m.content());
        assertFalse(m.bot());
        assertFalse(m.hasWebhookId());
        assertEquals("123", m.channelId());
        assertEquals("456", m.referencedMessageId());
    }

    @Test
    void toleratesMissingFields() {
        InboundMessage m = RelayGateway.parse("{\"content\":\"hi\"}");
        assertEquals("", m.authorName());
        assertEquals("hi", m.content());
        assertFalse(m.bot());
        assertFalse(m.hasWebhookId());
        assertNull(m.channelId());
        assertNull(m.referencedMessageId());
    }

    @Test
    void botAndWebhookFlagsDriveIsOwnOrBot() {
        assertTrue(RelayGateway.parse("{\"authorName\":\"X\",\"bot\":true}").isOwnOrBot());
        assertTrue(RelayGateway.parse("{\"authorName\":\"X\",\"hasWebhookId\":true}").isOwnOrBot());
        assertFalse(RelayGateway.parse("{\"authorName\":\"X\"}").isOwnOrBot());
    }

    @Test
    void nullJsonFieldsTreatedAsAbsent() {
        InboundMessage m = RelayGateway.parse(
                "{\"authorName\":null,\"content\":null,\"channelId\":null,\"referencedMessageId\":null}");
        assertEquals("", m.authorName());
        assertEquals("", m.content());
        assertNull(m.channelId());
        assertNull(m.referencedMessageId());
    }
}
