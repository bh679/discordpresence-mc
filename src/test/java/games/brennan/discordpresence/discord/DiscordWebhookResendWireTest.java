package games.brennan.discordpresence.discord;

import com.sun.net.httpserver.HttpServer;
import games.brennan.discordpresence.discord.DiscordResendQueue.QueuedPost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link DiscordWebhookClient#resend} and {@code sendAndParse} actually do against a loopback
 * stub: the post id goes out unchanged, the photo goes out as multipart, and each status either ends
 * the queue entry or keeps it.
 */
class DiscordWebhookResendWireTest {

    private static final String POST_ID = "6a9e8815-b87e-45a3-b473-1c0b7e6e14b6";
    private static final String PHOTO_BODY =
            "{\"embeds\":[{\"title\":\"t\",\"image\":{\"url\":\"attachment://photo.png\"}}]}";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> postIds = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private void start(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            hits.incrementAndGet();
            postIds.add(exchange.getRequestHeaders().getFirst(DiscordWebhookClient.POST_ID_HEADER));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodies.add(body);
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private QueuedPost queued(String rootJson, String postId, String photoFile, String filename) {
        return new QueuedPost("k", url(), null, rootJson, 0L, postId, photoFile, filename);
    }

    private boolean resend(QueuedPost post, byte[] png) throws Exception {
        return DiscordWebhookClient.resend(post, png).get(30, TimeUnit.SECONDS);
    }

    @Test
    void sendsTheOriginalPostId() throws Exception {
        start(200, "{\"id\":\"1\",\"channel_id\":\"2\"}");
        assertTrue(resend(queued("{}", POST_ID, null, null), null));
        assertEquals(POST_ID, postIds.get(0));
    }

    @Test
    void anOlderEntryGoesOutWithNoPostId() throws Exception {
        start(200, "{\"id\":\"1\",\"channel_id\":\"2\"}");
        assertTrue(resend(queued("{}", null, null, null), null));
        assertNull(postIds.get(0));
    }

    @Test
    void aSavedPhotoGoesOutAsMultipart() throws Exception {
        start(200, "{\"id\":\"1\",\"channel_id\":\"2\"}");
        assertTrue(resend(queued(PHOTO_BODY, POST_ID, POST_ID + ".png", "photo.png"), new byte[] {'P', 'N', 'G'}));
        assertTrue(contentTypes.get(0).startsWith("multipart/form-data"));
        assertTrue(bodies.get(0).contains("attachment://photo.png"));
        assertTrue(bodies.get(0).contains("filename=\"photo.png\""));
        assertTrue(bodies.get(0).contains("PNG"));
    }

    @Test
    void aLostPhotoGoesOutTextOnlyNotBroken() throws Exception {
        start(200, "{\"id\":\"1\",\"channel_id\":\"2\"}");
        assertTrue(resend(queued(PHOTO_BODY, POST_ID, POST_ID + ".png", "photo.png"), null));
        assertEquals("application/json", contentTypes.get(0));
        assertFalse(bodies.get(0).contains("attachment://"));
    }

    @Test
    void theRelaysDuplicateAnswerFinishesTheEntry() throws Exception {
        start(202, "{\"ok\":true,\"duplicate\":true}");
        assertTrue(resend(queued("{}", POST_ID, null, null), null), "a 202 has no message id but it is done");
        assertEquals(1, hits.get());
    }

    @Test
    void aServerErrorKeepsTheEntryWithoutRetryingInProcess() throws Exception {
        start(503, "<!DOCTYPE HTML>");
        assertFalse(resend(queued("{}", POST_ID, null, null), null));
        assertEquals(1, hits.get());
    }

    @Test
    void aRefusedPayloadFinishesTheEntry() throws Exception {
        start(400, "{\"code\":50035}");
        assertTrue(resend(queued("{}", POST_ID, null, null), null), "a bad payload will never succeed");
    }

    @Test
    void resendFinishedByStatus() {
        for (int code : new int[] {200, 201, 202, 204, 400, 401, 403, 404, 413}) {
            assertTrue(DiscordWebhookClient.resendFinished(code), "finished on " + code);
        }
        for (int code : new int[] {408, 429, 500, 502, 503, 504}) {
            assertFalse(DiscordWebhookClient.resendFinished(code), "kept on " + code);
        }
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create(url()))
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
    }

    @Test
    void aLivePostWithAnIdReportsA503AsAFailure() throws Exception {
        start(503, "<!DOCTYPE HTML>");
        Exception e = assertThrows(Exception.class, () ->
                DiscordWebhookClient.sendAndParse(tid -> request(), null, true).get(30, TimeUnit.SECONDS));
        Throwable cause = e.getCause() instanceof CompletionException ? e.getCause().getCause() : e.getCause();
        assertInstanceOf(DiscordHttp.RetryableStatusException.class, cause);
        assertEquals(1, hits.get(), "still no in-process retry of a message-creating post");
    }

    @Test
    void aLivePostWithNoIdStillSwallowsA503() throws Exception {
        start(503, "<!DOCTYPE HTML>");
        assertNull(DiscordWebhookClient.sendAndParse(tid -> request(), null, false).get(30, TimeUnit.SECONDS));
    }
}
