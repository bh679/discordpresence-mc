package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

    /** Hard floor: never more than one auto-response per 30 seconds, whatever the config says. */
    private static final int MIN_COOLDOWN_SECONDS = 30;

    // Translation-key pools for the localized path (localizeAutoResponse = true). Each base has
    // discordpresence.autoresponse.<base>.0 .. .<count-1> whole-sentence keys taking a single
    // '%s' player placeholder. COUNTS MUST MATCH the number of keys in en_us.json — the
    // AutoResponderTest count-guard parses the lang file to keep them in lock-step.
    private static final String ALONE_KEY_BASE = "discordpresence.autoresponse.alone";
    private static final String GROUP_KEY_BASE = "discordpresence.autoresponse.group";
    private static final String MENTION_HINT_KEY_BASE = "discordpresence.autoresponse.mention_hint";
    /** The per-locale developer @-mention tag (e.g. {@code @dev} / {@code @开发者}); fed to the hint as its 2nd arg. */
    private static final String DEV_TAG_KEY = "discordpresence.chattag.dev";
    static final int ALONE_KEY_COUNT = 12;
    static final int GROUP_KEY_COUNT = 3;
    static final int MENTION_HINT_KEY_COUNT = 12;

    /** Delay before the whisper shows, so it lands AFTER the player's own chat line. */
    private static final long WHISPER_DELAY_MILLIS = 300L;

    /** Delay before the "tag the dev" hint shows, so it lands AFTER the whisper. */
    private static final long MENTION_HINT_DELAY_MILLIS = 1500L;

    private final AutoResponseStore store = new AutoResponseStore();

    /** Per-player epoch-millis of the last whisper shown — the cooldown gate (transient). */
    private final ConcurrentHashMap<UUID, Long> lastWhisper = new ConcurrentHashMap<>();

    /** Single daemon thread that delays the whisper so it lands after the player's chat line. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-AutoResponse");
        t.setDaemon(true);
        return t;
    });

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

    /**
     * Whether Discord is in an active conversation with this player: a relayed Discord message landed
     * within the same window that disarms whispers ({@code rearmMinutes}). Reused by the engaged-only
     * chat-relay gate, so the moment Discord engages a player both the whisper stops and their chat
     * begins relaying. Independent of {@code autoResponse.enabled} — the disarm store is always kept.
     */
    boolean hasActiveDiscordConversation(UUID uuid) {
        return !isArmed(store.get(uuid), System.currentTimeMillis(),
                DiscordPresenceConfig.getAutoResponseRearmMinutes());
    }

    /** Drop transient per-player cooldowns on server stop (the disarm store stays on disk). */
    void clear() {
        lastWhisper.clear();
    }

    /**
     * On an in-game chat line, broadcast a flavour auto-response when the player is
     * armed and off cooldown. When {@code suggestMention} is set — the engaged-only
     * relay gate would otherwise swallow this line — a second "tag the dev" hint
     * follows the whisper. Server-thread only (called from
     * {@link DiscordService#onGameChat}).
     */
    void onPlayerChat(ServerPlayer player, boolean suggestMention) {
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
        int cooldownSeconds = effectiveCooldownSeconds(alone
                ? DiscordPresenceConfig.getAutoResponseAloneCooldownSeconds()
                : DiscordPresenceConfig.getAutoResponseGroupCooldownSeconds());
        if (!cooldownElapsed(lastWhisper.get(uuid), now, cooldownSeconds)) {
            return;
        }

        String name = player.getGameProfile().getName();
        boolean localize = DiscordPresenceConfig.isLocalizeAutoResponse();
        // Alone: "{player} {verb} into the {place}, {phrase}"-style whisper.
        // Group (others online): a "mutters to themselves" flavour line.
        // Localized path emits a translatable key (rendered per-client locale); the literal path
        // composes the operator-authored config lists into fixed text.
        Component whisper = alone
                ? aloneWhisper(name, localize)
                : groupWhisper(name, localize);
        if (whisper == null) {
            return; // this mode's message pool is empty
        }
        lastWhisper.put(uuid, now);
        // Grey, like a system message. Delayed so it lands AFTER the player's own chat line — the modern
        // chat broadcast is asynchronous, so an immediate server-thread enqueue still raced ahead of it.
        // A system message never re-fires ServerChatEvent, so there is no relay loop.
        scheduleGrayBroadcast(server, whisper, WHISPER_DELAY_MILLIS);

        // Second line: nudge the player to tag the dev so they can actually be heard.
        if (suggestMention) {
            Component hint = mentionHint(name, localize);
            if (hint != null) {
                scheduleGrayBroadcast(server, hint, MENTION_HINT_DELAY_MILLIS);
            }
        }
    }

    /** The alone whisper as a grey-ready component: a translatable key (localized) or composed literal. */
    private static Component aloneWhisper(String name, boolean localize) {
        if (localize) {
            String key = translationKey(ALONE_KEY_BASE, ThreadLocalRandom.current().nextInt(ALONE_KEY_COUNT),
                    ALONE_KEY_COUNT);
            return key == null ? null : Component.translatable(key, name);
        }
        String line = composeAloneWhisper(name);
        return (line == null || line.isBlank()) ? null : Component.literal(line);
    }

    /** The group (others-online) whisper as a grey-ready component. */
    private static Component groupWhisper(String name, boolean localize) {
        if (localize) {
            String key = translationKey(GROUP_KEY_BASE, ThreadLocalRandom.current().nextInt(GROUP_KEY_COUNT),
                    GROUP_KEY_COUNT);
            return key == null ? null : Component.translatable(key, name);
        }
        String line = pickGroupMessage(name);
        return (line == null || line.isBlank()) ? null : Component.literal(line);
    }

    /** The "tag the dev" hint as a grey-ready component, or null when the pool is empty. */
    private static Component mentionHint(String name, boolean localize) {
        if (localize) {
            String key = translationKey(MENTION_HINT_KEY_BASE,
                    ThreadLocalRandom.current().nextInt(MENTION_HINT_KEY_COUNT), MENTION_HINT_KEY_COUNT);
            // 2nd arg is the per-locale dev tag so the hint names the token the player actually types
            // on their client (@dev / @开发者), not a hardcoded one.
            return key == null ? null
                    : Component.translatable(key, name, Component.translatable(DEV_TAG_KEY));
        }
        String line = pickMentionHint(name);
        return (line == null || line.isBlank()) ? null : Component.literal(line);
    }

    /** Schedule a grey system broadcast after {@code delayMillis}, hopped onto the server thread; never throws. */
    private void scheduleGrayBroadcast(MinecraftServer server, Component line, long delayMillis) {
        Component message = line.copy().withStyle(ChatFormatting.GRAY);
        scheduler.schedule(() -> {
            try {
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
            } catch (Exception ignored) {
                // server stopped during the delay, etc. — best-effort flavour, never throw.
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /** Assemble the alone whisper from the configured template + a random verb/place/phrase. */
    private static String composeAloneWhisper(String player) {
        List<? extends String> verbs = DiscordPresenceConfig.getAutoResponseVerbs();
        List<? extends String> places = DiscordPresenceConfig.getAutoResponsePlaces();
        List<? extends String> phrases = DiscordPresenceConfig.getAutoResponsePhrases();
        if (verbs.isEmpty() || places.isEmpty() || phrases.isEmpty()) {
            return null; // a slot pool is empty — nothing to say
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String verb = verbs.get(rng.nextInt(verbs.size()));
        String place = places.get(rng.nextInt(places.size()));
        String phrase = phrases.get(rng.nextInt(phrases.size()));
        return compose(DiscordPresenceConfig.getAutoResponseAloneTemplate(), player, verb, place, phrase);
    }

    /** Pick a random line from the group (others-online) message list. */
    private static String pickGroupMessage(String player) {
        List<? extends String> msgs = DiscordPresenceConfig.getAutoResponseGroupMessages();
        if (msgs.isEmpty()) {
            return null;
        }
        return pickAndFormat(msgs, player, ThreadLocalRandom.current().nextInt(msgs.size()));
    }

    /** Pick a random "tag the dev" hint line, or null when the pool is empty. */
    private static String pickMentionHint(String player) {
        List<? extends String> hints = DiscordPresenceConfig.getAutoResponseMentionHintMessages();
        if (hints.isEmpty()) {
            return null;
        }
        return pickAndFormat(hints, player, ThreadLocalRandom.current().nextInt(hints.size()));
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

    /** The configured cooldown, floored at {@link #MIN_COOLDOWN_SECONDS} (one auto-response per 30s max). */
    static int effectiveCooldownSeconds(int configuredSeconds) {
        return Math.max(MIN_COOLDOWN_SECONDS, configuredSeconds);
    }

    /** Translation key {@code base.<roll mod count>} for the localized pools; null when {@code count <= 0}. */
    static String translationKey(String base, int roll, int count) {
        if (count <= 0) {
            return null;
        }
        return base + "." + Math.floorMod(roll, count);
    }

    /** Pick a message by {@code roll} (mod size) and substitute {@code {player}}; null when empty. */
    static String pickAndFormat(List<? extends String> messages, String player, int roll) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        String template = messages.get(Math.floorMod(roll, messages.size()));
        return template == null ? null : template.replace("{player}", player);
    }

    /** Fill {@code {player}/{verb}/{place}/{phrase}} in an alone-whisper template (nulls → empty). */
    static String compose(String template, String player, String verb, String place, String phrase) {
        if (template == null) {
            return null;
        }
        return template
                .replace("{player}", player == null ? "" : player)
                .replace("{verb}", verb == null ? "" : verb)
                .replace("{place}", place == null ? "" : place)
                .replace("{phrase}", phrase == null ? "" : phrase);
    }
}
