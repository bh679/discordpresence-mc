package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Posts the join message through the configured Discord webhook and parses the
 * created message's {@code id} + {@code channel_id} from the {@code ?wait=true}
 * response, so the bot can later react to it.
 *
 * <p>Webhooks can only post/edit/delete their own messages — they cannot add
 * reactions (that's {@link DiscordBotClient}'s job). Best-effort: every failure
 * resolves to {@code null}; it never throws into game logic.</p>
 */
final class DiscordWebhookClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DISCORD_GREEN = 0x57F287;

    private DiscordWebhookClient() {}

    /**
     * @return a future of the posted message ref, completing with {@code null}
     *         when disabled or on any failure (callers tolerate null).
     */
    static CompletableFuture<DiscordMessageRef> postJoinMessage(String playerName, UUID uuid) {
        String webhookUrl = DiscordPresenceConfig.getWebhookUrl();
        if (webhookUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String content = DiscordPresenceConfig.getJoinMessageTemplate().replace("{player}", playerName);
        String body = buildPayload(content, playerName, uuid);

        HttpRequest req = HttpRequest.newBuilder(URI.create(appendWait(webhookUrl)))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(DiscordWebhookClient::parseMessageRef)
                .exceptionally(t -> {
                    LOGGER.warn("Discord webhook POST failed: {}", t.toString());
                    return null;
                });
    }

    /** {@code wait=true} makes Discord return the created message (with id + channel_id). */
    private static String appendWait(String webhookUrl) {
        return webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true";
    }

    private static DiscordMessageRef parseMessageRef(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code != 200) {
            LOGGER.warn("Discord webhook POST returned HTTP {} (expected 200 with wait=true): {}",
                    code, truncate(resp.body()));
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String id = json.has("id") ? json.get("id").getAsString() : null;
            String channelId = json.has("channel_id") ? json.get("channel_id").getAsString() : null;
            if (id == null || channelId == null) {
                LOGGER.warn("Discord webhook response missing id/channel_id; reactions disabled for this message.");
                return null;
            }
            return new DiscordMessageRef(channelId, id);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Discord webhook response", e);
            return null;
        }
    }

    private static String buildPayload(String content, String playerName, UUID uuid) {
        JsonObject root = new JsonObject();
        // Discord rejects (HTTP 400) any webhook username override containing
        // "discord"/"clyde", so only set it when the player name is allowed —
        // otherwise the message posts under the webhook's default name (the
        // player is still named in the content).
        String username = safeUsername(playerName);
        if (username != null) {
            root.addProperty("username", username);
        }
        // Best-effort player head as the webhook avatar (cosmetic; Discord fetches the URL).
        root.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/64");
        root.addProperty("content", content);

        // Never ping anyone from a player-controlled name/template.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        root.add("allowed_mentions", allowedMentions);
        return root.toString();
    }

    /**
     * The player name if it is a valid Discord webhook username override, else
     * {@code null} to fall back to the webhook's default name. Discord forbids
     * the substrings "discord"/"clyde" (case-insensitive) and caps length at 80.
     */
    static String safeUsername(String playerName) {
        if (playerName == null || playerName.isBlank() || playerName.length() > 80) {
            return null;
        }
        String lower = playerName.toLowerCase(Locale.ROOT);
        if (lower.contains("discord") || lower.contains("clyde")) {
            return null;
        }
        return playerName;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
