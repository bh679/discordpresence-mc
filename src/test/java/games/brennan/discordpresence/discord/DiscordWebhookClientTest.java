package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the Discord webhook-username sanitiser + the durable-resend body builder. */
class DiscordWebhookClientTest {

    @Test
    void allowsNormalPlayerNames() {
        assertEquals("Steve", DiscordWebhookClient.safeUsername("Steve"));
        assertEquals("Notch_99", DiscordWebhookClient.safeUsername("Notch_99"));
    }

    @Test
    void rejectsDiscordAndClydeSubstrings() {
        // Discord returns HTTP 400 for these — must fall back to the webhook name.
        assertNull(DiscordWebhookClient.safeUsername("discordfan"));
        assertNull(DiscordWebhookClient.safeUsername("ProDISCORDgg"));
        assertNull(DiscordWebhookClient.safeUsername("clyde"));
        assertNull(DiscordWebhookClient.safeUsername("xX_Clyde_Xx"));
    }

    @Test
    void rejectsBlankOrOverlong() {
        assertNull(DiscordWebhookClient.safeUsername(null));
        assertNull(DiscordWebhookClient.safeUsername("   "));
        assertNull(DiscordWebhookClient.safeUsername("a".repeat(81)));
    }

    // --- durableBody: the JSON re-queued for a durable resend ---------------

    @Test
    void durableBodyStripsAttachmentImageWhenPostWasMultipart() {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Died to a creeper");
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://report.png"); // the composed gear PNG the live post attached
        embed.add("image", image);
        JsonObject root = DiscordWebhookClient.buildReportRoot("Steve", UUID.randomUUID(), embed, null, List.of());

        // On replay the PNG isn't persisted → the embed must render without the broken attachment ref.
        JsonObject parsed = JsonParser.parseString(DiscordWebhookClient.durableBody(root, true)).getAsJsonObject();
        JsonObject e0 = parsed.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertFalse(e0.has("image"), "attachment:// image must be stripped for the image-less replay");
        assertEquals("Died to a creeper", e0.get("title").getAsString(), "the rest of the embed is preserved");
    }

    @Test
    void durableBodyLeavesPlainJsonAndHotlinkedImagesUntouched() {
        JsonObject embed = new JsonObject();
        JsonObject image = new JsonObject();
        image.addProperty("url", "https://example.com/pic.png"); // hotlinked, survives a replay fine
        embed.add("image", image);
        JsonObject root = DiscordWebhookClient.buildReportRoot("Steve", UUID.randomUUID(), embed, null, List.of());

        // Non-multipart send → returned verbatim.
        assertEquals(root.toString(), DiscordWebhookClient.durableBody(root, false));
        // Even flagged as multipart, a non-attachment image is not stripped.
        JsonObject parsed = JsonParser.parseString(DiscordWebhookClient.durableBody(root, true)).getAsJsonObject();
        JsonObject e0 = parsed.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(e0.has("image"), "a hotlinked embed image is preserved");
    }

    // --- dead-thread detection (a post into a thread the relay's reaper, or a human, deleted) ---

    @Test
    void readsDiscordErrorCode() {
        assertEquals(DiscordWebhookClient.UNKNOWN_CHANNEL,
                DiscordWebhookClient.discordErrorCode("{\"message\":\"Unknown Channel\",\"code\":10003}"));
        // A 404 can equally mean the webhook itself is gone — that must NOT evict the player's thread.
        assertEquals(10015, DiscordWebhookClient.discordErrorCode("{\"message\":\"Unknown Webhook\",\"code\":10015}"));
    }

    @Test
    void unparseableErrorBodiesYieldNoCode() {
        assertEquals(0, DiscordWebhookClient.discordErrorCode(null));
        assertEquals(0, DiscordWebhookClient.discordErrorCode(""));
        assertEquals(0, DiscordWebhookClient.discordErrorCode("<html>502 Bad Gateway</html>"));
        assertEquals(0, DiscordWebhookClient.discordErrorCode("[1,2,3]"));
        assertEquals(0, DiscordWebhookClient.discordErrorCode("{\"message\":\"nope\"}"));
        assertEquals(0, DiscordWebhookClient.discordErrorCode("{\"code\":\"not-a-number\"}"));
    }

    @Test
    void dropsThreadIdForTheTopLevelRetry() {
        // The dead-thread fallback re-sends the same request with a null thread id; it must still
        // ask for the created message (wait=true) and must not carry the gone thread.
        String url = "https://discord.com/api/webhooks/1/tok";
        assertEquals(url + "?wait=true&thread_id=thread-9", DiscordWebhookClient.withQuery(url, "thread-9"));
        String topLevel = DiscordWebhookClient.withQuery(url, null);
        assertFalse(topLevel.contains("thread_id"), "retry must not re-aim at the deleted thread");
        assertTrue(topLevel.contains("wait=true"));
    }
}
