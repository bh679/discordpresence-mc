package games.brennan.discordpresence.compat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

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
         * @param authorId         the Discord user id (may be empty if the transport didn't carry it)
         * @param authorName       the author's display name
         * @param content          the raw message content
         * @param targetPlayerUuid the Minecraft UUID (as a string) of the player whose thread this
         *                         message belongs to, or {@code null} when the message isn't anchored
         *                         to a player's thread (e.g. a top-level channel post). Lets a handler
         *                         scope delivery to the one player instead of every online player.
         * @param reply            posts a plain reply to the same channel
         * @return {@code true} if this message was a command and was handled (suppress the normal relay)
         */
        boolean onCommand(String authorId, String authorName, String content, String targetPlayerUuid, Reply reply);
    }

    /**
     * Context-carrying variant of {@link Handler}: receives the full {@link InboundContext}
     * (including the Discord message id and channel id). Same threading and return semantics.
     */
    public interface ContextHandler {
        /**
         * @param ctx   the inbound message (message id, channel id, author, content, target player)
         * @param reply posts a plain reply to the same channel
         * @return {@code true} if this message was a command and was handled (suppress the normal relay)
         */
        boolean onCommand(InboundContext ctx, Reply reply);
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Handler> HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<ContextHandler> CONTEXT_HANDLERS = new CopyOnWriteArrayList<>();

    private DiscordCommandHooks() {}

    /** Register a handler. No-op for {@code null}. */
    public static void install(Handler handler) {
        if (handler != null) {
            HANDLERS.add(handler);
        }
    }

    /** Register a context handler. No-op for {@code null}. */
    public static void installContext(ContextHandler handler) {
        if (handler != null) {
            CONTEXT_HANDLERS.add(handler);
        }
    }

    /**
     * Fire every {@link Handler} and {@link ContextHandler}; called by DiscordPresence's inbound
     * message handler. Every handler runs (no short-circuit); the result is the OR of their returns.
     *
     * @return {@code true} if any handler consumed the message (it was a command).
     */
    public static boolean fire(InboundContext ctx, Reply reply) {
        if (ctx == null) {
            return false;
        }
        boolean handled = fire(ctx.authorId(), ctx.authorName(), ctx.content(), ctx.targetPlayerUuid(), reply);
        for (ContextHandler handler : CONTEXT_HANDLERS) {
            try {
                handled |= handler.onCommand(ctx, reply);
            } catch (Throwable t) {
                // A misbehaving host handler must never break the chat bridge.
                LOGGER.warn("DiscordCommandHooks context handler threw", t);
            }
        }
        return handled;
    }

    /**
     * Fire the legacy {@link Handler}s only (kept for binary compatibility); prefer
     * {@link #fire(InboundContext, Reply)}, which also runs {@link ContextHandler}s.
     *
     * @param targetPlayerUuid the Minecraft UUID (as a string) of the player whose thread the message
     *                         is in, or {@code null} when it isn't anchored to a player's thread.
     * @return {@code true} if any handler consumed the message (it was a command).
     */
    public static boolean fire(String authorId, String authorName, String content,
                               String targetPlayerUuid, Reply reply) {
        boolean handled = false;
        for (Handler handler : HANDLERS) {
            try {
                handled |= handler.onCommand(authorId, authorName, content, targetPlayerUuid, reply);
            } catch (Throwable t) {
                // A misbehaving host handler must never break the chat bridge.
                LOGGER.warn("DiscordCommandHooks handler threw", t);
            }
        }
        return handled;
    }
}
