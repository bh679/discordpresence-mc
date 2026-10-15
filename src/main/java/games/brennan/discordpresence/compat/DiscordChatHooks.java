package games.brennan.discordpresence.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chat-bridge seams for bundler mods (e.g. Dungeon Train):
 * <ul>
 *   <li>{@link PostedListener} — told the Discord message id of each in-game chat line DiscordPresence
 *       relayed to Discord, so the host can later match Discord reactions to the line.</li>
 *   <li>{@link InboundDeliverer} — may take over delivering a relayed Discord→game line to its
 *       recipient (e.g. to render it with extra decoration such as reactions).</li>
 * </ul>
 * Exceptions thrown by host code are swallowed and logged so they can never break the bridge.
 */
public final class DiscordChatHooks {

    /** Notified after an in-game chat line was posted to Discord. */
    public interface PostedListener {
        /**
         * Called on an HTTP/async thread (not the server thread) once Discord accepted the post —
         * hop to the server/client thread before touching game state.
         *
         * @param player    the player who sent the line
         * @param rawText   the player's original chat text (before any mention/ping rewriting)
         * @param channelId the channel or thread id the line was posted into
         * @param messageId the Discord message id of the posted line
         */
        void onGameChatPosted(UUID player, String rawText, String channelId, String messageId);
    }

    /** Offered each relayed Discord→game line before DiscordPresence sends it itself. */
    public interface InboundDeliverer {
        /**
         * Called on the server thread.
         *
         * @param recipient   the online player the line is addressed to
         * @param ctx         the inbound Discord message
         * @param defaultLine the line DiscordPresence would send (formatted per config)
         * @return {@code true} if this deliverer delivered the line (DiscordPresence then skips its own send)
         */
        boolean deliver(ServerPlayer recipient, InboundContext ctx, Component defaultLine);
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<PostedListener> POSTED = new CopyOnWriteArrayList<>();
    private static final List<InboundDeliverer> DELIVERERS = new CopyOnWriteArrayList<>();

    private DiscordChatHooks() {}

    /** Register a posted-line listener. No-op for {@code null}. */
    public static void installPosted(PostedListener listener) {
        if (listener != null) {
            POSTED.add(listener);
        }
    }

    /** Register an inbound deliverer. No-op for {@code null}. */
    public static void installDeliverer(InboundDeliverer deliverer) {
        if (deliverer != null) {
            DELIVERERS.add(deliverer);
        }
    }

    /** Fire all posted-line listeners; called by DiscordPresence after a successful chat post. */
    public static void firePosted(UUID player, String rawText, String channelId, String messageId) {
        for (PostedListener listener : POSTED) {
            try {
                listener.onGameChatPosted(player, rawText, channelId, messageId);
            } catch (Throwable t) {
                LOGGER.warn("DiscordChatHooks posted listener threw", t);
            }
        }
    }

    /**
     * Offer a line to every deliverer (all run; no short-circuit).
     *
     * @return {@code true} if any deliverer delivered it — the caller must then skip its own send.
     */
    public static boolean fireDeliver(ServerPlayer recipient, InboundContext ctx, Component defaultLine) {
        boolean delivered = false;
        for (InboundDeliverer deliverer : DELIVERERS) {
            try {
                delivered |= deliverer.deliver(recipient, ctx, defaultLine);
            } catch (Throwable t) {
                LOGGER.warn("DiscordChatHooks inbound deliverer threw", t);
            }
        }
        return delivered;
    }
}
