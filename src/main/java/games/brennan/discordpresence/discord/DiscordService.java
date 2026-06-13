package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates Discord Presence: posts the join message, maintains one persistent
 * thread per player, manages the online/death reactions, announces advancements in
 * the thread, and bridges chat both ways. The single entry point the event
 * subscribers delegate to.
 *
 * <p>Two per-player maps hold <i>futures</i>, not resolved values, so events that
 * fire before a webhook POST / thread creation completes still chain correctly:</p>
 * <ul>
 *   <li>{@code sessionMessages} — this session's join message (the first-join
 *       anchor, or the in-thread "started" message). Carries the online/death
 *       reactions; cleared on logout.</li>
 *   <li>{@code threadFutures} — the player's thread id, so an advancement earned
 *       while the first-join thread is still being created still lands once it
 *       resolves. Backed by the durable {@link DiscordThreadStore}.</li>
 * </ul>
 *
 * <p><b>Two-way chat.</b> game→Discord relays each {@code ServerChatEvent} line
 * through the webhook under the player's name. Discord→game runs a persistent
 * {@link DiscordGateway}: a Discord message is relayed in only when it is
 * <i>anchored</i> to a message the mod posted for a player — a reply to it, or a
 * message in the thread spun off it — resolved via {@link PlayerMessageIndex}
 * (messageId → UUID; a message-thread's id equals its source message id, so a reply
 * and a thread message are the same lookup). Inbound delivery hops to the server
 * thread and broadcasts as a system message, so it never re-fires
 * {@code ServerChatEvent} (no relay loop).</p>
 *
 * <p><b>Network gate.</b> On a dedicated server the relay is on by default; in
 * singleplayer all Discord network use waits on the one-time client consent.</p>
 *
 * <p>Continuations run on the HTTP executor; the server thread only ever enqueues
 * non-blocking work.</p>
 */
public final class DiscordService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String THREAD_STORE_FILE = "discordpresence-threads.json";
    private static final String AUTO_RESPONSE_STORE_FILE = "discordpresence-autoresponse.json";
    private static final int MAX_RELAY_CHARS = 256;

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final DiscordThreadStore threadStore = new DiscordThreadStore();
    private final ConcurrentHashMap<UUID, CompletableFuture<DiscordMessageRef>> sessionMessages =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> threadFutures =
            new ConcurrentHashMap<>();

    /** messageId → owning player, for every message we post on a player's behalf (join + relayed chat). */
    private final PlayerMessageIndex reverse = new PlayerMessageIndex();

    /** In-game "whispers into the darkness" auto-responses — game-side only, no Discord I/O. */
    private final AutoResponder autoResponder = new AutoResponder();

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
     * Whether the mod may use the network in the current context. Dedicated servers
     * are always allowed (opt out via config); singleplayer / LAN waits on the
     * one-time client consent.
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

    /**
     * On server start: load the persisted player→thread map (before any join), then
     * open the gateway when inbound relay is enabled, consented and a token is set.
     */
    public void onServerStarted(MinecraftServer startedServer) {
        this.server = startedServer;
        loadThreads();
        autoResponder.loadState(FMLPaths.CONFIGDIR.get().resolve(AUTO_RESPONSE_STORE_FILE));
        LOGGER.info("Discord Presence onServerStarted: dedicated={}, webhookSet={}, consent={}, relayDiscordToGame={}, botTokenSet={}",
                startedServer.isDedicatedServer(), enabled(),
                DiscordPresenceClientConfig.getConsent(), DiscordPresenceConfig.isRelayDiscordToGame(),
                !DiscordPresenceConfig.getBotToken().isBlank());
        if (!enabled() || !networkAllowed(startedServer) || !DiscordPresenceConfig.isRelayDiscordToGame()) {
            return;
        }
        String token = DiscordPresenceConfig.getBotToken();
        if (token.isBlank()) {
            LOGGER.warn("relayDiscordToGame is on but botToken is blank — gateway not started.");
            return;
        }
        LOGGER.info("Discord Presence: starting gateway…");
        DiscordGateway gw = new DiscordGateway(token, this::onDiscordMessage);
        this.gateway = gw;
        gw.start();
    }

    /** Load the persisted player→thread map from the server config dir. */
    private void loadThreads() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(THREAD_STORE_FILE);
        threadStore.load(file);
    }

    /** Drop per-session tracking and close the gateway on server stop (durable stores stay on disk). */
    public void clearAll() {
        DiscordGateway gw = gateway;
        gateway = null;
        if (gw != null) {
            gw.stop();
        }
        sessionMessages.clear();
        threadFutures.clear();
        reverse.clear();
        autoResponder.clear();
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

        if (!DiscordPresenceConfig.isCreateThreadOnJoin()) {
            // Threads disabled: plain per-session top-level message + reactions (v0.1.0).
            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            sessionMessages.put(uuid, postReactIndex(content, name, uuid, null, onlineEmoji));
            return;
        }

        String existingThread = threadStore.get(uuid);
        if (existingThread != null) {
            // Returning player: post "started the game" INTO their thread.
            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            sessionMessages.put(uuid, postReactIndex(content, name, uuid, existingThread, onlineEmoji));
            threadFutures.put(uuid, CompletableFuture.completedFuture(existingThread));
            return;
        }

        // First join ever: post the top-level anchor, react on + index it, and create
        // the player's thread from it (persisting the id once it resolves).
        String content = format(DiscordPresenceConfig.getFirstJoinMessageTemplate(), name);
        CompletableFuture<DiscordMessageRef> anchor =
                DiscordWebhookClient.post(content, name, uuid, null);

        sessionMessages.put(uuid, anchor.thenApply(ref -> {
            if (ref != null) {
                DiscordBotClient.addReaction(ref, onlineEmoji);
                reverse.put(ref.messageId(), uuid);
            }
            return ref;
        }));

        String threadName = format(DiscordPresenceConfig.getThreadNameTemplate(), name);
        int autoArchive = DiscordPresenceConfig.getThreadAutoArchiveMinutes();
        CompletableFuture<String> created = anchor
                .thenCompose(ref -> ref == null
                        ? CompletableFuture.completedFuture(null)
                        : DiscordThreadClient.createThreadFromMessage(ref, threadName, autoArchive))
                .thenApply(threadId -> {
                    if (threadId != null) {
                        threadStore.put(uuid, threadId);
                    }
                    return threadId;
                });
        threadFutures.put(uuid, created);
    }

    public void onPlayerLeave(UUID uuid) {
        CompletableFuture<DiscordMessageRef> posted = sessionMessages.remove(uuid);
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
        CompletableFuture<DiscordMessageRef> posted = sessionMessages.get(uuid);
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

    /**
     * Announce an earned advancement in the player's thread (when it passes the
     * namespace/display filter). Chains on the thread future, so an advancement
     * earned while the first-join thread is still being created still posts.
     */
    public void onAdvancement(ServerPlayer player, AdvancementHolder holder) {
        if (!enabled() || !networkAllowed(player.server) || !DiscordPresenceConfig.isCreateThreadOnJoin()) {
            return; // no thread to post into
        }

        String namespace = holder.id().getNamespace();
        Optional<DisplayInfo> display = holder.value().display();
        Set<String> allowed = new HashSet<>(DiscordPresenceConfig.getAdvancementNamespaces());
        boolean onlyDisplay = DiscordPresenceConfig.isOnlyDisplayAdvancements();
        if (!shouldPostAdvancement(namespace, display.isPresent(), allowed, onlyDisplay)) {
            return;
        }

        UUID uuid = player.getUUID();
        CompletableFuture<String> threadFuture = threadFutures.get(uuid);
        if (threadFuture == null) {
            String existing = threadStore.get(uuid);
            if (existing == null) {
                return; // no thread, and none in flight
            }
            threadFuture = CompletableFuture.completedFuture(existing);
        }

        // Title + full description + frame colour come from the advancement's display
        // (rendered as a coloured embed); the content line is the configurable attribution.
        String title = display.map(d -> d.getTitle().getString()).orElse(holder.id().toString());
        String description = display.map(d -> d.getDescription().getString()).orElse("");
        Integer color = display.map(DiscordService::frameColor).orElse(null);
        String content = formatAdvancement(
                DiscordPresenceConfig.getAdvancementMessageTemplate(),
                player.getGameProfile().getName(), title);

        threadFuture.thenAccept(threadId -> {
            if (threadId != null) {
                DiscordThreadClient.postEmbed(threadId, content, title, description, color);
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
        // In-game flavour feedback when the player is "whispering into the darkness".
        autoResponder.onPlayerChat(player);
    }

    /**
     * Discord→game: relay an inbound Discord message into in-game chat, but only when
     * it is anchored to a tracked player message (a reply to it, or a message in the
     * thread spun off it). Called off-thread by {@link DiscordGateway}; hops to the
     * server thread before broadcasting.
     */
    public void onDiscordMessage(InboundMessage msg) {
        if (msg != null) {
            LOGGER.debug("Discord inbound: author={}, ref={}, channel={}, bot={}, webhook={}, anchored={}",
                    msg.authorName(), msg.referencedMessageId(), msg.channelId(), msg.bot(), msg.hasWebhookId(),
                    reverse.contains(msg.referencedMessageId()) || reverse.contains(msg.channelId()));
        }
        if (!isRelayable(msg, reverse)) {
            return; // our own posts/bots, or not anchored to a tracked player message
        }
        // A Discord reply reached the server — disarm that player's auto-responses.
        UUID owner = reverse.get(msg.referencedMessageId());
        if (owner == null) {
            owner = reverse.get(msg.channelId());
        }
        if (owner != null) {
            autoResponder.onDiscordActivity(owner);
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

    // --- helpers ---

    /** The advancement frame's chat colour as a Discord embed colour (0xRRGGBB), or null. */
    private static Integer frameColor(DisplayInfo display) {
        ChatFormatting chatColor = display.getType().getChatColor();
        return chatColor != null ? chatColor.getColor() : null;
    }

    /**
     * Post {@code content} as the player (optionally into a thread), add the online
     * reaction, and index the ref so a Discord reply to it routes back in-game.
     */
    private CompletableFuture<DiscordMessageRef> postReactIndex(
            String content, String name, UUID uuid, String threadId, String onlineEmoji) {
        return DiscordWebhookClient.post(content, name, uuid, threadId)
                .thenApply(ref -> {
                    if (ref != null) {
                        DiscordBotClient.addReaction(ref, onlineEmoji);
                        reverse.put(ref.messageId(), uuid);
                    }
                    return ref;
                });
    }

    // --- pure helpers (unit-tested) ---------------------------------------

    /**
     * Whether an advancement should be announced, given the configured filters.
     * {@code allowedNamespaces} empty = all namespaces.
     */
    static boolean shouldPostAdvancement(String namespace, boolean hasDisplay,
                                         Set<String> allowedNamespaces, boolean onlyDisplay) {
        if (onlyDisplay && !hasDisplay) {
            return false;
        }
        return allowedNamespaces.isEmpty() || allowedNamespaces.contains(namespace);
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
     * capped. {@link Component#literal} does not parse {@code §} colour codes, so no
     * formatting/command injection is possible.
     */
    static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > MAX_RELAY_CHARS ? t.substring(0, MAX_RELAY_CHARS) + "…" : t;
    }

    /** Replace {@code {player}} in a template. */
    static String format(String template, String player) {
        return template.replace("{player}", player);
    }

    /** Replace {@code {player}} and {@code {advancement}} in a template. */
    static String formatAdvancement(String template, String player, String advancement) {
        return template.replace("{player}", player).replace("{advancement}", advancement);
    }
}
