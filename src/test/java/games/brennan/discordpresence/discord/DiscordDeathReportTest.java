package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the death-report embed, image grid math, and multipart body. */
class DiscordDeathReportTest {

    // --- grid layout -------------------------------------------------------

    @Test
    void gridFitsItemsInOneRowUnderColumnLimit() {
        DeathImageComposer.Dim d = DeathImageComposer.gridDimensions(5, 9, 64, 6);
        assertEquals(5, d.columns());
        assertEquals(1, d.rows());
        assertEquals(6 + 5 * (64 + 6), d.width());
        assertEquals(6 + 1 * (64 + 6), d.height());
    }

    @Test
    void gridWrapsToSecondRowOverColumnLimit() {
        DeathImageComposer.Dim d = DeathImageComposer.gridDimensions(10, 9, 64, 6);
        assertEquals(9, d.columns());
        assertEquals(2, d.rows());
    }

    @Test
    void gridEmptyForZeroItems() {
        DeathImageComposer.Dim d = DeathImageComposer.gridDimensions(0, 9, 64, 6);
        assertEquals(0, d.columns());
        assertEquals(0, d.rows());
        assertEquals(0, d.width());
        assertEquals(0, d.height());
    }

    // --- multipart body ----------------------------------------------------

    @Test
    void multipartBodyCarriesJsonAndPngParts() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG-ish magic
        MultipartBody mb = MultipartBody.jsonWithPng(
                "BOUND", "{\"content\":\"x\"}", "files[0]", "death.png", png);

        assertEquals("multipart/form-data; boundary=BOUND", mb.contentType());
        String body = new String(mb.body(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("--BOUND\r\n"), "opening boundary");
        assertTrue(body.contains("name=\"payload_json\""), "payload_json part");
        assertTrue(body.contains("{\"content\":\"x\"}"), "json content");
        assertTrue(body.contains("name=\"files[0]\"; filename=\"death.png\""), "file part header");
        assertTrue(body.contains("Content-Type: image/png"), "png content-type");
        assertTrue(body.endsWith("--BOUND--\r\n"), "closing boundary");
    }

    // --- embed building ----------------------------------------------------

    @Test
    void embedHasTitleDescriptionColorAndInlineFields() {
        JsonObject embed = DiscordService.buildReportEmbed(
                "💀 Dev", "Dev was killed",
                List.of(new DeathField("DIST", "28 m"), new DeathField("TIME", "0:29")),
                0xFF5555);

        assertEquals("💀 Dev", embed.get("title").getAsString());
        assertEquals("Dev was killed", embed.get("description").getAsString());
        assertEquals(0xFF5555, embed.get("color").getAsInt());
        assertEquals(2, embed.getAsJsonArray("fields").size());
        assertTrue(embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("inline").getAsBoolean());
    }

    @Test
    void embedSkipsBlankFieldsAndOmitsBlankTitle() {
        JsonObject embed = DiscordService.buildReportEmbed(
                null, null,
                List.of(new DeathField("", "x"), new DeathField("ok", "v")),
                0x000000);

        assertFalse(embed.has("title"));
        assertFalse(embed.has("description"));
        assertEquals(1, embed.getAsJsonArray("fields").size());
        assertEquals("ok", embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void embedOmitsFieldsArrayWhenNoneValid() {
        JsonObject embed = DiscordService.buildReportEmbed("t", "d", List.of(), 1);
        assertFalse(embed.has("fields"));
    }
}
