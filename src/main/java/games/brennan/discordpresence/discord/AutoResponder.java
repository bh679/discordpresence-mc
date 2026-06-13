package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-game "whispers into the darkness" auto-responses.
 *
 * <p>When a player chats while <b>armed</b> — no Discord reply has been relayed for
 * them recently — this broadcasts an in-game flavour line. It posts <b>nothing</b>
 * to Discord; the chat message itself still relays via
 * {@link DiscordService#onGameChat}. A relayed Discord reply disarms the player
 * ({@link #onDiscordActivity}); they re-arm after {@code rearmMinutes} of Discord
 * silence. The disarm timestamp persists in {@link AutoResponseStore} (config dir),
 * so for local games it carries across worlds.</p>
 *
 * <p>Two modes pick different message sets + cooldowns: <i>alone</i> (the player is
 * the only one online) vs <i>group</i> (others online). The flavour line is a system
 * message, so it never re-fires {@code ServerChatEvent} (no relay loop).</p>
 *
 * <p>{@link #onPlayerChat} runs on the server thread; {@link #onDiscordActivity} runs
 * off-thread on the gateway — the store and cooldown map are both thread-safe.</p>
 */
final class AutoResponder {

    private final AutoResponseStore store = new AutoResponseStore();

    /** Per-player epoch-millis of the last whisper shown — the cooldown gate (transient). */
    private final ConcurrentHashMap<UUID, Long> lastWhisper = new ConcurrentHashMap<>();

    /** Load the persisted disarm timestamps on server start (before any join). */
    void loadState(Path file) {
        store.load(file);
    }

    /** Discord→game: a relayed Discord reply disarms the player; they re-arm after the configured silence. */
    void onDiscordActivity(UUID uuid) {
        if (uuid == null) {
            return;
        }
        store.put(uuid, System.currentTimeMillis());
    }

    /** Drop transient per-player cooldowns on server stop (the disarm store stays on disk). */
    void clear() {
        lastWhisper.clear();
    }

    /**
     * On an in-game chat line, broadcast a flavour auto-response when the player is
     * armed and off cooldown. Server-thread only (called from
     * {@link DiscordService#onGameChat}).
     */
    void onPlayerChat(ServerPlayer player) {
        if (!DiscordPresenceConfig.isAutoResponseEnabled()) {
            return;
        }
        MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        if (!isArmed(store.get(uuid), now, DiscordPresenceConfig.getAutoResponseRearmMinutes())) {
            return; // an active Discord conversation — stay quiet
        }

        boolean alone = isAlone(server.getPlayerList().getPlayers().size());
        List<? extends String> messages = alone
                ? DiscordPresenceConfig.getAutoResponseAloneMessages()
                : DiscordPresenceConfig.getAutoResponseGroupMessages();
        if (messages.isEmpty()) {
            return; // this mode disabled
        }
        int cooldownSeconds = alone
                ? DiscordPresenceConfig.getAutoResponseAloneCooldownSeconds()
                : DiscordPresenceConfig.getAutoResponseGroupCooldownSeconds();
        if (!cooldownElapsed(lastWhisper.get(uuid), now, cooldownSeconds)) {
            return;
        }

        String name = player.getGameProfile().getName();
        String line = pickAndFormat(messages, name, ThreadLocalRandom.current().nextInt(messages.size()));
        if (line == null || line.isBlank()) {
            return;
        }
        lastWhisper.put(uuid, now);
        // System message — does NOT re-fire ServerChatEvent, so no relay loop.
        server.getPlayerList().broadcastSystemMessage(Component.literal(line), false);
    }

    // --- pure helpers (unit-tested) ---------------------------------------

    /** Armed when there's no recorded Discord activity, or it was at least {@code rearmMinutes} ago. */
    static boolean isArmed(Long lastActivityMillis, long now, int rearmMinutes) {
        if (lastActivityMillis == null) {
            return true;
        }
        return now - lastActivityMillis >= (long) rearmMinutes * 60_000L;
    }

    /** Alone when the player is the only one online. */
    static boolean isAlone(int onlinePlayerCount) {
        return onlinePlayerCount <= 1;
    }

    /** Cooldown elapsed when there's no prior whisper, or it was at least {@code cooldownSeconds} ago. */
    static boolean cooldownElapsed(Long lastWhisperMillis, long now, int cooldownSeconds) {
        if (lastWhisperMillis == null) {
            return true;
        }
        return now - lastWhisperMillis >= (long) cooldownSeconds * 1000L;
    }

    /** Pick a message by {@code roll} (mod size) and substitute {@code {player}}; null when empty. */
    static String pickAndFormat(List<? extends String> messages, String player, int roll) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String template = messages.get(Math.floorMod(roll, messages.size()));
        return template == null ? null : template.replace("{player}", player);
    }
}
