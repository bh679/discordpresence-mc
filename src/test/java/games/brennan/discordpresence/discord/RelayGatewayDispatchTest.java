package games.brennan.discordpresence.discord;

import com.google.gson.JsonParser;
import games.brennan.discordpresence.compat.DiscordReactionHooks.Reaction;
import games.brennan.discordpresence.compat.DiscordReactionHooks.ReactionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Typed-frame routing in {@link RelayGateway#dispatch} and defensive reactions parsing (pure, no socket). */
class RelayGatewayDispatchTest {

    private final List<InboundMessage> messages = new ArrayList<>();
    private final List<ReactionSnapshot> reactions = new ArrayList<>();

    private RelayGateway.FrameKind dispatch(String json) {
        return RelayGateway.dispatch(json, messages::add, reactions::add);
    }

    @Test
    void untypedFrameIsAMessageAndCarriesId() {
        assertEquals(RelayGateway.FrameKind.MESSAGE, dispatch(
                "{\"id\":\"999\",\"authorId\":\"1\",\"authorName\":\"Bob\",\"content\":\"hi\","
                        + "\"timestamp\":\"2026-10-15T00:00:00Z\",\"bot\":false,\"hasWebhookId\":false,"
                        + "\"channelId\":\"123\",\"referencedMessageId\":null}"));
        assertEquals(1, messages.size());
        assertTrue(reactions.isEmpty());
        InboundMessage m = messages.get(0);
        assertEquals("999", m.messageId());
        assertEquals("1", m.authorId());
        assertEquals("123", m.channelId());
    }

    @Test
    void messageWithoutIdHasNullMessageId() {
        assertEquals(RelayGateway.FrameKind.MESSAGE, dispatch("{\"content\":\"hi\"}"));
        assertNull(messages.get(0).messageId());
    }

    @Test
    void reactionsFrameIsParsedAndFired() {
        assertEquals(RelayGateway.FrameKind.REACTIONS, dispatch(
                "{\"type\":\"reactions\",\"reactions\":{\"messageId\":\"55\",\"channelId\":\"66\","
                        + "\"list\":[{\"name\":\"👍\",\"id\":null,\"animated\":false,\"count\":2,"
                        + "\"users\":[\"Brennan\",\"Sam\"]},"
                        + "{\"name\":\"party\",\"id\":\"777\",\"animated\":true,\"count\":1,\"users\":[\"Sam\"]}],"
                        + "\"event\":{\"action\":\"add\",\"name\":\"👍\",\"id\":null,\"animated\":false,"
                        + "\"userName\":\"Brennan\"}}}"));
        assertTrue(messages.isEmpty());
        assertEquals(1, reactions.size());
        ReactionSnapshot s = reactions.get(0);
        assertEquals("55", s.messageId());
        assertEquals("66", s.channelId());
        assertEquals(2, s.list().size());
        Reaction thumbs = s.list().get(0);
        assertEquals("👍", thumbs.name());
        assertNull(thumbs.id());
        assertEquals(2, thumbs.count());
        assertEquals(List.of("Brennan", "Sam"), thumbs.users());
        Reaction custom = s.list().get(1);
        assertEquals("777", custom.id());
        assertTrue(custom.animated());
        assertNotNull(s.event());
        assertEquals("add", s.event().action());
        assertEquals("Brennan", s.event().userName());
    }

    @Test
    void clearEventWithEmptyList() {
        dispatch("{\"type\":\"reactions\",\"reactions\":{\"messageId\":\"55\",\"list\":[],"
                + "\"event\":{\"action\":\"clear\"}}}");
        ReactionSnapshot s = reactions.get(0);
        assertTrue(s.list().isEmpty());
        assertNull(s.channelId());
        assertEquals("clear", s.event().action());
        assertEquals("", s.event().name());
    }

    @Test
    void unknownTypeIsIgnored() {
        assertEquals(RelayGateway.FrameKind.IGNORED,
                dispatch("{\"type\":\"typing\",\"content\":\"should not relay\"}"));
        assertTrue(messages.isEmpty());
        assertTrue(reactions.isEmpty());
    }

    @Test
    void malformedFramesAreDroppedWithoutThrowing() {
        assertEquals(RelayGateway.FrameKind.MALFORMED, dispatch("not json"));
        assertEquals(RelayGateway.FrameKind.MALFORMED, dispatch("{\"type\":\"reactions\"}"));
        assertEquals(RelayGateway.FrameKind.MALFORMED, dispatch("{\"type\":\"reactions\",\"reactions\":[]}"));
        assertEquals(RelayGateway.FrameKind.MALFORMED,
                dispatch("{\"type\":\"reactions\",\"reactions\":{\"list\":[]}}"));
        assertTrue(messages.isEmpty());
        assertTrue(reactions.isEmpty());
    }

    @Test
    void malformedEntriesSkippedAndBadEventDropped() {
        dispatch("{\"type\":\"reactions\",\"reactions\":{\"messageId\":\"1\",\"list\":["
                + "42, {\"id\":\"5\"}, {\"name\":\"ok\",\"count\":\"lots\",\"users\":[null,{},\"A\"]}],"
                + "\"event\":{\"action\":\"explode\",\"name\":\"x\"}}}");
        ReactionSnapshot s = reactions.get(0);
        assertEquals(1, s.list().size());
        assertEquals("ok", s.list().get(0).name());
        assertEquals(List.of("A"), s.list().get(0).users());
        assertEquals(1, s.list().get(0).count()); // count floored at users seen
        assertNull(s.event());
    }

    @Test
    void listSizesAndStringLengthsAreClamped() {
        StringBuilder list = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            StringBuilder users = new StringBuilder("[");
            for (int u = 0; u < 80; u++) {
                users.append(u == 0 ? "" : ",").append("\"u").append(u).append('"');
            }
            users.append(']');
            list.append(i == 0 ? "" : ",").append("{\"name\":\"e").append(i).append("\",\"count\":80,\"users\":")
                    .append(users).append('}');
        }
        list.append(']');
        String longName = "n".repeat(500);
        ReactionSnapshot s = RelayGateway.parseReactions(JsonParser.parseString(
                "{\"type\":\"reactions\",\"reactions\":{\"messageId\":\"" + "9".repeat(100) + "\",\"list\":" + list
                        + ",\"event\":{\"action\":\"add\",\"name\":\"" + longName + "\",\"userName\":\""
                        + longName + "\"}}}").getAsJsonObject());
        assertNotNull(s);
        assertEquals(RelayGateway.MAX_EMOJIS, s.list().size());
        assertEquals(RelayGateway.MAX_USERS, s.list().get(0).users().size());
        assertEquals(80, s.list().get(0).count());
        assertEquals(RelayGateway.MAX_ID_LEN, s.messageId().length());
        assertEquals(RelayGateway.MAX_NAME_LEN, s.event().name().length());
        assertEquals(RelayGateway.MAX_NAME_LEN, s.event().userName().length());
        assertFalse(s.list().get(0).animated());
    }
}
