package games.brennan.discordpresence.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Server-scoped config for Discord Presence, stored at
 * {@code <config>/discordpresence-server.toml}.
 *
 * <p>Holds SECRETS (the webhook URL and bot token), so it is registered as
 * {@code ModConfig.Type.SERVER} — server-side only, never transmitted to
 * clients. A blank webhook URL disables the mod entirely; a blank bot token
 * disables only the reactions/threads (messages still post).</p>
 *
 * <p>SERVER config is loaded only inside an active world/server, so the static
 * getters guard on {@link #isLoaded()} and fall back to defaults otherwise
 * (mirrors Dungeon Train's {@code DungeonTrainConfig}).</p>
 */
public final class DiscordPresenceConfig {

    public static final String DEFAULT_JOIN_TEMPLATE = "🎮 **{player}** started the game";
    public static final String DEFAULT_FIRST_JOIN_TEMPLATE = "🎮 **{player}** joined the game for the first time";
    public static final String DEFAULT_ONLINE_EMOJI = "🟢"; // 🟢
    public static final String DEFAULT_DEATH_EMOJI = "💀";  // 💀
    public static final String DEFAULT_THREAD_NAME_TEMPLATE = "{player}";
    public static final int DEFAULT_THREAD_AUTO_ARCHIVE_MINUTES = 10080; // 1 week
    public static final String DEFAULT_ADVANCEMENT_TEMPLATE = "{player} earned";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> FIRST_JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> ONLINE_EMOJI;
    public static final ModConfigSpec.ConfigValue<String> DEATH_EMOJI;
    public static final ModConfigSpec.BooleanValue CREATE_THREAD_ON_JOIN;
    public static final ModConfigSpec.ConfigValue<String> THREAD_NAME_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<Integer> THREAD_AUTO_ARCHIVE_MINUTES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADVANCEMENT_NAMESPACES;
    public static final ModConfigSpec.BooleanValue ONLY_DISPLAY_ADVANCEMENTS;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_MESSAGE_TEMPLATE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("discord");
        WEBHOOK_URL = b
                .comment("Discord incoming-webhook URL used to POST the join message.",
                         "Leave blank to disable Discord Presence entirely. SECRET — do not share or commit.")
                .define("webhookUrl", "");
        BOT_TOKEN = b
                .comment("Discord bot token used to add/remove reactions, create the per-player thread,",
                         "and post advancement messages. Required for reactions + threads — webhooks cannot do these.",
                         "The bot must be in the webhook's server with: Add Reactions, Read Message History,",
                         "Create Public Threads, and Send Messages in Threads.",
                         "Leave blank to post messages without reactions/threads. SECRET — do not share or commit.")
                .define("botToken", "");
        JOIN_MESSAGE_TEMPLATE = b
                .comment("Message posted when a returning player logs in. When threads are enabled this is",
                         "posted INSIDE the player's thread. '{player}' is replaced with the player's name.")
                .define("joinMessageTemplate", DEFAULT_JOIN_TEMPLATE);
        FIRST_JOIN_MESSAGE_TEMPLATE = b
                .comment("Message posted the FIRST time a player ever joins (threads enabled only). This",
                         "top-level message anchors the player's permanent thread. '{player}' is replaced",
                         "with the player's name.")
                .define("firstJoinMessageTemplate", DEFAULT_FIRST_JOIN_TEMPLATE);
        ONLINE_EMOJI = b
                .comment("Emoji reaction added while a player is online and removed when they log out.",
                         "A standard unicode emoji (e.g. 🟢). Leave blank to skip the online reaction.")
                .define("onlineEmoji", DEFAULT_ONLINE_EMOJI);
        DEATH_EMOJI = b
                .comment("Emoji reaction added to the join message when the player dies.",
                         "A standard unicode emoji (e.g. 💀). Leave blank to skip the death reaction.")
                .define("deathEmoji", DEFAULT_DEATH_EMOJI);
        CREATE_THREAD_ON_JOIN = b
                .comment("Create one persistent Discord thread per player (anchored to their first-join",
                         "message) and route later joins, deaths and advancements into it. Requires the bot",
                         "token + Create Public Threads / Send Messages in Threads permissions.",
                         "Set false to fall back to a plain top-level join message per session (no threads).")
                .define("createThreadOnJoin", true);
        THREAD_NAME_TEMPLATE = b
                .comment("Name of the per-player thread. '{player}' is replaced with the player's name.",
                         "Discord caps thread names at 100 characters.")
                .define("threadNameTemplate", DEFAULT_THREAD_NAME_TEMPLATE);
        THREAD_AUTO_ARCHIVE_MINUTES = b
                .comment("Minutes of inactivity after which Discord auto-archives the thread.",
                         "Only Discord's allowed values are accepted: 60 (1h), 1440 (1d), 4320 (3d), 10080 (1w).")
                // NOTE: acceptable values MUST be a null-tolerant list. ModConfigSpec
                // tests the (absent → null) current value against this during config
                // correction; an immutable List.of(...) throws NPE on contains(null),
                // crashing world load. Arrays.asList(...).contains(null) returns false.
                .defineInList("threadAutoArchiveMinutes", DEFAULT_THREAD_AUTO_ARCHIVE_MINUTES,
                        Arrays.asList(60, 1440, 4320, 10080));
        ADVANCEMENT_NAMESPACES = b
                .comment("Advancement namespaces to announce in the thread. Empty = announce ALL namespaces.",
                         "E.g. [\"dungeontrain\"] to only announce a specific mod's advancements.")
                .defineListAllowEmpty("advancementNamespaces", () -> List.<String>of(), () -> "",
                        o -> o instanceof String);
        ONLY_DISPLAY_ADVANCEMENTS = b
                .comment("Only announce advancements that have display info (a title/icon). This skips the",
                         "noisy hidden 'recipe' advancements. Leave true unless you really want every one.")
                .define("onlyDisplayAdvancements", true);
        ADVANCEMENT_MESSAGE_TEMPLATE = b
                .comment("Attribution line posted above the advancement embed in the thread. The advancement's",
                         "title + full description render inside a coloured embed (matching the in-game frame",
                         "colour: green for task/goal, purple for challenge), so this line is normally just the",
                         "attribution. '{player}' = player name, '{advancement}' = the advancement's display title.")
                .define("advancementMessageTemplate", DEFAULT_ADVANCEMENT_TEMPLATE);
        b.pop();
        SPEC = b.build();
    }

    private DiscordPresenceConfig() {}

    /** SERVER config is only loaded inside an active world/server. */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static String getWebhookUrl() {
        return isLoaded() ? WEBHOOK_URL.get() : "";
    }

    public static String getBotToken() {
        return isLoaded() ? BOT_TOKEN.get() : "";
    }

    public static String getJoinMessageTemplate() {
        return isLoaded() ? JOIN_MESSAGE_TEMPLATE.get() : DEFAULT_JOIN_TEMPLATE;
    }

    public static String getFirstJoinMessageTemplate() {
        return isLoaded() ? FIRST_JOIN_MESSAGE_TEMPLATE.get() : DEFAULT_FIRST_JOIN_TEMPLATE;
    }

    public static String getOnlineEmoji() {
        return isLoaded() ? ONLINE_EMOJI.get() : DEFAULT_ONLINE_EMOJI;
    }

    public static String getDeathEmoji() {
        return isLoaded() ? DEATH_EMOJI.get() : DEFAULT_DEATH_EMOJI;
    }

    public static boolean isCreateThreadOnJoin() {
        return isLoaded() ? CREATE_THREAD_ON_JOIN.get() : true;
    }

    public static String getThreadNameTemplate() {
        return isLoaded() ? THREAD_NAME_TEMPLATE.get() : DEFAULT_THREAD_NAME_TEMPLATE;
    }

    public static int getThreadAutoArchiveMinutes() {
        return isLoaded() ? THREAD_AUTO_ARCHIVE_MINUTES.get() : DEFAULT_THREAD_AUTO_ARCHIVE_MINUTES;
    }

    public static List<? extends String> getAdvancementNamespaces() {
        return isLoaded() ? ADVANCEMENT_NAMESPACES.get() : List.of();
    }

    public static boolean isOnlyDisplayAdvancements() {
        return isLoaded() ? ONLY_DISPLAY_ADVANCEMENTS.get() : true;
    }

    public static String getAdvancementMessageTemplate() {
        return isLoaded() ? ADVANCEMENT_MESSAGE_TEMPLATE.get() : DEFAULT_ADVANCEMENT_TEMPLATE;
    }
}
