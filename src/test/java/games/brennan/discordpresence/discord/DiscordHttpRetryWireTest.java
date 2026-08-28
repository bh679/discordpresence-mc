package games.brennan.discordpresence.discord;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-level companion to {@link DiscordHttpRetryTest}: the predicates are pure, but what matters for
 * the duplicated death report is how many times a body actually reaches the server. Runs
 * {@link DiscordHttp#sendWithRetry} against a loopback stub that counts requests.
 */
class DiscordHttpRetryWireTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    /** Stub that answers {@code status} to every request, counting them. */
    private void start(int status) throws IOException {
        start((exchange) -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private interface Responder {
        void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private void start(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", (exchange) -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            responder.respond(exchange);
        });
        server.start();
    }

    private HttpRequest post() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"embeds\":[{}]}", StandardCharsets.UTF_8))
                .build();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void reset() {
        hits.set(0);
    }

    @Test
    void aMessageCreatingPostReachesTheServerExactlyOnceOnA502() throws Exception {
        // The duplicate: dp-relay answers 502 after its own upstream forward already reached Discord.
        start(502);
        HttpResponse<String> resp = DiscordHttp.sendWithRetry(post(), false).get();

        assertEquals(502, resp.statusCode(), "the caller still sees the failure");
        assertEquals(1, hits.get(), "a message-creating post must never be resent on a 5xx");
    }

    @Test
    void anIdempotentCallStillRetriesA502ToExhaustion() throws Exception {
        start(502);
        DiscordHttp.sendWithRetry(post(), true).get();

        assertEquals(DiscordHttp.MAX_ATTEMPTS, hits.get(), "reads keep the full retry budget");
    }

    @Test
    void aRateLimitIsStillRetriedForMessageCreatingPosts() throws Exception {
        // 429 provably created nothing, so it stays retryable under the strict policy. Retry-After: 0
        // keeps the test instant; the second attempt succeeds.
        start((exchange) -> {
            int n = hits.get();
            byte[] body = "{\"id\":\"1\",\"channel_id\":\"2\"}".getBytes(StandardCharsets.UTF_8);
            if (n == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, body.length);
            } else {
                exchange.sendResponseHeaders(200, body.length);
            }
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        HttpResponse<String> resp = DiscordHttp.sendWithRetry(post(), false).get();

        assertEquals(200, resp.statusCode());
        assertEquals(2, hits.get(), "429 → retried once, then delivered");
    }

    @Test
    void aSuccessIsSentOnceUnderEitherPolicy() throws Exception {
        start(200);
        DiscordHttp.sendWithRetry(post(), false).get();
        DiscordHttp.sendWithRetry(post(), true).get();

        assertEquals(2, hits.get(), "one request per call, no spurious resends");
    }
}
