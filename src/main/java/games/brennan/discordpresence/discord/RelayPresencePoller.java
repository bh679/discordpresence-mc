package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Relay-mode presence: periodically fetch each tracked user's online status from the relay's
 * {@code GET <relayBase>/presence/<id>} endpoint and feed it into {@link DiscordPresenceStore},
 * so the {@code lastSeenOnline}/{@code isDiscordUserOnline} query seam works without a local bot
 * gateway. Direct-bot mode does NOT use this — there {@link DiscordGateway} feeds the store from
 * live {@code PRESENCE_UPDATE}s instead.
 *
 * <p>The poll is kicked off on {@link DiscordHttp#SCHEDULER} but the HTTP call itself is async
 * ({@link HttpRequest} via {@code sendAsync} on {@link DiscordHttp#CLIENT}, which runs on the
 * shared HTTP executor) — the scheduler thread is never blocked, so the gateway heartbeat it also
 * drives can never be starved. Best-effort and self-healing: a failed poll is logged at DEBUG and
 * retried on the next tick; the store keeps its last good value meanwhile.</p>
 */
final class RelayPresencePoller {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long INTERVAL_SECONDS = 60;

    private final String relayBaseUrl;
    private final List<String> userIds;
    private final DiscordPresenceStore store;
    private volatile ScheduledFuture<?> task;

    RelayPresencePoller(String relayBaseUrl, List<String> userIds, DiscordPresenceStore store) {
        this.relayBaseUrl = relayBaseUrl == null ? "" : relayBaseUrl;
        this.userIds = userIds == null ? List.of() : List.copyOf(userIds);
        this.store = store;
    }

    void start() {
        if (relayBaseUrl.isBlank() || userIds.isEmpty()) {
            return;
        }
        LOGGER.info("Discord Presence: polling relay for presence of {} user(s) every {}s.",
                userIds.size(), INTERVAL_SECONDS);
        task = DiscordHttp.SCHEDULER.scheduleAtFixedRate(
                this::pollOnce, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void stop() {
        ScheduledFuture<?> t = task;
        task = null;
        if (t != null) {
            t.cancel(false);
        }
    }

    /** Fire one async fetch per tracked id; returns immediately (never blocks the scheduler thread). */
    private void pollOnce() {
        for (String id : userIds) {
            fetchOne(id);
        }
    }

    private void fetchOne(String id) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(relayBaseUrl + "/presence/" + id))
                    .timeout(DiscordHttp.TIMEOUT)
                    .header("User-Agent", "DiscordPresence")
                    .GET()
                    .build();
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: bad relay presence URL for {}: {}", id, e.toString());
            return;
        }
        DiscordHttp.CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() != 200) {
                        LOGGER.debug("Discord Presence: relay /presence/{} -> {}", id, resp.statusCode());
                        return;
                    }
                    try {
                        Parsed p = parse(resp.body());
                        if (p.status() != null) {
                            store.setRelay(id, p.status(), p.lastOnlineMillis());
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Discord Presence: unparseable relay presence for {}: {}", id, e.toString());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.debug("Discord Presence: relay presence poll failed for {}: {}", id, e.toString());
                    return null;
                });
    }

    /**
     * Parse the relay's {@code { userId, status, lastOnlineMillis }} JSON. {@code status} is null
     * when the relay holds no presence for the user (then the caller skips, keeping the prior value).
     * Package-visible for unit tests.
     */
    static Parsed parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String status = (o.has("status") && !o.get("status").isJsonNull())
                ? o.get("status").getAsString() : null;
        long last = (o.has("lastOnlineMillis") && !o.get("lastOnlineMillis").isJsonNull())
                ? o.get("lastOnlineMillis").getAsLong() : 0L;
        return new Parsed(status, last);
    }

    /** Parsed relay presence: a Discord status string (or null when unknown) + last-online epoch ms. */
    record Parsed(String status, long lastOnlineMillis) {}
}
