package games.brennan.discordpresence.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final DiscordThreadStore threadStore = new DiscordThreadStore();
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
            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            sessionMessages.put(uuid, trackForReplies(postAndReact(content, name, uuid, null, onlineEmoji), uuid));
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

            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            trackForReplies(DiscordWebhookClient.post(content, name, uuid, threadId), uuid);

            sessionMessages.put(uuid, anchorRef(uuid, stored).thenApply(anchor -> {
                if (anchor != null) {
                    DiscordBotClient.addReaction(anchor, onlineEmoji);
                }
                return anchor;
            }));
            return;
        }

        // First join ever: post the top-level anchor, react on it, index it (its id == the thread
        // id), and create the player's thread from it (persisting the anchor ref once it resolves).
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
        String threadId = threadStore.threadId(uuid); // into the player's thread if they have one (null → top-level)
        DiscordWebhookClient.postChat(name, uuid, text, threadId).thenAccept(ref -> {
            if (ref != null) {
                reverse.put(ref.messageId(), uuid);
            }
        });
        // In-game flavour feedback when the player is "whispering into the darkness".
        autoResponder.onPlayerChat(player);
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

        threadFuture.thenAccept(threadId -> {
            if (threadId != null) {
                DiscordThreadClient.postEmbed(threadId, content, title, description, color, iconUrl);
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

    /** Drop per-session tracking + close the gateway on server stop (the durable store stays on disk). */
    public void clearAll() {
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

    /** Post {@code content} as the player (optionally into a thread) and add the online reaction. */
    private static CompletableFuture<DiscordMessageRef> postAndReact(
            String content, String name, UUID uuid, String threadId, String onlineEmoji) {
        return DiscordWebhookClient.post(content, name, uuid, threadId)
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
}
