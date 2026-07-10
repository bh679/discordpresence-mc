package games.brennan.discordpresence.reincarnation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordHttp;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to the relay's cross-world reincarnation pool — {@code POST}/{@code GET <base>/reincarnations}.
 * The relay base URL (from {@code DiscordPresenceConfig.getRelayBaseUrl()}) already embeds the
 * capability token, exactly like {@code /hook} and {@code /presence/<id>}, so this client only ever
 * runs in relay-mode.
 *
 * <p>The snapshot/friends fields are <b>already-encoded opaque strings</b> here ({@link SnapshotCodec}
 * does the {@link net.minecraft.nbt.CompoundTag} ↔ string work upstream), so the URL/body builders and
 * the response parser are pure string/JSON logic and unit-testable without Minecraft. Network calls
 * reuse the shared {@link DiscordHttp} client/executor and are best-effort: failures log at DEBUG and
 * resolve to empty, never throwing into game logic.</p>
 */
final class RelayReincarnationClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PATH = "/reincarnations";

    private RelayReincarnationClient() {}

    /** A death record to ingest. {@code carriage} null ⇒ omitted (the relay then excludes it from carriage bands). */
    record PostPayload(String snapshot, String name, String playerId, Integer carriage,
                       String skinUrl, List<String> friends) {}

    /** A candidate returned by the relay (snapshot/friends still opaque strings; {@code carriage} may be null). */
    record RelayRecord(String id, String playerId, String name, Integer carriage,
                       String skinUrl, String snapshot, List<String> friends) {}

    // --- pure builders / parser (unit-tested) --------------------------------

    /** Build the JSON ingest body. {@code snapshot} is required; blank/absent optional fields are omitted. */
    static String buildPostBody(PostPayload p) {
        JsonObject o = new JsonObject();
        o.addProperty("snapshot", p.snapshot());
        if (p.name() != null && !p.name().isBlank()) {
            o.addProperty("name", p.name());
        }
        if (p.playerId() != null && !p.playerId().isBlank()) {
            o.addProperty("playerId", p.playerId());
        }
        if (p.carriage() != null) {
            o.addProperty("carriage", p.carriage());
        }
        if (p.skinUrl() != null && !p.skinUrl().isBlank()) {
            o.addProperty("skinUrl", p.skinUrl());
        }
        if (p.friends() != null && !p.friends().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String f : p.friends()) {
                if (f != null && !f.isBlank()) {
                    arr.add(f);
                }
            }
            if (!arr.isEmpty()) {
                o.add("friends", arr);
            }
        }
        return o.toString();
    }

    /**
     * Build the candidate-query URL: {@code <base>/reincarnations?radius=&limit=&carriage=&exclude=}.
     * {@code carriage} null ⇒ omitted ("any" band); {@code exclude} null/blank ⇒ omitted. {@code exclude}
     * is URL-encoded (a player UUID).
     */
    static String buildQueryUrl(String base, Integer carriage, int radius, String exclude, int limit) {
        StringBuilder sb = new StringBuilder(base).append(PATH)
                .append("?radius=").append(radius)
                .append("&limit=").append(limit);
        if (carriage != null) {
            sb.append("&carriage=").append(carriage);
        }
        if (exclude != null && !exclude.isBlank()) {
            sb.append("&exclude=").append(URLEncoder.encode(exclude, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Parse the relay's {@code { "records": [ ... ] }} response (oldest→newest) into {@link RelayRecord}s.
     * Tolerant of missing fields and a missing/empty {@code records} array (→ empty list).
     */
    static List<RelayRecord> parseRecords(String json) {
        List<RelayRecord> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return out;
            }
            JsonElement recs = root.getAsJsonObject().get("records");
            if (recs == null || !recs.isJsonArray()) {
                return out;
            }
            for (JsonElement el : recs.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                out.add(new RelayRecord(
                        optString(o, "id"),
                        optString(o, "playerId"),
                        optString(o, "name"),
                        optInt(o, "carriage"),
                        optString(o, "skinUrl"),
                        optString(o, "snapshot"),
                        optStringArray(o, "friends")));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Discord Presence: unparseable reincarnation records: {}", e.toString());
        }
        return out;
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsString() : null;
    }

    private static Integer optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> optStringArray(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return out;
        }
        for (JsonElement el : e.getAsJsonArray()) {
            if (el != null && el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    // --- async network (best-effort) -----------------------------------------

    /**
     * Ingest one death record. Resolves to the HTTP status code — a 2xx means the relay accepted it, so
     * the caller may mark the death delivered and drop it — or {@code null} when the request couldn't be
     * built or the send failed with no response (the caller keeps the death queued for a later retry).
     * Logs the outcome; never throws. The queue-drain in {@code ReincarnationManager} owns the retry
     * cadence, so this no longer fires-and-forgets.
     */
    static CompletableFuture<Integer> post(String base, PostPayload payload) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + PATH))
                    .timeout(DiscordHttp.TIMEOUT)
                    .header("User-Agent", "DiscordPresence")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPostBody(payload), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: bad reincarnation POST URL ({}): {}", base, e.toString());
            return CompletableFuture.completedFuture(null);
        }
        return DiscordHttp.CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code / 100 != 2) {
                        LOGGER.debug("Discord Presence: reincarnation POST -> {} ({})", code, resp.body());
                    }
                    return code;
                })
                .exceptionally(e -> {
                    LOGGER.debug("Discord Presence: reincarnation POST failed: {}", e.toString());
                    return null;
                });
    }

    /**
     * Fetch candidates for a band. Resolves to the parsed records (oldest→newest), or an empty list on
     * any failure (logged at DEBUG). Never blocks the caller — the HTTP runs on the shared async client.
     */
    static CompletableFuture<List<RelayRecord>> fetch(String base, Integer carriage, int radius,
                                                      String exclude, int limit) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(buildQueryUrl(base, carriage, radius, exclude, limit)))
                    .timeout(DiscordHttp.TIMEOUT)
                    .header("User-Agent", "DiscordPresence")
                    .GET()
                    .build();
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: bad reincarnation GET URL ({}): {}", base, e.toString());
            return CompletableFuture.completedFuture(List.of());
        }
        return DiscordHttp.CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        LOGGER.debug("Discord Presence: reincarnation GET -> {}", resp.statusCode());
                        return List.<RelayRecord>of();
                    }
                    try {
                        return parseRecords(resp.body());
                    } catch (Exception e) {
                        LOGGER.debug("Discord Presence: unparseable reincarnation response: {}", e.toString());
                        return List.<RelayRecord>of();
                    }
                })
                .exceptionally(e -> {
                    LOGGER.debug("Discord Presence: reincarnation GET failed: {}", e.toString());
                    return List.of();
                });
    }
}
