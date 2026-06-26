package games.brennan.discordpresence.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (no-I/O, no-state) helpers for the Discord Gateway v10 wire protocol:
 * opcode + intent constants, the IDENTIFY / RESUME / HEARTBEAT payload builders,
 * and parsers for the inbound frames the relay cares about.
 *
 * <p>Kept entirely side-effect-free and Minecraft-free so the protocol encoding
 * can be unit-tested without a live gateway connection.</p>
 *
 * @see <a href="https://discord.com/developers/docs/topics/gateway-events">Discord gateway events</a>
 */
final class GatewayPayloads {

    private GatewayPayloads() {}

    // --- Gateway opcodes ---
    static final int OP_DISPATCH = 0;
    static final int OP_HEARTBEAT = 1;
    static final int OP_IDENTIFY = 2;
    static final int OP_RESUME = 6;
    static final int OP_RECONNECT = 7;
    static final int OP_INVALID_SESSION = 9;
    static final int OP_HELLO = 10;
    static final int OP_HEARTBEAT_ACK = 11;

    // --- Gateway intents (bit flags) ---
    static final int INTENT_GUILDS = 1;                  // 1 << 0
    static final int INTENT_GUILD_PRESENCES = 1 << 8;    // 256 — PRIVILEGED (portal toggle required)
    static final int INTENT_GUILD_MESSAGES = 1 << 9;     // 512
    static final int INTENT_MESSAGE_CONTENT = 1 << 15;   // 32768 — PRIVILEGED (portal toggle required)

    /**
     * The two-way-chat intents: guilds + their text-channel messages + the message text itself. The
     * fixed baseline the mod has always identified with; presence tracking layers on via
     * {@link #intentsFor(boolean, boolean)} only when opted in.
     */
    static final int INTENTS = INTENT_GUILDS | INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT; // 33281

    /**
     * The gateway intents to IDENTIFY with for the requested features. {@code GUILDS} is always
     * included (it delivers the {@code GUILD_CREATE} presence snapshot). {@code wantMessages} adds the
     * two-way-chat message intents (incl. the privileged Message Content); {@code wantPresence} adds
     * the privileged {@code GUILD_PRESENCES}. Pure — so each privileged intent is requested only when
     * its feature is actually enabled: a presence-only operator never asks for Message Content, and an
     * upgrader who enables neither presence nor (the default-on) chat still gets exactly the value the
     * mod has always sent. {@code intentsFor(true, false) == }{@link #INTENTS}.
     */
    static int intentsFor(boolean wantMessages, boolean wantPresence) {
        int intents = INTENT_GUILDS;
        if (wantMessages) {
            intents |= INTENT_GUILD_MESSAGES | INTENT_MESSAGE_CONTENT;
        }
        if (wantPresence) {
            intents |= INTENT_GUILD_PRESENCES;
        }
        return intents;
    }

    /** Query appended to the gateway URL: JSON encoding on API v10. */
    static final String GATEWAY_QUERY = "?v=10&encoding=json";

    // --- outbound payload builders ---

    /** IDENTIFY with the default two-way-chat {@link #INTENTS}. */
    static String identify(String token) {
        return identify(token, INTENTS);
    }

    /** IDENTIFY with an explicit intent bitset (see {@link #intentsFor(boolean, boolean)}). */
    static String identify(String token, int intents) {
        JsonObject props = new JsonObject();
        props.addProperty("os", "java");
        props.addProperty("browser", "DiscordPresence");
        props.addProperty("device", "DiscordPresence");

        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", intents);
        d.add("properties", props);
        return op(OP_IDENTIFY, d);
    }

    static String resume(String token, String sessionId, int seq) {
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", seq);
        return op(OP_RESUME, d);
    }

    /** Heartbeat's {@code d} is the last sequence number seen, or JSON null if none yet. */
    static String heartbeat(Integer lastSeq) {
        JsonObject root = new JsonObject();
        root.addProperty("op", OP_HEARTBEAT);
        if (lastSeq == null) {
            root.add("d", JsonNull.INSTANCE);
        } else {
            root.addProperty("d", lastSeq);
        }
        return root.toString();
    }

    private static String op(int opcode, JsonObject d) {
        JsonObject root = new JsonObject();
        root.addProperty("op", opcode);
        root.add("d", d);
        return root.toString();
    }

    // --- inbound parse ---

    static int opOf(JsonObject payload) {
        return intOrDefault(payload, "op", -1);
    }

    /** Sequence number {@code s}; null when absent/null (only DISPATCH frames carry it). */
    static Integer seqOf(JsonObject payload) {
        return payload.has("s") && !payload.get("s").isJsonNull() ? payload.get("s").getAsInt() : null;
    }

    /** Dispatch event name {@code t}; null on non-dispatch frames. */
    static String typeOf(JsonObject payload) {
        return str(payload, "t");
    }

    static long helloInterval(JsonObject payload) {
        return payload.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
    }

    /** Op 9 INVALID_SESSION's {@code d} is a bare boolean: is the session resumable? */
    static boolean invalidSessionResumable(JsonObject payload) {
        return payload.has("d") && !payload.get("d").isJsonNull() && payload.get("d").getAsBoolean();
    }

    /**
     * Parse a {@code MESSAGE_CREATE} data object into the fields the relay needs.
     * Tolerant of missing keys (blanks / nulls) so a malformed frame can never
     * throw into the gateway receive loop.
     */
    static InboundMessage message(JsonObject d) {
        String content = strOr(d, "content", "");
        String channelId = str(d, "channel_id");

        String authorId = "";
        String authorName = "";
        boolean bot = false;
        if (d.has("author") && d.get("author").isJsonObject()) {
            JsonObject author = d.getAsJsonObject("author");
            authorId = strOr(author, "id", "");
            String global = str(author, "global_name");
            String username = strOr(author, "username", "");
            authorName = (global != null && !global.isBlank()) ? global : username;
            bot = boolOf(author, "bot");
        }

        boolean hasWebhookId = d.has("webhook_id") && !d.get("webhook_id").isJsonNull();

        String referencedMessageId = null;
        if (d.has("message_reference") && d.get("message_reference").isJsonObject()) {
            referencedMessageId = str(d.getAsJsonObject("message_reference"), "message_id");
        }

        return new InboundMessage(authorId, authorName, content, bot, hasWebhookId, channelId, referencedMessageId);
    }

    /**
     * Parse a {@code PRESENCE_UPDATE} data object into the (user id, status) pair the presence
     * tracker needs. Tolerant of missing keys (blank/null) so a malformed frame can never throw
     * into the gateway receive loop.
     */
    static PresenceUpdate presence(JsonObject d) {
        String userId = null;
        if (d.has("user") && d.get("user").isJsonObject()) {
            userId = str(d.getAsJsonObject("user"), "id");
        }
        return new PresenceUpdate(userId, str(d, "status"));
    }

    /**
     * Parse the {@code presences} array of a {@code GUILD_CREATE} data object — the initial presence
     * snapshot for a guild (each entry the same shape {@link #presence} reads). Returns an empty list
     * when absent or malformed. {@code PRESENCE_UPDATE} is changes-only and is not replayed on
     * connect, so this snapshot is the only way to learn who is already online at connect time.
     */
    static List<PresenceUpdate> guildCreatePresences(JsonObject d) {
        List<PresenceUpdate> out = new ArrayList<>();
        if (d.has("presences") && d.get("presences").isJsonArray()) {
            for (JsonElement el : d.getAsJsonArray("presences")) {
                if (el != null && el.isJsonObject()) {
                    out.add(presence(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    // --- small JSON accessors (null/missing tolerant) ---

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        String v = str(o, key);
        return v != null ? v : fallback;
    }

    private static boolean boolOf(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static int intOrDefault(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }
}
