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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Posts a message through the configured Discord webhook and parses the created
 * message's {@code id} + {@code channel_id} from the {@code ?wait=true} response,
 * so the bot can later react to it.
 *
 * <p>The same call posts either a top-level channel message (the first-join
 * anchor or a relayed chat line) or a message inside an existing thread (via
 * {@code &thread_id=}), keeping the per-player webhook username/avatar in both
 * cases.</p>
 *
 * <p>Webhooks can only post/edit/delete their own messages — they cannot add
 * reactions or create threads (that's {@link DiscordBotClient} /
 * {@link DiscordThreadClient}). Best-effort: every failure resolves to
 * {@code null}; it never throws into game logic.</p>
 */
final class DiscordWebhookClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DiscordWebhookClient() {}

    /**
     * Relays a line of in-game chat to Discord under the player's name + avatar
     * (the game→Discord half of the two-way bridge), into the player's thread when
     * {@code threadId} is non-null. The returned ref is indexed by
     * {@link DiscordService} so Discord replies/threads on it route back.
     */
    static CompletableFuture<DiscordMessageRef> postChat(String playerName, UUID uuid, String content, String threadId) {
        return post(content, playerName, uuid, threadId);
    }

    /**
     * As {@link #postChat(String, UUID, String, String)}, but {@code allowedUserIds} are the Discord
     * users this relayed line may ping — the ids of configured {@code gameRelayMentions} triggers the
     * line contains (e.g. {@code @dev}), already rewritten to {@code <@id>} in {@code content}. Every
     * other mention stays non-notifying.
     */
    static CompletableFuture<DiscordMessageRef> postChat(String playerName, UUID uuid, String content,
                                                         String threadId, List<String> allowedUserIds) {
        return post(content, playerName, uuid, threadId, allowedUserIds);
    }

    /**
     * Post {@code content} as the given player. When {@code threadId} is non-null the
     * message lands inside that thread; otherwise it posts top-level. Uses
     * {@code ?wait=true} so Discord returns the created message's id + channel_id.
     *
     * @return a future of the posted message ref, completing with {@code null}
     *         when disabled or on any failure (callers tolerate null).
     */
    static CompletableFuture<DiscordMessageRef> post(String content, String playerName, UUID uuid, String threadId) {
        return post(content, playerName, uuid, threadId, List.of());
    }

    /**
     * As {@link #post(String, String, UUID, String)}, but {@code allowedUserIds} are Discord user
     * snowflakes this message is permitted to ping (every other mention stays non-notifying). Only
     * trusted sources populate these — the join-suffix (see {@code DiscordService.joinMessage}) and
     * configured chat-tag triggers ({@code gameRelayMentions}) — so arbitrary player text never pings.
     */
    static CompletableFuture<DiscordMessageRef> post(String content, String playerName, UUID uuid, String threadId,
                                                     List<String> allowedUserIds) {
        String webhookUrl = DiscordPresenceConfig.getWebhookUrl();
        if (webhookUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        String body = buildPayload(content, playerName, uuid, allowedUserIds);

        HttpRequest req = HttpRequest.newBuilder(URI.create(withQuery(webhookUrl, threadId)))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return DiscordHttp.sendWithRetry(req)
                .thenApply(DiscordWebhookClient::parseMessageRef)
                .exceptionally(t -> {
                    LOGGER.warn("Discord webhook POST failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post a single {@code embed} as the given player (optionally into a thread),
     * with an optional composed PNG attached and referenced as the embed's image.
     * When {@code png} is non-null the request is {@code multipart/form-data}
     * (payload_json + the file); otherwise it is plain JSON. Uses {@code ?wait=true}
     * so the created message's ref comes back.
     *
     * @return a future of the posted message ref, {@code null} when disabled or on failure.
     */
    static CompletableFuture<DiscordMessageRef> postReport(String playerName, UUID uuid, String threadId,
                                                           JsonObject embed, byte[] png, String filename) {
        return postReport(playerName, uuid, threadId, embed, png, filename, null);
    }

    /**
     * As {@link #postReport(String, UUID, String, JsonObject, byte[], String)} but posts to an explicit
     * {@code webhookOverride} (e.g. a separate public-channel cap's {@code <base>/hook}). Precedence: a
     * dev-env override ({@code DISCORDPRESENCE_DEV_WEBHOOK_URL}) still wins for local testing; then the
     * override; then the configured/relay webhook. Blank/null override → the default.
     */
    static CompletableFuture<DiscordMessageRef> postReport(String playerName, UUID uuid, String threadId,
                                                           JsonObject embed, byte[] png, String filename,
                                                           String webhookOverride) {
        return postReport(playerName, uuid, threadId, embed, png, filename, webhookOverride, null, List.of());
    }

    /**
     * As {@link #postReport(String, UUID, String, JsonObject, byte[], String, String)} but also carries a
     * {@code content} body and a trusted {@code allowedUserIds} ping allow-list — used by the survey
     * response so a bundling mod's maintainer is @-mentioned on each submitted answer. A blank/null
     * {@code content} with an empty {@code allowedUserIds} reproduces the default no-ping report exactly.
     */
    static CompletableFuture<DiscordMessageRef> postReport(String playerName, UUID uuid, String threadId,
                                                           JsonObject embed, byte[] png, String filename,
                                                           String webhookOverride, String content,
                                                           List<String> allowedUserIds) {
        String webhookUrl = (webhookOverride != null && !webhookOverride.isBlank()
                && !DiscordPresenceConfig.isDevWebhookOverrideActive())
                ? webhookOverride
                : DiscordPresenceConfig.getWebhookUrl();
        if (webhookUrl.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (png != null && filename != null) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://" + filename);
            embed.add("image", image);
        }

        JsonObject root = buildReportRoot(playerName, uuid, embed, content, allowedUserIds);

        String url = withQuery(webhookUrl, threadId);
        HttpRequest req;
        if (png != null) {
            String boundary = "DPBoundary" + UUID.randomUUID().toString().replace("-", "");
            MultipartBody mb = MultipartBody.jsonWithPng(boundary, root.toString(), "files[0]", filename, png);
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", mb.contentType())
                    .header("User-Agent", "DiscordPresence-Mod")
                    .timeout(DiscordHttp.REPORT_TIMEOUT)  // image upload — needs more than the 10s JSON timeout
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mb.body()))
                    .build();
        } else {
            req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "DiscordPresence-Mod")
                    .timeout(DiscordHttp.TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        return DiscordHttp.sendWithRetry(req)
                .thenApply(DiscordWebhookClient::parseMessageRef)
                .exceptionally(t -> {
                    LOGGER.warn("Discord webhook report POST failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Post a plain message under the player's name/avatar with one or more file attachments
     * (no embed). Discord shows each attachment inline/downloadable; the {@code content} line
     * can name them. Routes through the configured webhook (the feedback feed / relay {@code /hook}).
     * Used for bug-report logs. Multipart, so it uses the longer {@link DiscordHttp#REPORT_TIMEOUT}.
     *
     * @return a future of the posted message ref, {@code null} when disabled, no files, or on failure.
     */
    static CompletableFuture<DiscordMessageRef> postFiles(String playerName, UUID uuid, String threadId,
                                                          String content, List<MultipartBody.FilePart> files,
                                                          List<String> allowedUserIds) {
        String webhookUrl = DiscordPresenceConfig.getWebhookUrl();
        if (webhookUrl.isBlank() || files == null || files.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        JsonObject root = buildContentRoot(playerName, uuid, content, allowedUserIds);
        String url = withQuery(webhookUrl, threadId);
        String boundary = "DPBoundary" + UUID.randomUUID().toString().replace("-", "");
        MultipartBody mb = MultipartBody.jsonWithFiles(boundary, root.toString(), files);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", mb.contentType())
                .header("User-Agent", "DiscordPresence-Mod")
                .timeout(DiscordHttp.REPORT_TIMEOUT)  // file upload — needs more than the 10s JSON timeout
                .POST(HttpRequest.BodyPublishers.ofByteArray(mb.body()))
                .build();
        return DiscordHttp.sendWithRetry(req)
                .thenApply(DiscordWebhookClient::parseMessageRef)
                .exceptionally(t -> {
                    LOGGER.warn("Discord webhook files POST failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Build a webhook JSON root for a content-only message (no embed): the player username/avatar,
     * an optional {@code content} body, and the {@code allowed_mentions} block (parsing suppressed;
     * only the trusted {@code allowedUserIds} may ping). Pure (no I/O) so it is unit-testable.
     */
    static JsonObject buildContentRoot(String playerName, UUID uuid, String content, List<String> allowedUserIds) {
        JsonObject root = new JsonObject();
        String username = safeUsername(playerName);
        if (username != null) {
            root.addProperty("username", username);
        }
        root.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/64");
        if (content != null && !content.isBlank()) {
            root.addProperty("content", content);
        }
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        if (allowedUserIds != null && !allowedUserIds.isEmpty()) {
            JsonArray users = new JsonArray();
            for (String id : allowedUserIds) {
                users.add(id);
            }
            allowedMentions.add("users", users);
        }
        root.add("allowed_mentions", allowedMentions);
        return root;
    }

    /**
     * Build the report webhook JSON root: the player username/avatar, the single embed, an optional
     * {@code content} body, and the {@code allowed_mentions} block. Mention <i>parsing</i> is always
     * suppressed (a player-controlled name/embed can never ping); only the trusted caller-supplied
     * {@code allowedUserIds} are added to the {@code users} allow-list (e.g. the survey ping). A
     * blank/null {@code content} omits the content field; an empty/null id list yields {@code parse:[]}
     * only — i.e. the historical no-ping report. Pure (no I/O) so it is unit-testable.
     */
    static JsonObject buildReportRoot(String playerName, UUID uuid, JsonObject embed,
                                      String content, List<String> allowedUserIds) {
        JsonObject root = new JsonObject();
        String username = safeUsername(playerName);
        if (username != null) {
            root.addProperty("username", username);
        }
        root.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/64");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        root.add("embeds", embeds);
        if (content != null && !content.isBlank()) {
            root.addProperty("content", content);
        }
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        if (allowedUserIds != null && !allowedUserIds.isEmpty()) {
            JsonArray users = new JsonArray();
            for (String id : allowedUserIds) {
                users.add(id);
            }
            allowedMentions.add("users", users);
        }
        root.add("allowed_mentions", allowedMentions);
        return root;
    }

    /**
     * {@code wait=true} makes Discord return the created message (with id +
     * channel_id); {@code thread_id} (a numeric snowflake, safe unencoded) routes
     * the post into an existing thread.
     */
    private static String withQuery(String webhookUrl, String threadId) {
        StringBuilder sb = new StringBuilder(webhookUrl);
        sb.append(webhookUrl.contains("?") ? "&" : "?").append("wait=true");
        if (threadId != null && !threadId.isBlank()) {
            sb.append("&thread_id=").append(threadId);
        }
        return sb.toString();
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
        return buildPayload(content, playerName, uuid, List.of());
    }

    private static String buildPayload(String content, String playerName, UUID uuid, List<String> allowedUserIds) {
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

        // Suppress all mention PARSING so a player-controlled name/template/chat line can never
        // ping; then explicitly allow only the trusted user-ids the caller passed (e.g. a bundling
        // mod's dev ping, scanned from the join suffix — see DiscordService.joinMessage).
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        if (!allowedUserIds.isEmpty()) {
            JsonArray users = new JsonArray();
            for (String id : allowedUserIds) {
                users.add(id);
            }
            allowedMentions.add("users", users);
        }
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
