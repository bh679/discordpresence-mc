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

    /**
     * The outcome of a candidate query. Either the relay shipped a fresh band ({@code unchanged=false},
     * {@code records} oldest→newest, {@code etag} tagging it for the next conditional fetch) or answered
     * the conditional GET with "still current" ({@code unchanged=true}, no {@code records}) — the caller
     * then keeps its cached band and skips re-decoding, saving the snapshot bytes the relay didn't send.
     */
    record FetchResult(boolean unchanged, String etag, List<RelayRecord> records) {}

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

    /** Back-compat overload with no conditional-GET tag (always a full band). */
    static String buildQueryUrl(String base, Integer carriage, int radius, String exclude, int limit) {
        return buildQueryUrl(base, carriage, radius, exclude, limit, null);
    }

    /**
     * Build the candidate-query URL:
     * {@code <base>/reincarnations?radius=&limit=&carriage=&exclude=&etag=}. {@code carriage} null ⇒
     * omitted ("any" band); {@code exclude} null/blank ⇒ omitted ({@code exclude} is a URL-encoded player
     * UUID). {@code etag} null/blank ⇒ omitted; when present it is the tag of the band the caller already
     * holds, so the relay can answer "unchanged" and skip re-shipping the snapshots.
     */
    static String buildQueryUrl(String base, Integer carriage, int radius, String exclude, int limit, String etag) {
        StringBuilder sb = new StringBuilder(base).append(PATH)
                .append("?radius=").append(radius)
                .append("&limit=").append(limit);
        if (carriage != null) {
            sb.append("&carriage=").append(carriage);
        }
        if (exclude != null && !exclude.isBlank()) {
            sb.append("&exclude=").append(URLEncoder.encode(exclude, StandardCharsets.UTF_8));
        }
        if (etag != null && !etag.isBlank()) {
            sb.append("&etag=").append(URLEncoder.encode(etag, StandardCharsets.UTF_8));
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

    /**
     * Parse a candidate-query response into a {@link FetchResult}. A {@code {"unchanged":true}} reply
     * (conditional-GET hit) carries no records; otherwise {@code records} is the band (oldest→newest) and
     * {@code etag} tags it for the next conditional fetch. Tolerant of missing fields / unparseable input
     * (→ a not-unchanged result with whatever records parsed, and a null etag).
     */
    static FetchResult parseResponse(String json) {
        boolean unchanged = false;
        String etag = null;
        if (json != null && !json.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(json);
                if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    JsonElement u = o.get("unchanged");
                    unchanged = u != null && u.isJsonPrimitive() && u.getAsBoolean();
                    etag = optString(o, "etag");
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Discord Presence: unparseable reincarnation response: {}", e.toString());
            }
        }
        // An "unchanged" reply ships no records; only parse the array when the relay actually sent a band.
        List<RelayRecord> records = unchanged ? List.of() : parseRecords(json);
        return new FetchResult(unchanged, etag, records);
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
     * Fetch candidates for a band, sending {@code etag} (the tag of the band the caller already holds, or
     * {@code null}) so the relay can answer the conditional GET with "unchanged" instead of re-shipping
     * the snapshots. Resolves to a {@link FetchResult}; any failure (bad URL, non-200, unparseable, send
     * error) resolves to a not-unchanged result with no records — the caller then treats it as an empty
     * band, exactly as before. Never blocks — the HTTP runs on the shared async client.
     */
    static CompletableFuture<FetchResult> fetch(String base, Integer carriage, int radius,
                                                String exclude, int limit, String etag) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(buildQueryUrl(base, carriage, radius, exclude, limit, etag)))
                    .timeout(DiscordHttp.TIMEOUT)
                    .header("User-Agent", "DiscordPresence")
                    .GET()
                    .build();
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: bad reincarnation GET URL ({}): {}", base, e.toString());
            return CompletableFuture.completedFuture(new FetchResult(false, null, List.of()));
        }
        return DiscordHttp.CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        LOGGER.debug("Discord Presence: reincarnation GET -> {}", resp.statusCode());
                        return new FetchResult(false, null, List.<RelayRecord>of());
                    }
                    try {
                        return parseResponse(resp.body());
                    } catch (Exception e) {
                        LOGGER.debug("Discord Presence: unparseable reincarnation response: {}", e.toString());
                        return new FetchResult(false, null, List.<RelayRecord>of());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.debug("Discord Presence: reincarnation GET failed: {}", e.toString());
                    return new FetchResult(false, null, List.of());
                });
    }
}
