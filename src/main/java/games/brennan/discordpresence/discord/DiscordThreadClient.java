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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int MAX_THREAD_NAME = 100; // Discord's limit

    /** One-shot WARN de-dupe so a misconfigured token/perms doesn't spam the log. */
    private static final AtomicBoolean WARNED_AUTH = new AtomicBoolean(false);

    /**
     * Targets already warned about with a 404, so one deleted thread doesn't log on every advancement.
     * Keyed per target rather than globally (as {@link #WARNED_AUTH} is) so a dead thread can't mute the
     * warning for every other thread; bounded, and cleared wholesale when full — the worst case is one
     * repeated warning on a server churning through hundreds of deleted targets.
     */
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();
    private static final int MAX_WARNED_MISSING = 256;

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
        if (DiscordHttp.botUnavailable()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject body = new JsonObject();
        body.addProperty("name", threadName(name));
        body.addProperty("auto_archive_duration", autoArchiveMinutes);

        URI uri = URI.create(DiscordPresenceConfig.getBotApiBase() + "/channels/" + anchor.channelId()
                + "/messages/" + anchor.messageId() + "/threads");

        HttpRequest req = DiscordHttp.botRequest(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseThreadId(resp, anchor.messageId()))
                .exceptionally(t -> {
                    LOGGER.warn("Discord create-thread failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Resolve a thread's top-level <b>anchor</b> message ref — the message the thread
     * was started from. A message-thread's id equals its source message id, and that
     * source message lives in the thread's parent channel, so the anchor ref is
     * {@code (parent_id, threadId)}. Used to react on the top-level thread message for
     * threads persisted before the parent channel was stored. {@code GET /channels/{threadId}}.
     *
     * @return a future of {@code DiscordMessageRef(parent_id, threadId)}, completing
     *         with {@code null} when disabled, not a message-thread, or on any failure.
     */
    static CompletableFuture<DiscordMessageRef> fetchAnchorRef(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (DiscordHttp.botUnavailable()) {
            return CompletableFuture.completedFuture(null);
        }

        URI uri = URI.create(DiscordPresenceConfig.getBotApiBase() + "/channels/" + threadId);

        HttpRequest req = DiscordHttp.botRequest(uri)
                .GET()
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseAnchorRef(resp, threadId))
                .exceptionally(t -> {
                    LOGGER.warn("Discord fetch-thread-parent failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post an advancement message into the channel/thread as the bot: an optional
     * {@code content} attribution line plus a coloured embed (title + description,
     * with an optional {@code iconUrl} thumbnail at the top-right and optional
     * {@code fields}, e.g. the advancement's requirements), the embed colour
     * matching the in-game advancement frame.
     *
     * <p>{@code advancementId} is the earned advancement's registry id
     * ({@code dungeontrain:dungeon_train/root}). It is sent as the {@code X-DT-Advancement} request
     * header, and ONLY in relay mode — the relay reads it for its own analytics and does not forward
     * it, whereas a direct-to-Discord post has no reason to carry it. The embed itself is unchanged,
     * so players see exactly what they saw before.
     *
     * <p>Why a header rather than something in the embed: the embed carries the advancement's DISPLAY
     * TITLE, which is rendered in this server's locale, so it identifies an advancement only to a
     * reader who knows that locale. Analytics keyed off it counted English servers and silently
     * dropped the rest.
     *
     * @return a future of the posted message ref, or {@code null} on failure.
     */
    static CompletableFuture<DiscordMessageRef> postEmbed(String channelId, String content,
                                                          String title, String description, Integer color,
                                                          String iconUrl, List<DeathField> fields,
                                                          String advancementId) {
        if (channelId == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (DiscordHttp.botUnavailable()) {
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
        if (iconUrl != null && !iconUrl.isBlank()) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", iconUrl);
            embed.add("thumbnail", thumbnail); // ~80px image, top-right of the embed
        }
        if (fields != null && !fields.isEmpty()) {
            JsonArray fieldArr = new JsonArray();
            for (DeathField f : fields) {
                if (f == null || f.name() == null || f.value() == null
                        || f.name().isBlank() || f.value().isBlank()) {
                    continue;
                }
                JsonObject jf = new JsonObject();
                jf.addProperty("name", f.name());
                jf.addProperty("value", f.value());
                jf.addProperty("inline", false); // full-width block (the requirements list)
                fieldArr.add(jf);
            }
            if (!fieldArr.isEmpty()) {
                embed.add("fields", fieldArr);
            }
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

        URI uri = URI.create(DiscordPresenceConfig.getBotApiBase() + "/channels/" + channelId + "/messages");

        HttpRequest.Builder reqBuilder = DiscordHttp.botRequest(uri)
                .header("Content-Type", "application/json");
        if (advancementId != null && !advancementId.isBlank() && DiscordPresenceConfig.isRelayMode()) {
            reqBuilder.header("X-DT-Advancement", advancementId);
        }
        HttpRequest req = reqBuilder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseMessageRef(resp, channelId))
                .exceptionally(t -> {
                    LOGGER.warn("Discord thread post failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post a plain-text message (no embed) into the channel/thread as the bot. Used for a follow-up
     * line below an advancement embed (e.g. a bundling mod's game-state line): posting it as the same
     * bot author, chained immediately after the embed, makes Discord group the two under one author
     * header, so it renders as a line directly below the embed box. {@code content}-only; never pings.
     *
     * @return a future of the posted message ref, or {@code null} on failure / nothing to post.
     */
    static CompletableFuture<DiscordMessageRef> postPlain(String channelId, String content) {
        if (channelId == null || content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (DiscordHttp.botUnavailable()) {
            return CompletableFuture.completedFuture(null);
        }

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        // Never ping anyone from bundling-mod-supplied text.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);

        URI uri = URI.create(DiscordPresenceConfig.getBotApiBase() + "/channels/" + channelId + "/messages");

        HttpRequest req = DiscordHttp.botRequest(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> parseMessageRef(resp, channelId))
                .exceptionally(t -> {
                    LOGGER.warn("Discord thread plain post failed: {}", t.toString());
                    return null;
                });
    }

    private static String parseThreadId(HttpResponse<String> resp, String anchorMessageId) {
        int code = resp.statusCode();
        if (code != 200 && code != 201) {
            // The anchor message is not a thread we could have stored (this call is what creates one),
            // so a 404 here is never a dead-thread eviction — only a de-duped warning.
            handleError(code, resp, anchorMessageId, false);
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

    private static DiscordMessageRef parseMessageRef(HttpResponse<String> resp, String targetChannelId) {
        int code = resp.statusCode();
        if (code != 200 && code != 201) {
            handleError(code, resp, targetChannelId, true);
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

    private static DiscordMessageRef parseAnchorRef(HttpResponse<String> resp, String threadId) {
        int code = resp.statusCode();
        if (code != 200) {
            handleError(code, resp, threadId, true);
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String parentId = json.has("parent_id") && !json.get("parent_id").isJsonNull()
                    ? json.get("parent_id").getAsString() : null;
            return parentId == null ? null : new DiscordMessageRef(parentId, threadId);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse Discord channel response", e);
            return null;
        }
    }

    /**
     * @param targetId    the id the request aimed at (channel/thread/message), used to de-dupe the 404 warning
     * @param threadTarget whether {@code targetId} is a channel/thread id that may be a player's stored
     *                     thread — only then can a 404 evict it
     */
    private static void handleError(int code, HttpResponse<String> resp, String targetId, boolean threadTarget) {
        switch (code) {
            case 401 -> warnOnce("Discord bot token rejected (401) — check 'botToken' in discordpresence-server.toml.");
            case 403 -> warnOnce("Discord bot lacks permission (403) — grant it Create Public Threads + "
                    + "Send Messages in Threads in the channel.");
            case 429 -> LOGGER.warn("Discord rate-limited the thread request (429), retry-after={}s — dropping it.",
                    resp.headers().firstValue("retry-after").orElse("?"));
            case 404 -> handleNotFound(resp, targetId, threadTarget);
            default -> LOGGER.warn("Discord thread API returned HTTP {}: {}", code, truncate(resp.body()));
        }
    }

    /**
     * A 404 for a thread target with Discord's "Unknown Channel" code means the thread was deleted (the
     * relay's reaper trims the least-recently-active threads once a channel hits Discord's active cap, and
     * humans delete threads too). Forget it, so the player's next join creates a fresh one instead of the
     * mod posting into a gone thread every session forever. This post itself is dropped, as it already is
     * for a player without a thread — re-posting every advancement top-level would flood the very channel
     * the reaper is trimming. The warning is logged once per target, not once per event.
     */
    private static void handleNotFound(HttpResponse<String> resp, String targetId, boolean threadTarget) {
        if (threadTarget && targetId != null
                && DiscordWebhookClient.discordErrorCode(resp.body()) == DiscordWebhookClient.UNKNOWN_CHANNEL) {
            DiscordService.reportDeadThread(targetId);
        }
        if (warnMissingOnce(targetId)) {
            LOGGER.warn("Discord thread target {} not found (404) — message/channel/thread deleted: {}",
                    targetId, truncate(resp.body()));
        }
    }

    /** @return true the first time this target 404s (bounded; the set is cleared wholesale when full). */
    private static boolean warnMissingOnce(String targetId) {
        String key = targetId == null ? "<unknown>" : targetId;
        if (WARNED_MISSING.size() >= MAX_WARNED_MISSING) {
            WARNED_MISSING.clear();
        }
        return WARNED_MISSING.add(key);
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
