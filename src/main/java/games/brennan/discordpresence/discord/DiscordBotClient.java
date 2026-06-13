package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds/removes the bot's own reactions on a message via the Discord REST API.
 *
 * <p>Reactions require a BOT TOKEN — webhooks cannot react. Adding/removing a
 * reaction needs only the token plus channel permissions (Add Reactions, Read
 * Message History); no gateway connection or privileged intents.</p>
 *
 * <p><b>Two-way chat (Phase 2):</b> the persistent gateway (WebSocket) read path —
 * reading Discord replies/threads and routing them back into in-game chat — is
 * implemented in {@link DiscordGateway}, reusing this same bot token. This class
 * remains the REST reaction client.</p>
 */
final class DiscordBotClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One-shot WARN de-dupe so a misconfigured token/perms doesn't spam the log. */
    private static final AtomicBoolean WARNED_AUTH = new AtomicBoolean(false);

    private DiscordBotClient() {}

    static CompletableFuture<Void> addReaction(DiscordMessageRef ref, String emoji) {
        return reaction(ref, emoji, "PUT");
    }

    static CompletableFuture<Void> removeOwnReaction(DiscordMessageRef ref, String emoji) {
        return reaction(ref, emoji, "DELETE");
    }

    private static CompletableFuture<Void> reaction(DiscordMessageRef ref, String emoji, String method) {
        if (ref == null || emoji == null || emoji.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (DiscordHttp.botUnavailable()) {
            return CompletableFuture.completedFuture(null);
        }

        // Only the emoji segment is encoded (UTF-8 percent-encoding). The ids are
        // numeric snowflakes and '@me' is a literal — both safe unencoded.
        String enc = URLEncoder.encode(emoji, StandardCharsets.UTF_8);
        URI uri = URI.create(DiscordPresenceConfig.getBotApiBase() + "/channels/" + ref.channelId()
                + "/messages/" + ref.messageId()
                + "/reactions/" + enc + "/@me");

        HttpRequest req = DiscordHttp.botRequest(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();

        return DiscordHttp.CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> handle(resp, method, emoji))
                .exceptionally(t -> {
                    LOGGER.warn("Discord reaction {} failed: {}", method, t.toString());
                    return null;
                });
    }

    private static void handle(HttpResponse<String> resp, String method, String emoji) {
        int code = resp.statusCode();
        if (code == 204 || code == 200) {
            return; // success
        }
        switch (code) {
            case 401 -> warnOnce("Discord bot token rejected (401) — check 'botToken' in discordpresence-server.toml.");
            case 403 -> warnOnce("Discord bot lacks permission (403) — invite the bot to the channel with "
                    + "Add Reactions + Read Message History.");
            case 429 -> LOGGER.warn("Discord rate-limited the reaction (429), retry-after={}s — dropping it.",
                    resp.headers().firstValue("retry-after").orElse("?"));
            case 404 -> LOGGER.warn("Discord reaction target not found (404) — message/channel deleted "
                    + "or emoji invalid: {}", emoji);
            default -> LOGGER.warn("Discord reaction {} returned HTTP {}: {}", method, code, truncate(resp.body()));
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
