package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot-REST operations for the account-link channel: reading recent messages (to
 * find a posted code), deleting a consumed code message, and posting a plain
 * confirmation. Mirrors {@link DiscordThreadClient}'s REST style + error handling.
 *
 * <p><b>Requires the Message Content privileged intent.</b> Without it Discord
 * returns messages with an empty {@code content} field over REST, so a posted
 * code can never match. {@link #allContentBlank} detects this and
 * {@link LinkService} surfaces a one-shot WARN. Permissions needed in the
 * channel: View Channel, Read Message History, and Manage Messages (only if
 * deleting code messages).</p>
 *
 * <p>Best-effort: every failure resolves to an empty list / no-op and is logged;
 * it never throws into game logic.</p>
 */
final class DiscordLinkClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "https://discord.com/api/v10";

    /** One-shot WARN de-dupe so a misconfigured token/perms doesn't spam the log. */
    private static final AtomicBoolean WARNED_AUTH = new AtomicBoolean(false);

    private DiscordLinkClient() {}

    /** A single Discord message reduced to the fields the link flow needs. */
    record ChannelMessage(String id, String authorId, boolean authorBot, String content) {}

    /**
     * Fetch the most recent {@code limit} messages in {@code channelId} (newest
     * first). Best-effort → empty list when disabled or on any failure.
     */
    static CompletableFuture<List<ChannelMessage>> fetchMessages(String channelId, int limit) {
        if (channelId == null || channelId.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        URI uri = URI.create(API + "/channels/" + channelId + "/messages?limit=" + limit);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .GET()
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(DiscordLinkClient::parseResponse)
                .exceptionally(t -> {
                    LOGGER.warn("Discord link fetch-messages failed: {}", t.toString());
                    return List.of();
                });
    }

    /** Delete a consumed code message (needs Manage Messages). Best-effort. */
    static CompletableFuture<Void> deleteMessage(String channelId, String messageId) {
        if (channelId == null || channelId.isBlank() || messageId == null || messageId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        URI uri = URI.create(API + "/channels/" + channelId + "/messages/" + messageId);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .DELETE()
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    int code = resp.statusCode();
                    if (code != 204 && code != 200) {
                        handleError(code, resp);
                    }
                })
                .exceptionally(t -> {
                    LOGGER.warn("Discord link delete-message failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post a plain, non-pinging confirmation message into the channel. Any
     * {@code <@id>} renders as a chip but never notifies ({@code allowed_mentions}
     * parses nothing). Best-effort.
     */
    static CompletableFuture<Void> postPlainMessage(String channelId, String content) {
        if (channelId == null || channelId.isBlank() || content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);

        URI uri = URI.create(API + "/channels/" + channelId + "/messages");
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    int code = resp.statusCode();
                    if (code != 200 && code != 201) {
                        handleError(code, resp);
                    }
                })
                .exceptionally(t -> {
                    LOGGER.warn("Discord link confirm-message failed: {}", t.toString());
                    return null;
                });
    }

    private static List<ChannelMessage> parseResponse(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code != 200) {
            handleError(code, resp);
            return List.of();
        }
        return parseMessages(resp.body());
    }

    /**
     * Pure: parse a Discord messages-array JSON body into records. Tolerates a
     * missing/null {@code content} (the Message Content intent being off, or a
     * non-text message) by yielding an empty string.
     */
    static List<ChannelMessage> parseMessages(String body) {
        try {
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
            List<ChannelMessage> out = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String id = stringOrNull(o, "id");
                String content = o.has("content") && !o.get("content").isJsonNull()
                        ? o.get("content").getAsString() : "";
                JsonObject author = o.has("author") && o.get("author").isJsonObject()
                        ? o.getAsJsonObject("author") : null;
                String authorId = author != null ? stringOrNull(author, "id") : null;
                boolean bot = author != null && author.has("bot")
                        && !author.get("bot").isJsonNull() && author.get("bot").getAsBoolean();
                if (id != null && authorId != null) {
                    out.add(new ChannelMessage(id, authorId, bot, content));
                }
            }
            return out;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Discord messages response", e);
            return List.of();
        }
    }

    /**
     * True when messages were returned but every one has blank content — the
     * tell-tale of the Message Content intent being disabled.
     */
    static boolean allContentBlank(List<ChannelMessage> msgs) {
        if (msgs.isEmpty()) {
            return false;
        }
        for (ChannelMessage m : msgs) {
            if (!m.content().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String stringOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static void handleError(int code, HttpResponse<String> resp) {
        switch (code) {
            case 401 -> warnOnce("Discord bot token rejected (401) — check 'botToken' in discordpresence-server.toml.");
            case 403 -> warnOnce("Discord bot lacks permission (403) — grant it View Channel + Read Message History "
                    + "(+ Manage Messages to delete code messages) in the link channel.");
            case 429 -> LOGGER.warn("Discord rate-limited the link request (429), retry-after={}s — dropping it.",
                    resp.headers().firstValue("retry-after").orElse("?"));
            case 404 -> LOGGER.warn("Discord link channel/message not found (404) — check 'linkChannelId': {}",
                    truncate(resp.body()));
            default -> LOGGER.warn("Discord link API returned HTTP {}: {}", code, truncate(resp.body()));
        }
    }

    private static void warnOnce(String msg) {
        if (WARNED_AUTH.compareAndSet(false, true)) {
            LOGGER.warn(msg);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
