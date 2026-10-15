package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.compat.DiscordReactionHooks;
import games.brennan.discordpresence.compat.DiscordReactionHooks.Reaction;
import games.brennan.discordpresence.compat.DiscordReactionHooks.ReactionEvent;
import games.brennan.discordpresence.compat.DiscordReactionHooks.ReactionSnapshot;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Frames carrying a {@code type} key are typed events: {@code "reactions"} is parsed and fired
 * through {@link DiscordReactionHooks}; unknown types are ignored. Untyped frames are messages.</p>
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
        dispatch(json, onMessage, DiscordReactionHooks::fire);
    }

    /** What {@link #dispatch} did with a frame (for tests and debug logging). */
    enum FrameKind { MESSAGE, REACTIONS, IGNORED, MALFORMED }

    /**
     * Route one complete relay frame. The {@code type} key is checked FIRST: {@code "reactions"} →
     * parsed into a {@link ReactionSnapshot} and handed to {@code onReactions}; any other non-null
     * type is ignored (forward compatibility with newer relays); no type → the legacy message frame,
     * parsed and handed to {@code onMessage}. Never throws. Package-visible for unit testing.
     */
    static FrameKind dispatch(String json, Consumer<InboundMessage> onMessage,
                              Consumer<ReactionSnapshot> onReactions) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("Relay gateway: ignoring unparseable message ({} chars).", json == null ? 0 : json.length());
            return FrameKind.MALFORMED;
        }
        String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : null;
        if (type != null) {
            if (!"reactions".equals(type)) {
                LOGGER.debug("Relay gateway: ignoring frame of unknown type '{}'.", type);
                return FrameKind.IGNORED;
            }
            ReactionSnapshot snapshot = parseReactions(o);
            if (snapshot == null) {
                LOGGER.debug("Relay gateway: ignoring malformed reactions frame.");
                return FrameKind.MALFORMED;
            }
            try {
                onReactions.accept(snapshot);
            } catch (Exception e) {
                LOGGER.warn("Relay gateway: failed to route reactions", e);
            }
            return FrameKind.REACTIONS;
        }
        InboundMessage msg;
        try {
            msg = parse(o);
        } catch (Exception e) {
            LOGGER.warn("Relay gateway: ignoring unparseable message ({} chars).", json.length());
            return FrameKind.MALFORMED;
        }
        try {
            onMessage.accept(msg);
        } catch (Exception e) {
            LOGGER.warn("Relay gateway: failed to route message", e);
        }
        return FrameKind.MESSAGE;
    }

    // --- reactions frame ---

    static final int MAX_EMOJIS = 20;
    static final int MAX_USERS = 50;
    static final int MAX_NAME_LEN = 64;
    static final int MAX_ID_LEN = 32;

    /**
     * Parse a {@code {"type":"reactions","reactions":{...}}} frame defensively. Returns {@code null}
     * when the frame is unusable (no {@code reactions} object or no message id). Individual malformed
     * entries are skipped; list sizes and string lengths are clamped. Package-visible for testing.
     */
    static ReactionSnapshot parseReactions(JsonObject frame) {
        try {
            if (!frame.has("reactions") || !frame.get("reactions").isJsonObject()) {
                return null;
            }
            JsonObject r = frame.getAsJsonObject("reactions");
            String messageId = clamp(prim(r, "messageId"), MAX_ID_LEN);
            if (messageId == null || messageId.isBlank()) {
                return null;
            }
            String channelId = clamp(prim(r, "channelId"), MAX_ID_LEN);
            List<Reaction> list = new ArrayList<>();
            if (r.has("list") && r.get("list").isJsonArray()) {
                JsonArray arr = r.getAsJsonArray("list");
                for (JsonElement el : arr) {
                    if (list.size() >= MAX_EMOJIS) {
                        break;
                    }
                    Reaction reaction = parseReaction(el);
                    if (reaction != null) {
                        list.add(reaction);
                    }
                }
            }
            ReactionEvent event = null;
            if (r.has("event") && r.get("event").isJsonObject()) {
                event = parseEvent(r.getAsJsonObject("event"));
            }
            return new ReactionSnapshot(messageId, channelId, list, event);
        } catch (Exception e) {
            return null;
        }
    }

    private static Reaction parseReaction(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String name = clamp(prim(o, "name"), MAX_NAME_LEN);
        if (name == null || name.isBlank()) {
            return null;
        }
        int count = 0;
        try {
            count = o.has("count") && o.get("count").isJsonPrimitive() ? Math.max(0, o.get("count").getAsInt()) : 0;
        } catch (Exception ignored) {
            // non-numeric count → 0
        }
        List<String> users = new ArrayList<>();
        if (o.has("users") && o.get("users").isJsonArray()) {
            for (JsonElement u : o.getAsJsonArray("users")) {
                if (users.size() >= MAX_USERS) {
                    break;
                }
                if (u != null && u.isJsonPrimitive()) {
                    String user = clamp(u.getAsString(), MAX_NAME_LEN);
                    if (user != null && !user.isBlank()) {
                        users.add(user);
                    }
                }
            }
        }
        return new Reaction(name, clamp(prim(o, "id"), MAX_ID_LEN), boolPrim(o, "animated"),
                Math.max(count, users.size()), users);
    }

    private static ReactionEvent parseEvent(JsonObject o) {
        String action = prim(o, "action");
        if (!"add".equals(action) && !"remove".equals(action) && !"clear".equals(action)) {
            return null;
        }
        String name = clamp(prim(o, "name"), MAX_NAME_LEN);
        String userName = clamp(prim(o, "userName"), MAX_NAME_LEN);
        return new ReactionEvent(action, name == null ? "" : name, clamp(prim(o, "id"), MAX_ID_LEN),
                boolPrim(o, "animated"), userName == null ? "" : userName);
    }

    /** A primitive's string value, or null when absent / null / not a primitive. */
    private static String prim(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean boolPrim(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean()
                && o.get(key).getAsBoolean();
    }

    private static String clamp(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        // Don't split a surrogate pair at the cut.
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end);
    }

    // --- message frame ---

    /**
     * Parse one relay JSON message event into an {@link InboundMessage}, including the message's own
     * {@code id}. The relay sends the fields DP needs (see the relay's gateway parseMessage). Missing keys tolerate to blank/false/null.
     * Package-visible for unit testing.
     */
    static InboundMessage parse(String json) {
        return parse(JsonParser.parseString(json).getAsJsonObject());
    }

    /** {@link #parse(String)} for an already-parsed frame. */
    static InboundMessage parse(JsonObject o) {
        return new InboundMessage(
                str(o, "authorId", ""), // forward-compatible: present once the relay forwards it
                str(o, "authorName", ""),
                str(o, "content", ""),
                boolOf(o, "bot"),
                boolOf(o, "hasWebhookId"),
                strOrNull(o, "channelId"),
                strOrNull(o, "referencedMessageId"),
                strOrNull(o, "id"));
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
