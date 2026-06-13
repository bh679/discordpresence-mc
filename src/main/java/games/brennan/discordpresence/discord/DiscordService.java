package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
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

/**
 * Orchestrates Discord Presence: posts the join message, maintains one
 * persistent thread per player, manages the online/death reactions, and
 * announces advancements in the thread. The single entry point the event
 * subscriber delegates to — and the seam the future two-way chat inbound path
 * (Discord reply → in-game) will extend, since it already owns the
 * per-player ↔ message/thread mapping.
 *
 * <p>Two per-player maps hold <i>futures</i>, not resolved values, so events
 * that fire before a webhook POST / thread creation completes still chain
 * correctly:</p>
 * <ul>
 *   <li>{@code sessionMessages} — this session's join message (the first-join
 *       anchor, or the in-thread "started" message). Carries the online/death
 *       reactions; cleared on logout.</li>
 *   <li>{@code threadFutures} — the player's thread id, so an advancement earned
 *       while the first-join thread is still being created still lands once it
 *       resolves. Backed by the durable {@link DiscordThreadStore}.</li>
 * </ul>
 *
 * <p>Continuations run on the HTTP executor; the server thread only ever
 * enqueues non-blocking work.</p>
 */
public final class DiscordService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String THREAD_STORE_FILE = "discordpresence-threads.json";

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final DiscordThreadStore threadStore = new DiscordThreadStore();
    private final ConcurrentHashMap<UUID, CompletableFuture<DiscordMessageRef>> sessionMessages =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> threadFutures =
            new ConcurrentHashMap<>();

    private DiscordService() {}

    /** Off entirely when no webhook URL is configured. */
    private boolean enabled() {
        return !DiscordPresenceConfig.getWebhookUrl().isBlank();
    }

    /** Load the persisted player→thread map on server start (before any join). */
    public void loadThreads() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(THREAD_STORE_FILE);
        threadStore.load(file);
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!enabled()) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        String onlineEmoji = DiscordPresenceConfig.getOnlineEmoji();

        if (!DiscordPresenceConfig.isCreateThreadOnJoin()) {
            // Threads disabled: plain per-session top-level message + reactions (v0.1.0).
            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            sessionMessages.put(uuid, postAndReact(content, name, uuid, null, onlineEmoji));
            return;
        }

        String existingThread = threadStore.get(uuid);
        if (existingThread != null) {
            // Returning player: post "started the game" INTO their thread.
            String content = format(DiscordPresenceConfig.getJoinMessageTemplate(), name);
            sessionMessages.put(uuid, postAndReact(content, name, uuid, existingThread, onlineEmoji));
            threadFutures.put(uuid, CompletableFuture.completedFuture(existingThread));
            return;
        }

        // First join ever: post the top-level anchor, react on it, and create the
        // player's thread from it (persisting the id once it resolves).
        String content = format(DiscordPresenceConfig.getFirstJoinMessageTemplate(), name);
        CompletableFuture<DiscordMessageRef> anchor =
                DiscordWebhookClient.post(content, name, uuid, null);

        sessionMessages.put(uuid, anchor.thenApply(ref -> {
            if (ref != null) {
                DiscordBotClient.addReaction(ref, onlineEmoji);
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
            String existing = threadStore.get(uuid);
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

    /** Drop per-session tracking on server stop (the durable store stays on disk). */
    public void clearAll() {
        sessionMessages.clear();
        threadFutures.clear();
    }

    // --- pure helpers (unit-tested) ---------------------------------------

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
