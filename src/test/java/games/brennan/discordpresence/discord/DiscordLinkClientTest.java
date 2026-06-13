package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.discord.DiscordLinkClient.ChannelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for parsing the Discord messages payload + the missing-intent heuristic. */
class DiscordLinkClientTest {

    @Test
    void parsesIdAuthorBotAndContent() {
        String json = """
                [
                  {"id":"111","content":"ABC234","author":{"id":"222","bot":false}},
                  {"id":"333","content":"hi","author":{"id":"444","bot":true}}
                ]""";
        List<ChannelMessage> msgs = DiscordLinkClient.parseMessages(json);
        assertEquals(2, msgs.size());

        ChannelMessage first = msgs.get(0);
        assertEquals("111", first.id());
        assertEquals("222", first.authorId());
        assertFalse(first.authorBot());
        assertEquals("ABC234", first.content());

        assertTrue(msgs.get(1).authorBot());
    }

    @Test
    void toleratesMissingContentAndAuthorBot() {
        // content absent (intent off) and bot flag absent → "" content, non-bot.
        String json = "[{\"id\":\"1\",\"author\":{\"id\":\"2\"}}]";
        List<ChannelMessage> msgs = DiscordLinkClient.parseMessages(json);
        assertEquals(1, msgs.size());
        assertEquals("", msgs.get(0).content());
        assertFalse(msgs.get(0).authorBot());
    }

    @Test
    void skipsMessagesMissingIdOrAuthor() {
        String json = """
                [
                  {"content":"no id","author":{"id":"2"}},
                  {"id":"3","content":"no author"}
                ]""";
        assertTrue(DiscordLinkClient.parseMessages(json).isEmpty());
    }

    @Test
    void malformedJsonYieldsEmptyList() {
        assertTrue(DiscordLinkClient.parseMessages("not json").isEmpty());
    }

    @Test
    void allContentBlankDetectsMissingIntent() {
        List<ChannelMessage> blank = List.of(
                new ChannelMessage("1", "2", false, ""),
                new ChannelMessage("3", "4", false, "   "));
        assertTrue(DiscordLinkClient.allContentBlank(blank), "all-empty content signals the intent is off");

        List<ChannelMessage> mixed = List.of(
                new ChannelMessage("1", "2", false, ""),
                new ChannelMessage("3", "4", false, "ABC234"));
        assertFalse(DiscordLinkClient.allContentBlank(mixed));

        assertFalse(DiscordLinkClient.allContentBlank(List.of()), "no messages is not a signal");
    }
}
