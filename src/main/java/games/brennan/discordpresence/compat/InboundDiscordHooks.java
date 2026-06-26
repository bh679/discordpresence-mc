package games.brennan.discordpresence.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observer seam for inbound Discord messages, for bundler mods (e.g. Dungeon Train) that need to
 * react to what Discord users say — without DiscordPresence taking a dependency on them and without
 * exposing the package-private {@code InboundMessage} carrier.
 *
 * <p>Listeners fire for every human (non-bot, non-webhook) message DiscordPresence receives, on the
 * gateway/network thread, <em>before</em> the in-game relay decision — so a host can observe a
 * message even when it isn't anchored to a tracked player thread. Keep listeners cheap and
 * thread-safe; exceptions are swallowed so one bad listener can't break the relay.</p>
 */
public final class InboundDiscordHooks {

    /** Notified for each inbound human Discord message. {@code authorId} is the Discord user id
     *  (may be empty if the transport didn't carry it); {@code authorName} is the display name. */
    public interface Listener {
        void onInboundMessage(String authorId, String authorName, String content);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private InboundDiscordHooks() {}

    /** Register a listener. No-op for {@code null}. */
    public static void install(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Fire all listeners; called by DiscordPresence's inbound message handler. */
    public static void fire(String authorId, String authorName, String content) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onInboundMessage(authorId, authorName, content);
            } catch (Throwable ignored) {
                // A misbehaving host listener must never break the chat bridge.
            }
        }
    }
}
