package games.brennan.discordpresence.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Inbound gateway client for <b>relay-mode</b>. Instead of connecting to Discord's gateway (which
 * needs the bot token), DP connects to the relay's {@code /<cap>/gateway} WebSocket and receives the
 * same {@link InboundMessage} events the relay already decoded from Discord. Far simpler than
 * {@link DiscordGateway} — no IDENTIFY/heartbeat/resume (the relay owns the Discord protocol): just
 * connect, reassemble + parse the relay's JSON, hand off to {@code onMessage}, and reconnect with
 * exponential backoff on drop. DP holds no token; the relay does.
 *
 * <p>Best-effort and self-healing like the rest of the Discord I/O. The JDK WebSocket delivers
 * callbacks serially, so the unsynchronised reassembly buffer is safe.</p>
 */
final class RelayGateway implements GatewayConnection, WebSocket.Listener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final String url; // wss://…/<cap>/gateway
    private final Consumer<InboundMessage> onMessage;
    private final StringBuilder buffer = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile boolean running;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    RelayGateway(String url, Consumer<InboundMessage> onMessage) {
        this.url = url;
        this.onMessage = onMessage;
    }

    // --- lifecycle ---

    @Override
    public void start() {
        if (url == null || url.isBlank()) {
            LOGGER.warn("Relay gateway not started: relay gateway URL is blank.");
            return;
        }
        running = true;
        connect();
    }

    @Override
    public void stop() {
        running = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutting down");
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                ws.abort();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    // --- connect / reconnect ---

    private void connect() {
        if (!running) {
            return;
        }
        reconnecting.set(false);
        try {
            DiscordHttp.CLIENT.newWebSocketBuilder()
                    .connectTimeout(DiscordHttp.TIMEOUT)
                    .buildAsync(URI.create(url), this)
                    .whenComplete((ws, err) -> {
                        if (err != null || ws == null) {
                            LOGGER.warn("Relay gateway connect failed: {}",
                                    err != null ? err.toString() : "null socket");
                            reconnect();
                        } else {
                            reconnectAttempts.set(0);
                            LOGGER.info("Relay gateway connected — listening for relayed Discord messages.");
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn("Relay gateway connect threw: {}", e.toString());
            reconnect();
        }
    }

    private void reconnect() {
        if (!running) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return; // a reconnect is already scheduled
        }
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        long delay = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(reconnectAttempts.getAndIncrement(), 6));
        LOGGER.info("Relay gateway reconnecting in {}s.", delay);
        DiscordHttp.SCHEDULER.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    // --- WebSocket.Listener (reassemble fragments + flow control, like GatewayListener) ---

    @Override
    public void onOpen(WebSocket ws) {
        this.webSocket = ws;
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String complete = buffer.toString();
            buffer.setLength(0);
            handle(complete);
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        LOGGER.info("Relay gateway closed: {} {}", statusCode, reason);
        reconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        LOGGER.warn("Relay gateway socket error: {}", error.toString());
        reconnect();
    }

    private void handle(String json) {
        InboundMessage msg;
        try {
            msg = parse(json);
        } catch (Exception e) {
            LOGGER.warn("Relay gateway: ignoring unparseable message ({} chars).", json.length());
            return;
        }
        try {
            onMessage.accept(msg);
        } catch (Exception e) {
            LOGGER.warn("Relay gateway: failed to route message", e);
        }
    }

    /**
     * Parse one relay JSON event into an {@link InboundMessage}. The relay sends exactly the six
     * fields DP needs (see the relay's gateway parseMessage). Missing keys tolerate to blank/false/null.
     * Package-visible for unit testing.
     */
    static InboundMessage parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new InboundMessage(
                str(o, "authorName", ""),
                str(o, "content", ""),
                boolOf(o, "bot"),
                boolOf(o, "hasWebhookId"),
                strOrNull(o, "channelId"),
                strOrNull(o, "referencedMessageId"));
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static boolean boolOf(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }
}
