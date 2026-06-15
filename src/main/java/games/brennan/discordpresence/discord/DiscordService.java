package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates Discord Presence end-to-end: posts the join message, maintains one
 * persistent thread per player, manages the online/death reactions, announces
 * advancements in the thread, and bridges chat both ways. The single entry point
 * the event subscribers delegate to.
 *
 * <p><b>Per-player state</b> — two maps hold <i>futures</i>, not resolved values,
 * so events that fire before a webhook POST / thread creation completes still
 * chain correctly:</p>
 * <ul>
 *   <li>{@code sessionMessages} — the message carrying this session's online/death
 *       reactions: the player's top-level thread message (the anchor) when they have a
 *       thread, otherwise a plain top-level message; cleared on logout.</li>
 *   <li>{@code threadFutures} — the player's thread id, backed by the durable
 *       {@link DiscordThreadStore}, so an advancement earned while the first-join
 *       thread is still being created still lands once it resolves.</li>
 * </ul>
 *
 * <p><b>Two-way chat.</b> game→Discord relays each {@code ServerChatEvent} line
 * through the webhook under the player's name (into their thread when they have
 * one). Discord→game runs a persistent {@link DiscordGateway}: a Discord message
 * is relayed in only when it is <i>anchored</i> to something the mod posted for a
 * player — a reply to one of our messages, or a message in the player's thread —
 * resolved via {@link PlayerMessageIndex} (a message-thread's id equals its source
 * message id, so a reply and a thread message are the same lookup). Inbound
 * delivery hops to the server thread and broadcasts a system message, so it never
 * re-fires {@code ServerChatEvent} (no relay loop).</p>
 *
 * <p><b>Network gate.</b> On a dedicated server the relay is on by default; in
 * singleplayer all Discord network use waits on the one-time client consent.</p>
 */
public final class DiscordService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RELAY_CHARS = 256;
    private static final String THREAD_STORE_FILE = "discordpresence-threads.json";
    private static final String AUTO_RESPONSE_STORE_FILE = "discordpresence-autoresponse.json";
    private static final String PRESENCE_STORE_FILE = "discordpresence-presence.json";

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final DiscordThreadStore threadStore = new DiscordThreadStore();

    /** Durable record of who currently carries the online reaction, for crash-safe cleanup. */
    private final OnlinePresenceStore presenceStore = new OnlinePresenceStore();

    private final ConcurrentHashMap<UUID, CompletableFuture<DiscordMessageRef>> sessionMessages =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> threadFutures =
            new ConcurrentHashMap<>();

    /** messageId / threadId → owning player, for everything we post on a player's behalf. */
    private final PlayerMessageIndex reverse = new PlayerMessageIndex();

    /** In-game "whispers into the darkness" auto-responses — game-side only, no Discord I/O. */
    private final AutoResponder autoResponder = new AutoResponder();

    /** One-shot WARN so a missing privileged intent doesn't spam the log. */
    private final AtomicBoolean warnedBlankContent = new AtomicBoolean(false);

    private volatile MinecraftServer server;
    private volatile GatewayConnection gateway;

    /** The recurring online-reaction refresh/reconcile task, cancelled on server stop. */
    private volatile ScheduledFuture<?> presenceTask;

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

    /** Load the persisted player→thread map on server start (before any join). */
    public void loadThreads() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(THREAD_STORE_FILE);
        threadStore.load(file);
    }

    /** Opens the gateway (if inbound relay is enabled and consented) once the server is up. */
    public void onServerStarted(MinecraftServer startedServer) {
        this.server = startedServer;
        autoResponder.loadState(FMLPaths.CONFIGDIR.get().resolve(AUTO_RESPONSE_STORE_FILE));
        LOGGER.info("Discord Presence onServerStarted: dedicated={}, webhookSet={}, consent={}, relayDiscordToGame={}, botTokenSet={}",
                startedServer.isDedicatedServer(), enabled(),
                DiscordPresenceClientConfig.getConsent(), DiscordPresenceConfig.isRelayDiscordToGame(),
                !DiscordPresenceConfig.getBotToken().isBlank());
        startPresenceTracking(startedServer);
        if (!enabled() || !networkAllowed(startedServer) || !DiscordPresenceConfig.isRelayDiscordToGame()) {
            return;
        }
        GatewayConnection gw;
        if (DiscordPresenceConfig.isRelayMode()) {
            LOGGER.info("Discord Presence: starting relay gateway…");
            gw = new RelayGateway(DiscordPresenceConfig.getRelayGatewayUrl(), this::onDiscordMessage);
        } else {
            String token = DiscordPresenceConfig.getBotToken();
            if (token.isBlank()) {
                LOGGER.warn("relayDiscordToGame is on but botToken is blank — gateway not started.");
                return;
            }
            LOGGER.info("Discord Presence: starting gateway…");
            gw = new DiscordGateway(token, this::onDiscordMessage);
        }
        this.gateway = gw;
        gw.start();
    }

    // --- online-reaction heartbeat ---

    /**
     * Load the persisted online presence and start the reaction heartbeat. The immediate
     * reconcile pass runs while no players are connected yet, so any green reaction left by a
     * crashed prior session is cleared; the recurring task then refreshes it while players are
     * online and removes it when they are not. Independent of the inbound-relay gating — it only
     * needs reactions to be in use.
     */
    private void startPresenceTracking(MinecraftServer srv) {
        presenceStore.load(FMLPaths.CONFIGDIR.get().resolve(PRESENCE_STORE_FILE));
        if (!enabled() || !networkAllowed(srv)
                || DiscordPresenceConfig.getOnlineEmoji().isBlank() || DiscordHttp.botUnavailable()) {
            return; // reactions not in use (no emoji / no bot) → nothing to refresh or clean up
        }
        reconcilePresence(); // crash-recovery: no one is connected yet, so stale greens are removed
        int minutes = DiscordPresenceConfig.getOnlineReactionRefreshMinutes();
        if (minutes > 0) {
            presenceTask = DiscordHttp.SCHEDULER.scheduleAtFixedRate(
                    this::reconcilePresence, minutes, minutes, TimeUnit.MINUTES);
        }
    }

    /**
     * Reconcile the persisted online presence against who is actually connected. The connected
     * set <i>and</i> the presence snapshot are captured together on the server thread (the player
     * list is server-thread-only) so they are a consistent pair, then the Discord I/O runs off the
     * server thread. Best-effort.
     */
    private void reconcilePresence() {
        MinecraftServer srv = server;
        if (srv == null || presenceStore.isEmpty() || DiscordPresenceConfig.getOnlineEmoji().isBlank()) {
            return;
        }
        try {
            srv.execute(() -> {
                Set<UUID> connected = new HashSet<>();
                for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                    connected.add(p.getUUID());
                }
                Map<UUID, OnlinePresenceStore.PresenceEntry> snapshot = presenceStore.entries();
                long now = System.currentTimeMillis();
                DiscordHttp.EXECUTOR.execute(() -> applyReconcile(connected, snapshot, now));
            });
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: presence reconcile skipped: {}", e.toString());
        }
    }

    /**
     * Off-thread half of {@link #reconcilePresence}: for the consistent ({@code connected},
     * {@code snapshot}) pair, refresh the green reaction + {@code lastSeen} for those still online,
     * and remove a now-stale reaction + drop the entry for anyone who is not — then persist. A
     * player who reconnected after the snapshot keeps their fresh entry ({@code dropStale} only
     * removes unchanged entries), so the next heartbeat re-asserts their reaction.
     */
    private void applyReconcile(Set<UUID> connected, Map<UUID, OnlinePresenceStore.PresenceEntry> snapshot, long now) {
        String emoji = DiscordPresenceConfig.getOnlineEmoji();
        if (emoji.isBlank()) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        Map<UUID, OnlinePresenceStore.PresenceEntry> stale = new HashMap<>();
        for (Map.Entry<UUID, OnlinePresenceStore.PresenceEntry> e : snapshot.entrySet()) {
            UUID uuid = e.getKey();
            DiscordMessageRef ref = e.getValue().ref();
            if (connected.contains(uuid)) {
                DiscordBotClient.addReaction(ref, emoji); // idempotent — heals a join reaction that failed to post
                online.add(uuid);
            } else {
                DiscordBotClient.removeOwnReaction(ref, emoji); // stale → no longer online
                stale.put(uuid, e.getValue());
                LOGGER.info("Discord Presence: cleared stale online reaction for {} (last seen {}).",
                        uuid, ageDescription(e.getValue().lastSeen(), now));
            }
        }
        presenceStore.touch(online, now);
        presenceStore.dropStale(stale);
    }

    /** Human-readable "Nm ago" for a lastSeen epoch-millis, or "unknown" when never recorded. */
    private static String ageDescription(long lastSeen, long now) {
        if (lastSeen <= 0) {
            return "unknown";
        }
        return Math.max(0, (now - lastSeen) / 60_000L) + "m ago";
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
            // Threads disabled: plain per-session top-level message + reactions (v0.1.0 behaviour).
            JoinMessage jm = joinMessage(DiscordPresenceConfig.getJoinMessageTemplate(), uuid, name);
            sessionMessages.put(uuid, recordOnlineWhenReady(
                    trackForReplies(postAndReact(jm.content(), name, uuid, null, onlineEmoji, jm.allowedUserIds()), uuid), uuid));
            return;
        }

        DiscordMessageRef stored = threadStore.get(uuid);
        if (stored != null) {
            // Returning player: index the thread id so replies / messages there route back
            // in-game, and post the per-session "started the game" line INTO their thread as an
            // activity marker / reply anchor. The live reaction, though, goes on the TOP-LEVEL
            // thread message (the anchor) — not this in-thread line — every session.
            String threadId = stored.messageId();
            reverse.put(threadId, uuid);
            threadFutures.put(uuid, CompletableFuture.completedFuture(threadId));

            JoinMessage jm = joinMessage(DiscordPresenceConfig.getJoinMessageTemplate(), uuid, name);
            trackForReplies(DiscordWebhookClient.post(jm.content(), name, uuid, threadId, jm.allowedUserIds()), uuid);

            sessionMessages.put(uuid, recordOnlineWhenReady(anchorRef(uuid, stored).thenApply(anchor -> {
                if (anchor != null) {
                    DiscordBotClient.addReaction(anchor, onlineEmoji);
                }
                return anchor;
            }), uuid));
            return;
        }

        // First join ever: post the top-level anchor, react on it, index it (its id == the thread
        // id), and create the player's thread from it (persisting the anchor ref once it resolves).
        JoinMessage jm = joinMessage(DiscordPresenceConfig.getFirstJoinMessageTemplate(), uuid, name);
        CompletableFuture<DiscordMessageRef> anchor =
                DiscordWebhookClient.post(jm.content(), name, uuid, null, jm.allowedUserIds());

        sessionMessages.put(uuid, recordOnlineWhenReady(anchor.thenApply(ref -> {
            if (ref != null) {
                DiscordBotClient.addReaction(ref, onlineEmoji);
                reverse.put(ref.messageId(), uuid);
            }
            return ref;
        }), uuid));

        String threadName = format(DiscordPresenceConfig.getThreadNameTemplate(), name);
        int autoArchive = DiscordPresenceConfig.getThreadAutoArchiveMinutes();
        CompletableFuture<String> created = anchor.thenCompose(ref -> {
            if (ref == null) {
                return CompletableFuture.completedFuture(null);
            }
            return DiscordThreadClient.createThreadFromMessage(ref, threadName, autoArchive)
                    .thenApply(threadId -> {
                        if (threadId != null) {
                            // Persist the anchor (parent channel + thread id, which == the anchor
                            // message id) so future sessions react on the top-level thread message.
                            threadStore.put(uuid, new DiscordMessageRef(ref.channelId(), threadId));
                            reverse.put(threadId, uuid); // thread id == anchor message id → anchors inbound routing
                        }
                        return threadId;
                    });
        });
        threadFutures.put(uuid, created);
    }

    public void onPlayerLeave(UUID uuid) {
        presenceStore.markOffline(uuid); // clean logout: drop the crash-recovery record
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

    public void onPlayerDeath(ServerPlayer player, DamageSource source) {
        UUID uuid = player.getUUID();

        // Existing behaviour: add the death reaction to this session's join message.
        CompletableFuture<DiscordMessageRef> posted = sessionMessages.get(uuid);
        if (posted != null) {
            String deathEmoji = DiscordPresenceConfig.getDeathEmoji();
            posted.thenAccept(ref -> {
                if (ref != null) {
                    DiscordBotClient.addReaction(ref, deathEmoji);
                }
            });
        }

        // New: a rich death report (cause + basic stats + held/worn item image). Skipped when a
        // bundling mod drives its own richer report via postDeathReport() (set autoDeathReport=false).
        if (DiscordPresenceConfig.isAutoDeathReport()) {
            String name = player.getGameProfile().getName();
            String cause = source != null
                    ? source.getLocalizedDeathMessage(player).getString()
                    : name + " died";
            postDeathReport(player, "💀 " + name, cause, basicDeathFields(player), heldAndArmor(player));
        }
    }

    // --- two-way chat ---

    /** game→Discord: relay one in-game chat line under the player's name, into their thread when they have one. */
    public void onGameChat(ServerPlayer player, String text) {
        if (!enabled() || !networkAllowed(player.server) || !DiscordPresenceConfig.isRelayGameToDiscord()) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();

        // Configured chat-tag triggers (e.g. @dev): rewrite to a real <@id> mention and collect the ids
        // allowed to ping, reusing the trusted-mention path. Applied whenever the line is relayed —
        // gate on or off — so a tag pings like a player-name tag.
        List<MentionTrigger> triggers = MentionTrigger.parse(DiscordPresenceConfig.getGameRelayMentions());
        boolean mentioned = MentionTrigger.matches(text, triggers);

        // Engaged-only gate (opt-in): relay only when Discord is actively conversing with this player,
        // or the line carries a trigger. When gated out, suggest tagging the dev (unless already tagged).
        boolean relay = true;
        boolean suggestMention = false;
        if (DiscordPresenceConfig.isRelayGameToDiscordEngagedOnly()) {
            relay = relayGameChat(true, mentioned, autoResponder.hasActiveDiscordConversation(uuid));
            suggestMention = !mentioned;
        }

        if (relay) {
            String content = MentionTrigger.applyPings(text, triggers);
            List<String> pingIds = MentionTrigger.pingUserIds(text, triggers);
            String threadId = threadStore.threadId(uuid); // into the player's thread if they have one (null → top-level)
            DiscordWebhookClient.postChat(name, uuid, content, threadId, pingIds).thenAccept(ref -> {
                if (ref != null) {
                    reverse.put(ref.messageId(), uuid);
                }
            });
        }
        // In-game flavour feedback when the player is "whispering into the darkness" (+ a "tag the dev"
        // nudge when the gate would otherwise swallow the line). Self-gates on the armed/cooldown state.
        autoResponder.onPlayerChat(player, suggestMention);
    }

    /**
     * Discord→game: relay an inbound Discord message into in-game chat, but only
     * when it is anchored to a tracked player message (a reply to it, or a message
     * in the player's thread). Called off-thread by {@link DiscordGateway}; hops to
     * the server thread before broadcasting.
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
     * Pure game→Discord relay decision for the engaged-only gate: relay when the gate is off, or the
     * line mentioned a trigger, or Discord is actively conversing with the player. Extracted so it is
     * unit-testable.
     */
    static boolean relayGameChat(boolean engagedOnly, boolean mentioned, boolean activeConversation) {
        return !engagedOnly || mentioned || activeConversation;
    }

    /**
     * A yellow-highlighted copy of {@code rawText} for in-game display when it contains a configured
     * chat-tag token (e.g. {@code @dev}), or {@code null} when there is nothing to highlight — so the
     * caller leaves vanilla chat untouched. Independent of the relay gate: the tag is coloured whenever
     * it is configured. Server-side (the broadcast everyone sees).
     */
    public Component colorizeChatTags(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }
        List<MentionTrigger> triggers = MentionTrigger.parse(DiscordPresenceConfig.getGameRelayMentions());
        if (triggers.isEmpty()) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (MentionTrigger t : triggers) {
            tokens.add(t.token());
        }
        return ChatTagHighlighter.hasMatch(rawText, tokens) ? ChatTagHighlighter.toComponent(rawText, tokens) : null;
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

    // --- advancements ---

    /**
     * Thread-local gate toggled by {@link #runWithAdvancementAnnounceSuppressed(Runnable)}.
     * When set, {@link #onAdvancement} is a no-op. Thread-scoped because
     * {@code AdvancementEarnEvent} fires synchronously on the server thread inside
     * {@code PlayerAdvancements.award(...)} — the same thread a bundling mod runs its
     * programmatic grants on — so the flag is visible to the handler and never bleeds
     * into other players' grants on other threads.
     */
    private static final ThreadLocal<Boolean> SUPPRESS_ANNOUNCE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Run {@code body} with advancement announcements suppressed on the current
     * thread: any {@link #onAdvancement} firing while {@code body} runs is skipped.
     * The previous state is restored in a {@code finally}, so nesting and exceptions
     * are safe.
     *
     * <p><b>Public API.</b> A bundling mod (e.g. Dungeon Train) wraps a batch of
     * <i>programmatic</i> advancement grants it does not want mirrored to Discord —
     * for example re-granting a player's cross-world advancements on login, which
     * would otherwise re-fire {@code AdvancementEarnEvent} and double-post an
     * advancement the player already earned. Genuine first-time earns are unaffected.</p>
     */
    public static void runWithAdvancementAnnounceSuppressed(Runnable body) {
        boolean prev = SUPPRESS_ANNOUNCE.get();
        SUPPRESS_ANNOUNCE.set(Boolean.TRUE);
        try {
            body.run();
        } finally {
            SUPPRESS_ANNOUNCE.set(prev);
        }
    }

    /** Whether advancement announcements are currently suppressed on this thread. Package-private for tests. */
    static boolean isAdvancementAnnounceSuppressed() {
        return SUPPRESS_ANNOUNCE.get();
    }

    /**
     * Announce an earned advancement in the player's thread (when it passes the
     * namespace/display filter). Chains on the thread future, so an advancement
     * earned while the first-join thread is still being created still posts.
     */
    public void onAdvancement(ServerPlayer player, AdvancementHolder holder) {
        if (SUPPRESS_ANNOUNCE.get()) {
            return; // bundling mod is re-granting advancements (e.g. DT cross-world replay) — don't mirror to Discord
        }
        if (!enabled() || !DiscordPresenceConfig.isCreateThreadOnJoin()) {
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
            String existing = threadStore.threadId(uuid);
            if (existing == null) {
                return; // no thread, and none in flight
            }
            threadFuture = CompletableFuture.completedFuture(existing);
        }

        // Title + description + frame colour + icon come from the advancement's display
        // (rendered as a coloured embed with the item icon as a thumbnail); the content
        // line is the configurable attribution.
        String title = display.map(d -> d.getTitle().getString()).orElse(holder.id().toString());
        String description = display.map(d -> d.getDescription().getString()).orElse("");
        Integer color = display.map(DiscordService::frameColor).orElse(null);
        String iconUrl = iconUrlFor(display);
        String content = formatAdvancement(
                DiscordPresenceConfig.getAdvancementMessageTemplate(),
                player.getGameProfile().getName(), title);
        List<DeathField> fields = advancementFields(holder, description);

        threadFuture.thenAccept(threadId -> {
            if (threadId != null) {
                DiscordThreadClient.postEmbed(threadId, content, title, description, color, iconUrl, fields);
            }
        });
    }

    /** The advancement frame's chat colour as a Discord embed colour (0xRRGGBB), or null. */
    private static Integer frameColor(DisplayInfo display) {
        ChatFormatting chatColor = display.getType().getChatColor();
        return chatColor != null ? chatColor.getColor() : null;
    }

    /**
     * The thumbnail URL for the advancement's icon item, or null when the icon is
     * disabled or there is no display. Resolves the icon {@link net.minecraft.world.item.ItemStack}
     * to its registry id and substitutes it into the configured template.
     */
    private static String iconUrlFor(Optional<DisplayInfo> display) {
        if (!DiscordPresenceConfig.isShowAdvancementIcon() || display.isEmpty()) {
            return null;
        }
        var iconId = BuiltInRegistries.ITEM.getKey(display.get().getIcon().getItem());
        return advancementIconUrl(
                DiscordPresenceConfig.getAdvancementIconUrlTemplate(),
                iconId.getNamespace(), iconId.getPath());
    }

    /**
     * Optional embed fields for an advancement: the configurable "Requirements" field
     * listing the sub-goals (criteria) not already named in {@code description}, or an
     * empty list when the feature is disabled or nothing novel remains.
     */
    private static List<DeathField> advancementFields(AdvancementHolder holder, String description) {
        if (!DiscordPresenceConfig.isShowAdvancementRequirements()) {
            return List.of();
        }
        String reqs = advancementRequirements(
                holder.value().criteria().keySet(),
                description,
                DiscordPresenceConfig.getAdvancementRequirementsMax());
        if (reqs == null) {
            return List.of();
        }
        return List.of(new DeathField(DiscordPresenceConfig.getAdvancementRequirementsLabel(), reqs));
    }

    /** Drop per-session tracking + close the gateway on server stop (the durable stores stay on disk). */
    public void clearAll() {
        ScheduledFuture<?> pt = presenceTask;
        presenceTask = null;
        if (pt != null) {
            pt.cancel(false); // stop the heartbeat; the presence store stays for startup crash-recovery
        }
        GatewayConnection gw = gateway;
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

    // --- death report -----------------------------------------------------

    /**
     * Post a death / run-summary embed for {@code player}: a coloured embed with
     * {@code title} + {@code description} + ordered {@code fields}, plus an image
     * composed from {@code iconItems} (e.g. weapon + armor) attached and shown.
     * Posts into the player's thread when they have one, else top-level, under the
     * player's name/avatar. Best-effort; all HTTP + image work runs off-thread.
     *
     * <p><b>Public API.</b> A bundling mod (e.g. Dungeon Train) calls this with its
     * own run stats + item stacks. The {@code iconItems} are read on the calling
     * (server) thread and snapshotted to icon URLs immediately, so later mutation /
     * clearing of those stacks (keep-inventory, respawn) cannot affect the image.</p>
     */
    public void postDeathReport(ServerPlayer player, String title, String description,
                                List<DeathField> fields, List<ItemStack> iconItems) {
        if (!enabled() || !networkAllowed(player.server)) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        JsonObject embed = buildReportEmbed(title, description, fields, DiscordPresenceConfig.getDeathReportEmbedColor());
        // Snapshot the icons NOW (server thread); the off-thread work below only sees URLs + counts.
        List<DeathImageComposer.IconSpec> icons =
                DiscordPresenceConfig.isShowDeathReportImage() ? resolveIcons(iconItems) : List.of();
        String threadId = threadStore.threadId(uuid); // player's thread, or null = top-level

        CompletableFuture
                .supplyAsync(() -> DeathImageComposer.compose(icons), DiscordHttp.EXECUTOR)
                .exceptionally(t -> {
                    LOGGER.warn("Death report image compose failed: {}", t.toString());
                    return null;
                })
                .thenCompose(png -> DiscordWebhookClient.postReport(name, uuid, threadId, embed, png, "death.png"))
                .thenAccept(ref -> {
                    if (ref != null) {
                        reverse.put(ref.messageId(), uuid);
                    }
                })
                .exceptionally(t -> {
                    LOGGER.warn("Death report post failed: {}", t.toString());
                    return null;
                });
    }

    /**
     * Build the death-report embed JSON (title, description, colour, inline fields).
     * Pure (colour passed in) so it is unit-testable without a loaded config.
     */
    static JsonObject buildReportEmbed(String title, String description, List<DeathField> fields, int color) {
        JsonObject embed = new JsonObject();
        if (title != null && !title.isBlank()) {
            embed.addProperty("title", title);
        }
        if (description != null && !description.isBlank()) {
            embed.addProperty("description", description);
        }
        embed.addProperty("color", color);
        if (fields != null && !fields.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (DeathField f : fields) {
                if (f == null || f.name() == null || f.value() == null
                        || f.name().isBlank() || f.value().isBlank()) {
                    continue;
                }
                JsonObject jf = new JsonObject();
                jf.addProperty("name", f.name());
                jf.addProperty("value", f.value());
                jf.addProperty("inline", true);
                arr.add(jf);
            }
            if (!arr.isEmpty()) {
                embed.add("fields", arr);
            }
        }
        return embed;
    }

    /** Resolve item stacks to icon slots (URL + count) on the server thread; empty stacks → empty slots. */
    private static List<DeathImageComposer.IconSpec> resolveIcons(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        String template = DiscordPresenceConfig.getDeathReportIconUrlTemplate();
        List<DeathImageComposer.IconSpec> specs = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                specs.add(new DeathImageComposer.IconSpec(null, 0));
                continue;
            }
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String url = advancementIconUrl(template, id.getNamespace(), id.getPath());
            specs.add(new DeathImageComposer.IconSpec(url, stack.getCount()));
        }
        return specs;
    }

    /** Basic vanilla death-screen fields for DiscordPresence's own auto report. */
    private static List<DeathField> basicDeathFields(ServerPlayer player) {
        List<DeathField> fields = new ArrayList<>();
        fields.add(new DeathField("Score", Integer.toString(player.getScore())));
        var pos = player.blockPosition();
        fields.add(new DeathField("Location", pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
        fields.add(new DeathField("Dimension", player.level().dimension().location().toString()));
        fields.add(new DeathField("XP Level", Integer.toString(player.experienceLevel)));
        return fields;
    }

    /** The player's held item + the four armor slots, for the auto report image. */
    private static List<ItemStack> heldAndArmor(ServerPlayer player) {
        return List.of(
                player.getMainHandItem(),
                player.getItemBySlot(EquipmentSlot.HEAD),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.FEET));
    }

    // --- pure helpers (unit-tested) ---------------------------------------

    /** Index a posted message's id → player once it resolves, so Discord replies to it route back. */
    private CompletableFuture<DiscordMessageRef> trackForReplies(CompletableFuture<DiscordMessageRef> future, UUID uuid) {
        return future.thenApply(ref -> {
            if (ref != null) {
                reverse.put(ref.messageId(), uuid);
            }
            return ref;
        });
    }

    /**
     * Persist the player as online on the reaction-bearing message once it resolves, so the
     * green reaction can be removed after a crash even though the in-memory session is gone.
     * Skipped when the online reaction is disabled (blank emoji).
     */
    private CompletableFuture<DiscordMessageRef> recordOnlineWhenReady(
            CompletableFuture<DiscordMessageRef> future, UUID uuid) {
        return future.thenApply(ref -> {
            if (ref != null && !DiscordPresenceConfig.getOnlineEmoji().isBlank() && !DiscordHttp.botUnavailable()) {
                presenceStore.recordOnline(uuid, ref, System.currentTimeMillis());
            }
            return ref;
        });
    }

    /** Post {@code content} as the player (optionally into a thread) and add the online reaction. */
    private static CompletableFuture<DiscordMessageRef> postAndReact(
            String content, String name, UUID uuid, String threadId, String onlineEmoji, List<String> allowedUserIds) {
        return DiscordWebhookClient.post(content, name, uuid, threadId, allowedUserIds)
                .thenApply(ref -> {
                    if (ref != null) {
                        DiscordBotClient.addReaction(ref, onlineEmoji);
                    }
                    return ref;
                });
    }

    /**
     * The player's top-level thread message (the anchor) that carries this session's
     * reactions. Uses the stored parent channel when present; for a legacy entry that
     * predates storing it, resolves the parent channel once via the bot API and upgrades
     * the store. Resolves to {@code null} only when the parent can't be determined (e.g.
     * a blank/invalid bot token) — in which case reactions couldn't be applied anyway.
     */
    private CompletableFuture<DiscordMessageRef> anchorRef(UUID uuid, DiscordMessageRef stored) {
        if (stored.channelId() != null) {
            return CompletableFuture.completedFuture(stored);
        }
        return DiscordThreadClient.fetchAnchorRef(stored.messageId()).thenApply(resolved -> {
            if (resolved != null) {
                threadStore.put(uuid, resolved); // self-upgrade the legacy entry
            }
            return resolved;
        });
    }

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

    /** Replace {@code {player}} in a template. */
    static String format(String template, String player) {
        return template.replace("{player}", player);
    }

    /** {@code <@123>} / {@code <@!123>} user mentions — scanned ONLY from the trusted join suffix. */
    private static final java.util.regex.Pattern USER_MENTION = java.util.regex.Pattern.compile("<@!?(\\d+)>");

    /** A built join message: the content body plus the user-ids its trusted suffix is allowed to ping. */
    record JoinMessage(String content, List<String> allowedUserIds) {}

    /**
     * Build a join message: fill {@code {player}} in the template, then append the bundling mod's
     * optional join-suffix block (a version line / dev ping-marker) on its own line. The provider
     * is read ONCE here because it may consume one-time state. Any user mentions in that trusted
     * suffix are returned as the ping allow-list; player name / template mentions never notify.
     */
    static JoinMessage joinMessage(String template, UUID uuid, String player) {
        String body = format(template, player);
        String suffix = DiscordCredentials.providerJoinSuffix(uuid, player);
        if (suffix.isBlank()) {
            return new JoinMessage(body, List.of());
        }
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher m = USER_MENTION.matcher(suffix);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return new JoinMessage(body + "\n" + suffix, List.copyOf(ids));
    }

    /** Replace {@code {player}} and {@code {advancement}} in a template. */
    static String formatAdvancement(String template, String player, String advancement) {
        return template.replace("{player}", player).replace("{advancement}", advancement);
    }

    /**
     * Build the advancement icon thumbnail URL by substituting the icon item's
     * registry id into {@code template} ({@code {namespace}} / {@code {path}}
     * placeholders), or null when the template or path is blank. Registry paths are
     * limited to {@code [a-z0-9_.-/]}, so no URL-encoding is needed.
     */
    static String advancementIconUrl(String template, String namespace, String path) {
        if (template == null || template.isBlank() || path == null || path.isBlank()) {
            return null;
        }
        return template
                .replace("{namespace}", namespace == null ? "" : namespace)
                .replace("{path}", path);
    }

    /** Discord embed field values are capped at 1024 chars; trim with an ellipsis if over. */
    private static final int MAX_FIELD_VALUE = 1024;

    /**
     * Build the "actual requirements" field value for an advancement: its sub-goal
     * criteria names (e.g. the biomes for <i>Adventuring Time</i>, the foods for
     * <i>A Balanced Diet</i>) that are <b>not already reflected in {@code description}</b>,
     * prettified, de-duplicated, sorted, and capped to {@code max} with a
     * {@code +N more} suffix. Returns {@code null} when nothing novel remains, so the
     * caller adds no field. Pure (no game/config access) → unit-tested.
     *
     * <p>"Not already in the description" = the criterion's normalized name is not a
     * substring of the normalized description — this is the user's "if different" intent,
     * so a sub-goal the description already names is omitted. Names normalizing to fewer
     * than 3 characters are always kept (short substrings match unreliably).</p>
     */
    static String advancementRequirements(Collection<String> criterionKeys, String description, int max) {
        if (criterionKeys == null || criterionKeys.isEmpty()) {
            return null;
        }
        String descNorm = normalizeForMatch(description);
        List<String> novel = criterionKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(DiscordService::prettifyCriterion)
                .filter(name -> !name.isBlank())
                .filter(name -> {
                    String norm = normalizeForMatch(name);
                    return norm.length() < 3 || !descNorm.contains(norm);
                })
                .distinct()
                .sorted()
                .toList();
        if (novel.isEmpty()) {
            return null;
        }
        int cap = Math.max(1, max);
        String joined = novel.size() <= cap
                ? String.join(", ", novel)
                : String.join(", ", novel.subList(0, cap)) + ", +" + (novel.size() - cap) + " more";
        return joined.length() <= MAX_FIELD_VALUE ? joined : joined.substring(0, MAX_FIELD_VALUE - 1) + "…";
    }

    /**
     * Human-readable display form of a criterion key: strip any namespace/path
     * ({@code minecraft:badlands} → {@code badlands}; last {@code /} segment), turn
     * {@code _}/{@code -} into spaces and Title-Case each word ({@code birch_forest} →
     * {@code Birch Forest}). Returns "" for null/blank.
     */
    static String prettifyCriterion(String key) {
        if (key == null) {
            return "";
        }
        String core = key.trim();
        int colon = core.lastIndexOf(':');
        if (colon >= 0) {
            core = core.substring(colon + 1);
        }
        int slash = core.lastIndexOf('/');
        if (slash >= 0) {
            core = core.substring(slash + 1);
        }
        core = core.replace('_', ' ').replace('-', ' ');
        StringBuilder sb = new StringBuilder(core.length());
        for (String word : core.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Normalize text for substring matching: lower-case, collapse every run of
     * non-alphanumeric characters to a single space, and trim. Used to compare a
     * criterion name against the advancement description.
     */
    static String normalizeForMatch(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
