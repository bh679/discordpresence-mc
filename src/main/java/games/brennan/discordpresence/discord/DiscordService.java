package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates Discord Presence: posts the join message, tracks it per player,
 * and manages the online/death reactions. This is the single entry point the
 * event subscriber delegates to — and the seam the future two-way chat inbound
 * path (Discord reply → in-game) will extend, since it already owns the
 * per-player ↔ message mapping.
 *
 * <p>State maps each online player's UUID to a
 * {@code CompletableFuture<DiscordMessageRef>} — the <i>future</i>, not the
 * resolved ref. A logout or death that fires before the webhook POST completes
 * still chains correctly onto the eventual message, so the online reaction can
 * never leak. Continuations run on the HTTP executor; the server thread only
 * ever enqueues non-blocking work.</p>
 */
public final class DiscordService {

    private static final DiscordService INSTANCE = new DiscordService();

    public static DiscordService get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<UUID, CompletableFuture<DiscordMessageRef>> active =
            new ConcurrentHashMap<>();

    private DiscordService() {}

    /** Off entirely when no webhook URL is configured. */
    private boolean enabled() {
        return !DiscordPresenceConfig.getWebhookUrl().isBlank();
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!enabled()) {
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

    /** Drop all tracked messages on server stop (the daemon HTTP pool needs no teardown). */
    public void clearAll() {
        active.clear();
    }
}
