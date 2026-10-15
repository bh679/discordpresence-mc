package games.brennan.discordpresence.compat;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Discord reaction seam for bundler mods (e.g. Dungeon Train). In relay mode the relay forwards a
 * {@code {"type":"reactions"}} frame whenever a reaction on a message in the bridged channel changes;
 * DiscordPresence parses it into a {@link ReactionSnapshot} and fires it here.
 *
 * <p><b>Threading:</b> {@link #fire} runs on the gateway/network thread. Listeners must be cheap,
 * thread-safe, and hop to the server/client thread before touching game state. Exceptions are
 * swallowed and logged.</p>
 *
 * <p>Direct (bot-token) mode does not produce reaction snapshots.</p>
 */
public final class DiscordReactionHooks {

    /**
     * One emoji's reaction tally on a message.
     *
     * @param name     unicode emoji, or the custom emoji's name
     * @param id       custom emoji snowflake, or {@code null} for unicode emoji
     * @param animated whether a custom emoji is animated
     * @param count    total reactions with this emoji
     * @param users    display names of (some of) the reacting users; may be shorter than {@code count}
     */
    public record Reaction(String name, @Nullable String id, boolean animated, int count, List<String> users) {
        public Reaction {
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    /**
     * The change that produced a snapshot.
     *
     * @param action   {@code "add"}, {@code "remove"} or {@code "clear"}
     * @param name     emoji name (may be empty for {@code clear})
     * @param id       custom emoji snowflake, or {@code null}
     * @param animated whether a custom emoji is animated
     * @param userName display name of the user who reacted (may be empty)
     */
    public record ReactionEvent(String action, String name, @Nullable String id, boolean animated, String userName) {}

    /**
     * The full current reaction state of one message, plus the event that changed it.
     *
     * @param messageId the Discord message id
     * @param channelId the channel/thread id, or {@code null}
     * @param list      every emoji currently on the message (empty after a clear)
     * @param event     the triggering change, or {@code null} if the relay didn't send one
     */
    public record ReactionSnapshot(String messageId, @Nullable String channelId, List<Reaction> list,
                                   @Nullable ReactionEvent event) {
        public ReactionSnapshot {
            list = list == null ? List.of() : List.copyOf(list);
        }
    }

    /** Notified of each reaction change. Called on the gateway/network thread — hop threads. */
    public interface Listener {
        void onReactions(ReactionSnapshot snapshot);
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private DiscordReactionHooks() {}

    /** Register a listener. No-op for {@code null}. */
    public static void install(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Fire all listeners (gateway/network thread); called by DiscordPresence's relay gateway. */
    public static void fire(ReactionSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onReactions(snapshot);
            } catch (Throwable t) {
                LOGGER.warn("DiscordReactionHooks listener threw", t);
            }
        }
    }
}
