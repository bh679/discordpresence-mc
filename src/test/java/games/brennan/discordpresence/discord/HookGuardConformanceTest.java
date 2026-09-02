package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance check for the relay's hook guard ({@code dp-relay/hook-guard.js}): every request body
 * THIS mod's real producer code sends to {@code /hook} and {@code /bot} must pass the guard in
 * {@code enforce} mode. Runs the actual {@link DiscordWebhookClient} / {@link DiscordThreadClient}
 * HTTP paths (not fixtures) against a real relay process spawned from {@code DP_RELAY_DIR}, with a
 * local stub standing in for Discord, and asserts nothing was withheld or flagged.
 *
 * <p>Skipped unless {@code DP_RELAY_DIR} points at a dp-relay checkout with {@code node_modules}
 * installed — it is a cross-repo check, not part of DP's normal unit run.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HookGuardConformanceTest {

    private static final String CAP = "localcapconformance00";
    private static final String PING_ID = "123456789012345678";
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final List<Map.Entry<String, String>> stubPosts = new CopyOnWriteArrayList<>(); // path -> body
    private HttpServer stub;
    private HttpServer proxy;
    private Process relay;
    private String relayBase;
    private String dpBase;
    private Path tmp;

    /** Node's http server does not speak h2c; Java's default client tries the upgrade and hangs. */
    private static final HttpClient HTTP1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeAll
    void boot() throws Exception {
        String relayDir = System.getenv("DP_RELAY_DIR");
        Assumptions.assumeTrue(relayDir != null && Files.isDirectory(Path.of(relayDir, "node_modules")),
                "DP_RELAY_DIR not set to a dp-relay checkout with node_modules — cross-repo check skipped");
        Assumptions.assumeTrue(System.getenv(games.brennan.discordpresence.config.DiscordPresenceConfig.DEV_WEBHOOK_ENV) == null,
                "a dev webhook override would force direct mode");

        // Stub "Discord": records every POST (webhook + bot REST), answers like Discord does.
        int stubPort = freePort();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", stubPort), 0);
        stub.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            if ("POST".equals(ex.getRequestMethod())) {
                stubPosts.add(Map.entry(ex.getRequestURI().toString(), new String(body, StandardCharsets.UTF_8)));
            }
            byte[] resp = "{\"id\":\"9001\",\"channel_id\":\"42\",\"parent_id\":\"41\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        stub.start();

        int relayPort = freePort();
        relayBase = "http://127.0.0.1:" + relayPort;
        tmp = Files.createTempDirectory("dp-hookguard-conformance");
        String node = System.getenv().getOrDefault("NODE_BIN", "node");
        ProcessBuilder pb = new ProcessBuilder(node, "server.js").directory(Path.of(relayDir).toFile())
                .redirectErrorStream(true).redirectOutput(tmp.resolve("relay.log").toFile());
        pb.environment().putAll(Map.ofEntries(
                Map.entry("PORT", String.valueOf(relayPort)), Map.entry("HOST", "127.0.0.1"), Map.entry("ENABLED", "true"),
                Map.entry("CAP", CAP), Map.entry("CENTRAL_WEBHOOK_URL", "http://127.0.0.1:" + stubPort + "/webhook"),
                Map.entry("BOT_TOKEN", "conformance-token"), Map.entry("DISCORD_API_BASE", "http://127.0.0.1:" + stubPort + "/api"),
                Map.entry("PRESENCE_TRACK_IDS", ""), Map.entry("ANALYTICS_ENABLED", "true"),
                Map.entry("ANALYTICS_DIR", tmp.resolve("analytics").toString()),
                Map.entry("RATE_MAX", "100000"), Map.entry("RL_MAX_PER_MIN", "100000"), Map.entry("RL_MAX_INFLIGHT", "10000"),
                Map.entry("HOOK_GUARD_MODE", "enforce"), Map.entry("HOOK_PING_ALLOW_IDS", PING_ID),
                Map.entry("HEALTH_CACHE_MS", "0")));
        relay = pb.start();
        waitForHealth();

        // In prod DP talks HTTPS to Apache, which terminates the HTTP/2 negotiation and speaks HTTP/1.1
        // to Node. DP's shared HttpClient sets no version, so against a BARE Node relay it sends an h2c
        // UPGRADE that the relay's WebSocket upgrade handler drops. Stand the same shim in front here:
        // a plain HTTP/1.1 server that forwards each request to the relay byte-for-byte (hop-by-hop
        // headers stripped), so the guard sees exactly the bytes the mod produced.
        int proxyPort = freePort();
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", proxyPort), 0);
        proxy.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        proxy.createContext("/", ex -> {
            try {
                byte[] body = ex.getRequestBody().readAllBytes();
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(relayBase + ex.getRequestURI().toString()))
                        .timeout(Duration.ofSeconds(20));
                ex.getRequestHeaders().forEach((k, vs) -> {
                    String lk = k.toLowerCase();
                    if (lk.equals("host") || lk.equals("connection") || lk.equals("upgrade") || lk.equals("http2-settings")
                            || lk.equals("content-length") || lk.equals("expect")) return;
                    for (String v : vs) b.header(k, v);
                });
                b.method(ex.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                HttpResponse<byte[]> r = HTTP1.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                r.headers().map().forEach((k, vs) -> {
                    if (k.startsWith(":") || k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("transfer-encoding")) return;
                    for (String v : vs) ex.getResponseHeaders().add(k, v);
                });
                ex.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
                if (r.body().length > 0) ex.getResponseBody().write(r.body());
            } catch (Exception e) {
                byte[] msg = ("proxy: " + e).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(502, msg.length);
                ex.getResponseBody().write(msg);
            } finally {
                ex.close();
            }
        });
        proxy.start();
        dpBase = "http://127.0.0.1:" + proxyPort + "/" + CAP;

        // Point DP at the relay exactly the way Dungeon Train does: a credentials provider with a relay base.
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String relayBaseUrl() { return dpBase; }
        });
    }

    @AfterAll
    void shutdown() {
        DiscordCredentials.register(null);
        if (relay != null) relay.destroyForcibly();
        if (proxy != null) proxy.stop(0);
        if (stub != null) stub.stop(0);
    }

    private void waitForHealth() throws Exception {
        HttpClient c = HTTP1;
        for (int i = 0; i < 300; i++) {
            try {
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(relayBase + "/health")).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return;
            } catch (IOException ignored) { /* not up yet */ }
            Thread.sleep(100);
            if (!relay.isAlive()) break;
        }
        throw new IllegalStateException("relay did not come up; log:\n" + Files.readString(tmp.resolve("relay.log")));
    }

    private JsonObject health() throws Exception {
        HttpResponse<String> r = HTTP1.send(
                HttpRequest.newBuilder(URI.create(relayBase + "/health")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("hookGuard");
    }

    private static byte[] realPng() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(3, 3, 0xffff5555);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    @Test
    void everyRealProducerPathPassesTheGuardInEnforce() throws Exception {
        assertEquals(dpBase + "/hook", games.brennan.discordpresence.config.DiscordPresenceConfig.getWebhookUrl());
        assertEquals(dpBase + "/bot", games.brennan.discordpresence.config.DiscordPresenceConfig.getBotApiBase());
        int expected = 0;

        // 1. Join / leave / game-chat line — content-only webhook post (DiscordWebhookClient.buildPayload).
        assertNotNull(await(DiscordWebhookClient.post("🎮 **Steve** started the game", "Steve", STEVE, null)), "join line");
        expected++;
        // 2. The same line into the player's thread, with a bundling mod's allow-listed dev ping.
        assertNotNull(await(DiscordWebhookClient.post("Steve: hello from the train <@" + PING_ID + ">", "Steve", STEVE,
                "555555555555555555", List.of(PING_ID))), "threaded chat line with ping");
        expected++;
        // 3. Death report: real manifest-style embed + a real PNG, multipart payload_json + files[0].
        JsonObject death = DiscordService.buildReportEmbed("Steve - Carriage 120", "☠ Steve fell from a high place",
                List.of(new DeathField("DIST", "1,204 m"), new DeathField("TIME", "12:03"), new DeathField("LIVES", "3")), 0xff5555);
        assertNotNull(await(DiscordWebhookClient.postReport("Steve", STEVE, null, death, realPng(), "ride.png")), "death report");
        expected++;
        // 4. Survey answer copy: report embed + content + maintainer ping (postReport's full form).
        JsonObject survey = DiscordService.buildReportEmbed("📋 Feedback — Steve", "Rate it: ★★★★☆", List.of(), 0x5865f2);
        assertNotNull(await(DiscordWebhookClient.postReport("Steve", STEVE, "555555555555555555", survey, null, null,
                null, "New survey answer", List.of(PING_ID))), "survey copy with ping");
        expected++;
        // 5. Bug report attachments — the exact part set DiscordService.postFeedbackAttachments builds.
        List<MultipartBody.FilePart> files = List.of(
                new MultipartBody.FilePart("files[0]", "latest.log", "text/plain", "[12:00:00] [main/INFO]: boot\n".getBytes(StandardCharsets.UTF_8)),
                new MultipartBody.FilePart("files[1]", "debug.log.gz", "application/gzip", new byte[] {0x1f, (byte) 0x8b, 8, 0}),
                new MultipartBody.FilePart("files[2]", "crash-report.txt", "text/plain", "---- Minecraft Crash Report ----".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(await(DiscordWebhookClient.postFiles("Steve", STEVE, "555555555555555555",
                "Bug report — Crash: latest.log, debug.log.gz, crash-report.txt", files, List.of())), "bug report attachments");
        expected++;
        // 6. Advancement embed via the bot proxy (DiscordThreadClient.postEmbed) with the X-DT-Advancement header.
        assertNotNull(await(DiscordThreadClient.postEmbed("42", null, "Achievement Get!", "Steve — The Great Beyond", 0xffaa00,
                "https://mc-heads.net/avatar/" + STEVE + "/64", List.of(new DeathField("Carriage", "120")), "dungeontrain:the_great_beyond")),
                "advancement embed");
        expected++;
        // 7. Plain bot post into a thread (operator answer relay etc.).
        assertNotNull(await(DiscordThreadClient.postPlain("42", "Steve: a plain bot-side line")), "plain bot post");
        expected++;
        // 8. Thread creation from a message anchor.
        assertNotNull(await(DiscordThreadClient.createThreadFromMessage(new DiscordMessageRef("42", "9001"), "Steve", 10080)), "thread create");
        expected++;

        // Give the relay's fast-path background forwards a moment, then check what Discord (the stub) saw.
        for (int i = 0; i < 50 && stubPosts.size() < expected; i++) Thread.sleep(50);
        assertEquals(expected, stubPosts.size(), "every producer body reached Discord: " + stubPosts);
        for (Map.Entry<String, String> p : stubPosts) {
            if (p.getKey().contains("/threads")) continue; // thread-create bodies carry no mentions block
            JsonObject sent = p.getValue().startsWith("{")
                    ? JsonParser.parseString(p.getValue()).getAsJsonObject()
                    : payloadJsonOf(p.getValue());
            JsonArray parse = sent.getAsJsonObject("allowed_mentions").getAsJsonArray("parse");
            assertEquals(0, parse.size(), "mention parsing stays off on the wire: " + p.getKey());
        }
        // The two pings above are allow-listed, so they must have SURVIVED the relay's rewrite.
        long withPing = stubPosts.stream().filter(p -> p.getValue().contains("\"users\":[\"" + PING_ID + "\"]")).count();
        assertEquals(2, withPing, "allow-listed pings survive");

        JsonObject h = health();
        // Evidence for the Gate 2 report: what Discord (the stub) actually received, plus the guard tallies.
        StringBuilder ev = new StringBuilder("hookGuard health: ").append(h).append("\n\n");
        for (Map.Entry<String, String> p : stubPosts) {
            ev.append("POST ").append(p.getKey()).append('\n')
              .append(p.getValue().length() > 1200 ? p.getValue().substring(0, 1200) + "…[" + p.getValue().length() + " bytes]" : p.getValue())
              .append("\n\n");
        }
        Path evidence = Path.of("build", "hook-guard-conformance-evidence.txt");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, ev.toString());
        assertEquals("enforce", h.get("mode").getAsString());
        assertEquals(expected, h.get("checked").getAsInt(), "guard saw every body");
        assertEquals(0, h.get("flagged").getAsInt(), "nothing this mod sends is flagged: " + h);
        assertEquals(0, h.get("withheld").getAsInt(), h.toString());
        assertEquals(0, h.get("rewritten").getAsInt(), "DP's own mentions block already IS the safe form: " + h);
        assertTrue(Files.readString(tmp.resolve("relay.log")).lines().noneMatch(l -> l.contains("guard:enforce")),
                "no guard line in the relay log");
    }

    /** payload_json out of a multipart body (test-side, tolerant). */
    private static JsonObject payloadJsonOf(String multipart) {
        int i = multipart.indexOf("name=\"payload_json\"");
        int start = multipart.indexOf("\r\n\r\n", i) + 4;
        int end = multipart.indexOf("\r\n--", start);
        return JsonParser.parseString(multipart.substring(start, end)).getAsJsonObject();
    }

    @Test
    void aForgedBodyIsWithheldByTheSameRelay() throws Exception {
        // Sanity: the relay we just proved transparent for DP really is enforcing — a body DP never
        // sends (tts + @everyone) comes back 202 withheld and never reaches the stub.
        int before = stubPosts.size();
        HttpResponse<String> r = HTTP1.send(HttpRequest.newBuilder(URI.create(relayBase + "/" + CAP + "/hook?wait=true"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"@everyone free nitro\",\"tts\":true,\"allowed_mentions\":{\"parse\":[\"everyone\"]}}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode());
        assertTrue(r.body().contains("\"withheld\":[\"key:tts\"]"), r.body());
        Thread.sleep(300);
        assertEquals(before, stubPosts.size(), "withheld body never forwarded");
    }
}
