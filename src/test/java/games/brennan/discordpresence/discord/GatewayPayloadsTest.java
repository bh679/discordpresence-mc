package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protocol encoding/decoding for the Discord gateway — pure, no live connection. */
class GatewayPayloadsTest {

    private static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void intentsAreGuildsGuildMessagesAndMessageContent() {
        assertEquals(1 + 512 + 32768, GatewayPayloads.INTENTS);
        assertEquals(33281, GatewayPayloads.INTENTS);
    }

    @Test
    void identifyCarriesTokenIntentsAndProperties() {
        JsonObject id = parse(GatewayPayloads.identify("tok123"));
        assertEquals(GatewayPayloads.OP_IDENTIFY, id.get("op").getAsInt());
        JsonObject d = id.getAsJsonObject("d");
        assertEquals("tok123", d.get("token").getAsString());
        assertEquals(GatewayPayloads.INTENTS, d.get("intents").getAsInt());
        assertTrue(d.getAsJsonObject("properties").has("os"));
    }

    @Test
    void resumeCarriesSessionAndSeq() {
        JsonObject r = parse(GatewayPayloads.resume("tok", "sess-1", 42));
        assertEquals(GatewayPayloads.OP_RESUME, r.get("op").getAsInt());
        JsonObject d = r.getAsJsonObject("d");
        assertEquals("tok", d.get("token").getAsString());
        assertEquals("sess-1", d.get("session_id").getAsString());
        assertEquals(42, d.get("seq").getAsInt());
    }

    @Test
    void heartbeatUsesSeqOrNull() {
        JsonObject h = parse(GatewayPayloads.heartbeat(7));
        assertEquals(GatewayPayloads.OP_HEARTBEAT, h.get("op").getAsInt());
        assertEquals(7, h.get("d").getAsInt());

        JsonObject none = parse(GatewayPayloads.heartbeat(null));
        assertTrue(none.get("d").isJsonNull());
    }

    @Test
    void parsesHelloInterval() {
        JsonObject hello = parse("{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}");
        assertEquals(GatewayPayloads.OP_HELLO, GatewayPayloads.opOf(hello));
        assertEquals(41250L, GatewayPayloads.helloInterval(hello));
    }

    @Test
    void readsOpSeqAndType() {
        JsonObject dispatch = parse("{\"op\":0,\"s\":15,\"t\":\"MESSAGE_CREATE\",\"d\":{}}");
        assertEquals(0, GatewayPayloads.opOf(dispatch));
        assertEquals(Integer.valueOf(15), GatewayPayloads.seqOf(dispatch));
        assertEquals("MESSAGE_CREATE", GatewayPayloads.typeOf(dispatch));

        JsonObject ack = parse("{\"op\":11}");
        assertNull(GatewayPayloads.seqOf(ack));
        assertNull(GatewayPayloads.typeOf(ack));
    }

    @Test
    void invalidSessionResumableFlag() {
        assertTrue(GatewayPayloads.invalidSessionResumable(parse("{\"op\":9,\"d\":true}")));
        assertFalse(GatewayPayloads.invalidSessionResumable(parse("{\"op\":9,\"d\":false}")));
    }

    @Test
    void parsesMessageCreateFields() {
        String json = "{\"content\":\"hi there\",\"channel_id\":\"chan99\","
                + "\"author\":{\"username\":\"alice\",\"global_name\":\"Alice\"},"
                + "\"message_reference\":{\"message_id\":\"msg42\"}}";
        InboundMessage m = GatewayPayloads.message(parse(json));
        assertEquals("hi there", m.content());
        assertEquals("chan99", m.channelId());
        assertEquals("Alice", m.authorName()); // prefers global_name over username
        assertEquals("msg42", m.referencedMessageId());
        assertFalse(m.bot());
        assertFalse(m.hasWebhookId());
        assertFalse(m.isOwnOrBot());
    }

    @Test
    void detectsBotAndWebhookAuthors() {
        InboundMessage bot = GatewayPayloads.message(parse(
                "{\"content\":\"x\",\"author\":{\"username\":\"b\",\"bot\":true}}"));
        assertTrue(bot.bot());
        assertTrue(bot.isOwnOrBot());

        InboundMessage hook = GatewayPayloads.message(parse(
                "{\"content\":\"x\",\"webhook_id\":\"123\",\"author\":{\"username\":\"w\"}}"));
        assertTrue(hook.hasWebhookId());
        assertTrue(hook.isOwnOrBot());
    }

    @Test
    void fallsBackToUsernameWhenNoGlobalName() {
        InboundMessage m = GatewayPayloads.message(parse(
                "{\"content\":\"x\",\"author\":{\"username\":\"bob\"}}"));
        assertEquals("bob", m.authorName());
    }

    @Test
    void toleratesMissingFields() {
        InboundMessage m = GatewayPayloads.message(parse("{}"));
        assertEquals("", m.content()); // blank, never null
        assertEquals("", m.authorName());
        assertNull(m.channelId());
        assertNull(m.referencedMessageId());
        assertFalse(m.isOwnOrBot());
    }
}
