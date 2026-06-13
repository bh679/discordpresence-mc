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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bot-REST operations for the per-player thread: creating a public thread from a
 * message, and posting messages (advancements) into it.
 *
 * <p>Both require a BOT TOKEN plus channel permissions — webhooks cannot create
 * threads. Create-thread needs <b>Create Public Threads</b>; posting into the
 * thread needs <b>Send Messages in Threads</b>. No gateway/intents required.</p>
 *
 * <p>Best-effort: every failure resolves to {@code null} and is logged; it never
 * throws into game logic. Mirrors {@link DiscordBotClient}'s REST style.</p>
 */
final class DiscordThreadClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String API = "https://discord.com/api/v10";
    private static final int MAX_THREAD_NAME = 100; // Discord's limit

    /** One-shot WARN de-dupe so a misconfigured token/perms doesn't spam the log. */
    private static final AtomicBoolean WARNED_AUTH = new AtomicBoolean(false);

    private DiscordThreadClient() {}

    /**
     * Create a public thread anchored to {@code anchor}.
     *
     * @return a future of the new thread's id (itself a channel id), completing
     *         with {@code null} when disabled or on any failure.
     */
    static CompletableFuture<String> createThreadFromMessage(DiscordMessageRef anchor, String name, int autoArchiveMinutes) {
        if (anchor == null) {
            return CompletableFuture.completedFuture(null);
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject body = new JsonObject();
        body.addProperty("name", threadName(name));
        body.addProperty("auto_archive_duration", autoArchiveMinutes);

        URI uri = URI.create(API + "/channels/" + anchor.channelId()
                + "/messages/" + anchor.messageId() + "/threads");

        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(DiscordThreadClient::parseThreadId)
                .exceptionally(t -> {
                    LOGGER.warn("Discord create-thread failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post an advancement message into the channel/thread as the bot: an optional
     * {@code content} attribution line plus a coloured embed (title + description),
     * the embed colour matching the in-game advancement frame.
     *
     * @return a future of the posted message ref, or {@code null} on failure.
     */
    static CompletableFuture<DiscordMessageRef> postEmbed(String channelId, String content,
                                                          String title, String description, Integer color) {
        if (channelId == null) {
            return CompletableFuture.completedFuture(null);
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject embed = new JsonObject();
        if (title != null && !title.isBlank()) {
            embed.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            embed.addProperty("description", description);
        }
        if (color != null) {
            embed.addProperty("color", color); // 0xRRGGBB
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject body = new JsonObject();
        if (content != null && !content.isBlank()) {
            body.addProperty("content", content);
        }
        body.add("embeds", embeds);
        // Never ping anyone from a player-controlled name/template.
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
                .thenApply(DiscordThreadClient::parseMessageRef)
                .exceptionally(t -> {
                    LOGGER.warn("Discord thread post failed: {}", t.toString());
                    return null;
                });
    }

    private static String parseThreadId(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code != 200 && code != 201) {
            handleError(code, resp);
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            return json.has("id") ? json.get("id").getAsString() : null;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Discord create-thread response", e);
            return null;
        }
    }

    private static DiscordMessageRef parseMessageRef(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code != 200 && code != 201) {
            handleError(code, resp);
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String id = json.has("id") ? json.get("id").getAsString() : null;
            String channelId = json.has("channel_id") ? json.get("channel_id").getAsString() : null;
            if (id == null || channelId == null) {
                return null;
            }
            return new DiscordMessageRef(channelId, id);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Discord thread-message response", e);
            return null;
        }
    }

    private static void handleError(int code, HttpResponse<String> resp) {
        switch (code) {
            case 401 -> warnOnce("Discord bot token rejected (401) — check 'botToken' in discordpresence-server.toml.");
            case 403 -> warnOnce("Discord bot lacks permission (403) — grant it Create Public Threads + "
                    + "Send Messages in Threads in the channel.");
            case 429 -> LOGGER.warn("Discord rate-limited the thread request (429), retry-after={}s — dropping it.",
                    resp.headers().firstValue("retry-after").orElse("?"));
            case 404 -> LOGGER.warn("Discord thread target not found (404) — message/channel/thread deleted: {}",
                    truncate(resp.body()));
            default -> LOGGER.warn("Discord thread API returned HTTP {}: {}", code, truncate(resp.body()));
        }
    }

    private static void warnOnce(String msg) {
        if (WARNED_AUTH.compareAndSet(false, true)) {
            LOGGER.warn(msg);
        }
    }

    private static String threadName(String name) {
        if (name == null || name.isBlank()) {
            return "thread";
        }
        return name.length() > MAX_THREAD_NAME ? name.substring(0, MAX_THREAD_NAME) : name;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
