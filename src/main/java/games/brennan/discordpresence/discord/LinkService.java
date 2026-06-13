package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.DiscordLinkClient.ChannelMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates account verification: mints a one-time code per player, polls the
 * Discord link channel for it, and persists the resulting {@code UUID ↔ Discord
 * id} link. The first concrete use of the inbound seam the architecture
 * anticipated (reading from Discord, not just posting) — built on plain bot REST
 * polling, no gateway yet.
 *
 * <p>The poller is lazy: {@link #requestLink} starts it, and a tick stops it once
 * no codes are pending, so an idle server makes no link API calls. All HTTP runs
 * off-thread on {@link DiscordHttp}; the only server-thread work is delivering the
 * in-game confirmation via {@link MinecraftServer#execute}.</p>
 */
public final class LinkService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LINK_STORE_FILE = "discordpresence-links.json";
    private static final int FETCH_LIMIT = 50;
    private static final int SNOWFLAKE_MIN = 17;
    private static final int SNOWFLAKE_MAX = 20;

    private static final LinkService INSTANCE = new LinkService();

    public static LinkService get() {
        return INSTANCE;
    }

    private final LinkCodes codes = new LinkCodes();
    private final DiscordLinkStore store = new DiscordLinkStore();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean warnedEmptyContent = new AtomicBoolean(false);

    private volatile MinecraftServer server;
    private ScheduledFuture<?> pollTask; // guarded by this

    private LinkService() {}

    /** Linking needs both the bot token and a channel to watch. */
    private boolean enabled() {
        return !DiscordPresenceConfig.getBotToken().isBlank()
                && !DiscordPresenceConfig.getLinkChannelId().isBlank();
    }

    /** Capture the server + load the persisted links on server start (before any join). */
    public void onServerStarted(MinecraftServer server) {
        this.server = server;
        Path file = FMLPaths.CONFIGDIR.get().resolve(LINK_STORE_FILE);
        store.load(file);
    }

    /** Stop polling + drop per-session state on server stop (the durable store stays on disk). */
    public synchronized void clearAll() {
        stopPolling();
        codes.clear();
        inFlight.set(false);
        server = null;
    }

    /**
     * Mint a link code for {@code player} and make sure the poller is running.
     *
     * @return the code to post in the link channel, or {@code null} if linking is
     *         disabled (no bot token / link channel configured).
     */
    public String requestLink(ServerPlayer player) {
        if (!enabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1, DiscordPresenceConfig.getLinkCodeTtlMinutes()) * 60_000L;
        String code = codes.issue(player.getUUID(), now, ttlMs);
        startPolling();
        return code;
    }

    /** The player's linked Discord id, or {@code null} if unlinked. */
    public String status(UUID uuid) {
        return store.getDiscordId(uuid);
    }

    /** Remove the player's link. Returns whether one existed. */
    public boolean unlink(UUID uuid) {
        return store.remove(uuid);
    }

    /** Whether linking is configured — lets the command explain a disabled state. */
    public boolean isEnabled() {
        return enabled();
    }

    // --- polling -----------------------------------------------------------

    private synchronized void startPolling() {
        if (pollTask != null && !pollTask.isCancelled()) {
            return;
        }
        int period = Math.max(2, DiscordPresenceConfig.getLinkPollSeconds());
        pollTask = DiscordHttp.SCHEDULER.scheduleWithFixedDelay(
                this::pollTickSafe, period, period, TimeUnit.SECONDS);
    }

    private synchronized void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /** Never let a thrown exception kill the scheduled task. */
    private void pollTickSafe() {
        try {
            pollTick();
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: link poll tick failed", e);
        }
    }

    private void pollTick() {
        long now = System.currentTimeMillis();
        codes.pruneExpired(now);
        if (!codes.hasPending() || !enabled()) {
            stopPolling();
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return; // a previous fetch is still outstanding
        }
        String channelId = DiscordPresenceConfig.getLinkChannelId();
        DiscordLinkClient.fetchMessages(channelId, FETCH_LIMIT).whenComplete((msgs, t) -> {
            inFlight.set(false);
            if (msgs != null && !msgs.isEmpty()) {
                processMessages(channelId, msgs);
            }
        });
    }

    private void processMessages(String channelId, List<ChannelMessage> msgs) {
        if (DiscordLinkClient.allContentBlank(msgs) && warnedEmptyContent.compareAndSet(false, true)) {
            LOGGER.warn("Discord Presence: link-channel messages have empty content — enable the "
                    + "MESSAGE CONTENT INTENT for the bot in the Discord Developer Portal, or codes can never match.");
        }
        long now = System.currentTimeMillis();
        // Oldest-first so the original poster wins a same-window code race.
        List<ChannelMessage> ordered = new ArrayList<>(msgs);
        Collections.reverse(ordered);
        for (ChannelMessage m : ordered) {
            if (m.authorBot()) {
                continue;
            }
            UUID uuid = codes.consume(m.content(), now);
            if (uuid != null) {
                link(channelId, uuid, m);
            }
        }
    }

    private void link(String channelId, UUID uuid, ChannelMessage m) {
        if (!isSnowflake(m.authorId())) {
            LOGGER.warn("Discord Presence: ignoring link with non-numeric author id '{}'.", m.authorId());
            return;
        }
        store.put(uuid, m.authorId());
        LOGGER.info("Discord Presence: linked player {} to Discord user {}.", uuid, m.authorId());
        if (DiscordPresenceConfig.isDeleteCodeMessage()) {
            DiscordLinkClient.deleteMessage(channelId, m.id());
        }
        confirm(channelId, uuid, m.authorId());
    }

    /** In-game feedback (if the player is online) plus a non-pinging channel note. */
    private void confirm(String channelId, UUID uuid, String discordId) {
        MinecraftServer srv = this.server;
        if (srv != null) {
            srv.execute(() -> {
                ServerPlayer p = srv.getPlayerList().getPlayer(uuid);
                if (p != null) {
                    p.sendSystemMessage(Component.literal("✅ Your Minecraft account is now linked to Discord.")
                            .withStyle(ChatFormatting.GREEN));
                }
            });
        }
        DiscordLinkClient.postPlainMessage(channelId, "✅ Linked a Minecraft account to <@" + discordId + ">.");
    }

    /** A Discord snowflake is a 17–20 digit number. */
    static boolean isSnowflake(String s) {
        if (s == null || s.length() < SNOWFLAKE_MIN || s.length() > SNOWFLAKE_MAX) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
