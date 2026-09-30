package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SurveyEmbedFooterTest {

    private static JsonObject embed() {
        return DiscordService.buildReportEmbed("Title", "Desc", List.of(), 0x123456);
    }

    @Test
    void addsFooterText() {
        JsonObject e = DiscordService.withFooter(embed(), " DT 0.1075.0 ");
        assertEquals("DT 0.1075.0", e.getAsJsonObject("footer").get("text").getAsString());
    }

    @Test
    void blankOrNullLeavesEmbedWithoutFooter() {
        assertFalse(DiscordService.withFooter(embed(), "").has("footer"));
        assertFalse(DiscordService.withFooter(embed(), "   ").has("footer"));
        assertFalse(DiscordService.withFooter(embed(), null).has("footer"));
    }
}
