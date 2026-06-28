package games.brennan.discordpresence.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Operator-command seam for inbound Discord messages, for bundler mods (e.g. Dungeon Train) that
 * want to treat certain Discord messages as <em>commands</em> and reply in the same channel —
 * without DiscordPresence taking a dependency on them and without exposing the package-private
 * message/transport types.
 *
 * <p>This is the command-with-reply counterpart to {@link InboundDiscordHooks} (which is a fire-and-
 * forget observer). Handlers fire for every human (non-bot, non-webhook) message DiscordPresence
 * receives, on the gateway/network thread, <em>before</em> the in-game relay decision. A handler
 * that returns {@code true} marks the message as a consumed command: DiscordPresence then skips the
 * normal Discord→game relay for it (so {@code !command} text isn't echoed into in-game chat).</p>
 *
 * <p>Keep handlers cheap and thread-safe; exceptions are swallowed so one bad handler can't break
 * the relay. The {@link Reply} posts a plain message back to the originating channel via the bot —
 * it is a no-op when the bot/channel isn't available.</p>
 */
public final class DiscordCommandHooks {

    /** Posts a plain text reply back to the channel the command arrived in. */
    public interface Reply {
        void send(String content);
    }

    /** Handles a candidate command message. */
    public interface Handler {
        /**
         * @param authorId   the Discord user id (may be empty if the transport didn't carry it)
         * @param authorName the author's display name
         * @param content    the raw message content
         * @param reply      posts a plain reply to the same channel
         * @return {@code true} if this message was a command and was handled (suppress the normal relay)
         */
        boolean onCommand(String authorId, String authorName, String content, Reply reply);
    }

    private static final List<Handler> HANDLERS = new CopyOnWriteArrayList<>();

    private DiscordCommandHooks() {}

    /** Register a handler. No-op for {@code null}. */
    public static void install(Handler handler) {
        if (handler != null) {
            HANDLERS.add(handler);
        }
    }

    /**
     * Fire all handlers; called by DiscordPresence's inbound message handler.
     *
     * @return {@code true} if any handler consumed the message (it was a command).
     */
    public static boolean fire(String authorId, String authorName, String content, Reply reply) {
        boolean handled = false;
        for (Handler handler : HANDLERS) {
            try {
                handled |= handler.onCommand(authorId, authorName, content, reply);
            } catch (Throwable ignored) {
                // A misbehaving host handler must never break the chat bridge.
            }
        }
        return handled;
    }
}
