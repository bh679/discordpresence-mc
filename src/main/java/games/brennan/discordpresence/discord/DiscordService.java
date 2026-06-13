package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates Discord Presence: posts the join message, tracks it per player,
 * manages the online/death reactions, and bridges chat both ways. The single
 * entry point the event subscribers delegate to.
 *
 * <p><b>Per-player state</b> ({@code active}) maps each online player's UUID to a
 * {@code CompletableFuture<DiscordMessageRef>} — the <i>future</i>, not the
 * resolved ref — so a logout/death that fires before the webhook POST completes
 * still chains correctly and the online reaction can never leak.</p>
 *
 * <p><b>Two-way chat.</b> game→Discord relays each {@code ServerChatEvent} line
 * through the webhook under the player's name. Discord→game runs a persistent
 * {@link DiscordGateway}: a Discord message is relayed in only when it is
 * <i>anchored</i> to a message the mod posted for a player — a reply to it, or a
 * message in the thread spun off it — resolved via {@link PlayerMessageIndex}
 * (messageId → UUID; a message-thread's id equals its source message id, so a
 * reply and a thread message are the same lookup). Inbound delivery hops to the
 * server thread and broadcasts to everyone; it is a system message, so it never
 * re-fires {@code ServerChatEvent} (no relay loop).</p>
 *
 * <p><b>Network gate.</b> On a dedicated server the relay is on by default; in
 * singleplayer all Discord network use waits on the one-time client consent.</p>
 */
public final class DiscordService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RELAY_CHARS = 256;

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<UUID, CompletableFuture<DiscordMessageRef>> active =
            new ConcurrentHashMap<>();

    /** messageId → owning player, for every message we post on a player's behalf (join + relayed chat). */
    private final PlayerMessageIndex reverse = new PlayerMessageIndex();

    /** One-shot WARN so a missing privileged intent doesn't spam the log. */
    private final AtomicBoolean warnedBlankContent = new AtomicBoolean(false);

    private volatile MinecraftServer server;
    private volatile DiscordGateway gateway;

    private DiscordService() {}

    /** Off entirely when no webhook URL is configured. */
    private boolean enabled() {
        return !DiscordPresenceConfig.getWebhookUrl().isBlank();
    }

    /**
     * Whether the mod may use the network in the current context. Dedicated
     * servers are always allowed (opt out via config); singleplayer / LAN waits
     * on the one-time client consent.
     */
    private boolean networkAllowed(MinecraftServer srv) {
        if (srv == null) {
            return false;
        }
        if (srv.isDedicatedServer()) {
            return true;
        }
        return DiscordPresenceClientConfig.isGranted();
    }

    // --- server lifecycle ---

    /** Opens the gateway (if inbound relay is enabled and consented) once the server is up. */
    public void onServerStarted(MinecraftServer startedServer) {
        this.server = startedServer;
        if (!enabled() || !networkAllowed(startedServer) || !DiscordPresenceConfig.isRelayDiscordToGame()) {
            return;
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            LOGGER.warn("relayDiscordToGame is on but botToken is blank — gateway not started.");
            return;
        }
        DiscordGateway gw = new DiscordGateway(token, this::onDiscordMessage);
        this.gateway = gw;
        gw.start();
    }

    /** Drop all tracked state and close the gateway on server stop. */
    public void clearAll() {
        DiscordGateway gw = gateway;
        gateway = null;
        if (gw != null) {
            gw.stop();
        }
        active.clear();
        reverse.clear();
        server = null;
    }

    // --- player events ---

    public void onPlayerJoin(ServerPlayer player) {
        if (!enabled() || !networkAllowed(player.server)) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        String onlineEmoji = DiscordPresenceConfig.getOnlineEmoji();

        CompletableFuture<DiscordMessageRef> posted = DiscordWebhookClient
                .postJoinMessage(name, uuid)
                .thenApply(ref -> {
                    if (ref != null) {
                        DiscordBotClient.addReaction(ref, onlineEmoji);
                        reverse.put(ref.messageId(), uuid);
                    }
                    return ref;
                });
        active.put(uuid, posted);
    }

    public void onPlayerLeave(UUID uuid) {
        CompletableFuture<DiscordMessageRef> posted = active.remove(uuid);
        if (posted == null) {
            return;
        }
        String onlineEmoji = DiscordPresenceConfig.getOnlineEmoji();
        posted.thenAccept(ref -> {
            if (ref != null) {
                DiscordBotClient.removeOwnReaction(ref, onlineEmoji);
            }
        });
    }

    public void onPlayerDeath(UUID uuid) {
        CompletableFuture<DiscordMessageRef> posted = active.get(uuid);
        if (posted == null) {
            return;
        }
        String deathEmoji = DiscordPresenceConfig.getDeathEmoji();
        posted.thenAccept(ref -> {
            if (ref != null) {
                DiscordBotClient.addReaction(ref, deathEmoji);
            }
        });
    }

    // --- two-way chat ---

    /** game→Discord: relay one in-game chat line under the player's name, indexing the result. */
    public void onGameChat(ServerPlayer player, String text) {
        if (!enabled() || !networkAllowed(player.server) || !DiscordPresenceConfig.isRelayGameToDiscord()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        DiscordWebhookClient.postChat(name, uuid, text).thenAccept(ref -> {
            if (ref != null) {
                reverse.put(ref.messageId(), uuid);
            }
        });
    }

    /**
     * Discord→game: relay an inbound Discord message into in-game chat, but only
     * when it is anchored to a tracked player message (a reply to it, or a message
     * in the thread spun off it). Called off-thread by {@link DiscordGateway};
     * hops to the server thread before broadcasting.
     */
    public void onDiscordMessage(InboundMessage msg) {
        if (!isRelayable(msg, reverse)) {
            return; // our own posts/bots, or not anchored to a tracked player message
        }
        String content = sanitize(msg.content());
        if (content.isBlank()) {
            if (warnedBlankContent.compareAndSet(false, true)) {
                LOGGER.warn("A relayed Discord message had empty content — enable the 'Message Content' "
                        + "privileged intent for the bot in the Discord Developer Portal.");
            }
            return;
        }
        String line = DiscordPresenceConfig.getDiscordToGameFormat()
                .replace("{user}", sanitize(msg.authorName()))
                .replace("{msg}", content);

        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        // Hop to the server thread; broadcast as a SYSTEM message (does not re-fire ServerChatEvent).
        srv.execute(() -> srv.getPlayerList().broadcastSystemMessage(Component.literal(line), false));
    }

    /**
     * Pure relay decision: a non-bot, non-webhook message anchored to a tracked
     * player message — a reply to it, or a message in the thread spun off it
     * ({@code channelId} == that message's id). Extracted so it is unit-testable.
     */
    static boolean isRelayable(InboundMessage msg, PlayerMessageIndex index) {
        if (msg == null || msg.isOwnOrBot()) {
            return false;
        }
        return index.contains(msg.referencedMessageId()) || index.contains(msg.channelId());
    }

    /**
     * Make Discord-supplied text safe for in-game display: single-line and length
     * capped. {@link Component#literal} does not parse {@code §} colour codes, so
     * no formatting/command injection is possible.
     */
    static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > MAX_RELAY_CHARS ? t.substring(0, MAX_RELAY_CHARS) + "…" : t;
    }
}
