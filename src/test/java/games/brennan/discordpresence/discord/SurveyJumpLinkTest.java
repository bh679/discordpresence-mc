package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests for the survey-results copy jump-link + content composer. */
class SurveyJumpLinkTest {

    @Test
    void url_buildsWellFormedJumpLink() {
        assertEquals("https://discord.com/channels/111/222/333",
                SurveyJumpLink.url("111", "222", "333"));
    }

    @Test
    void url_blankOrNullPart_returnsNull() {
        assertNull(SurveyJumpLink.url(null, "222", "333"));
        assertNull(SurveyJumpLink.url("", "222", "333"));
        assertNull(SurveyJumpLink.url("  ", "222", "333"));
        assertNull(SurveyJumpLink.url("111", null, "333"));
        assertNull(SurveyJumpLink.url("111", "222", null));
        assertNull(SurveyJumpLink.url("111", "222", ""));
    }

    @Test
    void content_wrapsUrl() {
        assertEquals("🧵 Originally posted in the player's thread → https://discord.com/channels/1/2/3",
                SurveyJumpLink.content("https://discord.com/channels/1/2/3"));
    }

    @Test
    void content_blankOrNull_returnsNull() {
        assertNull(SurveyJumpLink.content(null));
        assertNull(SurveyJumpLink.content(""));
        assertNull(SurveyJumpLink.content("   "));
    }
}
