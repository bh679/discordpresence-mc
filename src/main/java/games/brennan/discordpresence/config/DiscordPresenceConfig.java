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
    public static final boolean DEFAULT_SHOW_ADVANCEMENT_ICON = true;
    public static final String DEFAULT_ADVANCEMENT_ICON_URL_TEMPLATE =
            "https://static.minecraftitemids.com/64/{path}.png";

    public static final boolean DEFAULT_AUTO_DEATH_REPORT = true;
    public static final int DEFAULT_DEATH_REPORT_EMBED_COLOR = 0xFF5555; // death-red
    public static final boolean DEFAULT_SHOW_DEATH_REPORT_IMAGE = true;
    public static final String DEFAULT_DEATH_REPORT_ICON_URL_TEMPLATE =
            "https://static.minecraftitemids.com/64/{path}.png";

    public static final boolean DEFAULT_RELAY_DISCORD_TO_GAME = true;
    public static final boolean DEFAULT_RELAY_GAME_TO_DISCORD = true;
    public static final String DEFAULT_DISCORD_TO_GAME_FORMAT = "<{user}> {msg}";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> FIRST_JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> ONLINE_EMOJI;
    public static final ModConfigSpec.ConfigValue<String> DEATH_EMOJI;
    public static final ModConfigSpec.BooleanValue RELAY_DISCORD_TO_GAME;
    public static final ModConfigSpec.BooleanValue RELAY_GAME_TO_DISCORD;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TO_GAME_FORMAT;
    public static final ModConfigSpec.BooleanValue CREATE_THREAD_ON_JOIN;
    public static final ModConfigSpec.ConfigValue<String> THREAD_NAME_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<Integer> THREAD_AUTO_ARCHIVE_MINUTES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADVANCEMENT_NAMESPACES;
    public static final ModConfigSpec.BooleanValue ONLY_DISPLAY_ADVANCEMENTS;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.BooleanValue SHOW_ADVANCEMENT_ICON;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_ICON_URL_TEMPLATE;
    public static final ModConfigSpec.BooleanValue AUTO_DEATH_REPORT;
    public static final ModConfigSpec.IntValue DEATH_REPORT_EMBED_COLOR;
    public static final ModConfigSpec.BooleanValue SHOW_DEATH_REPORT_IMAGE;
    public static final ModConfigSpec.ConfigValue<String> DEATH_REPORT_ICON_URL_TEMPLATE;

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
        RELAY_DISCORD_TO_GAME = b
                .comment("Relay messages from Discord into in-game chat. Requires the bot token AND the",
                         "Message Content privileged intent enabled in the Discord Developer Portal.",
                         "Only messages that REPLY to — or are posted in a player's THREAD (or a thread started",
                         "from a message this mod posted for them, e.g. a relayed chat line) are relayed.",
                         "On a dedicated server this is on by default; in singleplayer it also needs the",
                         "one-time in-game network confirmation.")
                .define("relayDiscordToGame", DEFAULT_RELAY_DISCORD_TO_GAME);
        RELAY_GAME_TO_DISCORD = b
                .comment("Relay in-game chat to Discord through the webhook, posted under each player's name",
                         "(into their thread when they have one).")
                .define("relayGameToDiscord", DEFAULT_RELAY_GAME_TO_DISCORD);
        DISCORD_TO_GAME_FORMAT = b
                .comment("Format for a relayed Discord message shown in-game.",
                         "'{user}' = the Discord author's name, '{msg}' = their message text.")
                .define("discordToGameFormat", DEFAULT_DISCORD_TO_GAME_FORMAT);
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
        SHOW_ADVANCEMENT_ICON = b
                .comment("Show the advancement's Minecraft icon as a thumbnail on the embed (top-right).",
                         "The icon is the item/block the game displays for the advancement.")
                .define("showAdvancementIcon", DEFAULT_SHOW_ADVANCEMENT_ICON);
        ADVANCEMENT_ICON_URL_TEMPLATE = b
                .comment("URL template for the advancement icon thumbnail. '{path}' = the icon item's id",
                         "(e.g. 'stone'), '{namespace}' = its mod id (e.g. 'minecraft'). Discord fetches this",
                         "URL; if it 404s the embed simply shows no icon. The default serves rendered VANILLA",
                         "item/block icons only — for modded advancements, point this at your own render host,",
                         "e.g. 'https://my-host/icons/{namespace}/{path}.png'.")
                .define("advancementIconUrlTemplate", DEFAULT_ADVANCEMENT_ICON_URL_TEMPLATE);
        AUTO_DEATH_REPORT = b
                .comment("Post a rich 'death report' embed when a player dies: the death cause, basic stats",
                         "(score, location, dimension, XP) and a composed image of the items they were holding",
                         "and wearing. This is IN ADDITION to the death emoji reaction. When a bundling mod",
                         "(e.g. Dungeon Train) drives its own death report via the public API, set this false",
                         "to avoid a duplicate post.")
                .define("autoDeathReport", DEFAULT_AUTO_DEATH_REPORT);
        DEATH_REPORT_EMBED_COLOR = b
                .comment("Embed colour for the death report, as a decimal 0xRRGGBB value (default 0xFF5555, a death-red).")
                .defineInRange("deathReportEmbedColor", DEFAULT_DEATH_REPORT_EMBED_COLOR, 0x000000, 0xFFFFFF);
        SHOW_DEATH_REPORT_IMAGE = b
                .comment("Compose the death report's items (weapon/armor/held) into a single image and attach it.",
                         "Disable to post only the text fields (no image, no icon fetching).")
                .define("showDeathReportImage", DEFAULT_SHOW_DEATH_REPORT_IMAGE);
        DEATH_REPORT_ICON_URL_TEMPLATE = b
                .comment("URL template for each item icon in the death report image. '{path}' = the item id",
                         "(e.g. 'diamond_sword'), '{namespace}' = its mod id. Discord-independent: these are",
                         "fetched server-side and composited. The default serves rendered VANILLA item icons",
                         "only — point it at your own render host for modded items.")
                .define("deathReportIconUrlTemplate", DEFAULT_DEATH_REPORT_ICON_URL_TEMPLATE);
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

    public static boolean isRelayDiscordToGame() {
        return isLoaded() ? RELAY_DISCORD_TO_GAME.get() : DEFAULT_RELAY_DISCORD_TO_GAME;
    }

    public static boolean isRelayGameToDiscord() {
        return isLoaded() ? RELAY_GAME_TO_DISCORD.get() : DEFAULT_RELAY_GAME_TO_DISCORD;
    }

    public static String getDiscordToGameFormat() {
        return isLoaded() ? DISCORD_TO_GAME_FORMAT.get() : DEFAULT_DISCORD_TO_GAME_FORMAT;
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

    public static boolean isShowAdvancementIcon() {
        return isLoaded() ? SHOW_ADVANCEMENT_ICON.get() : DEFAULT_SHOW_ADVANCEMENT_ICON;
    }

    public static String getAdvancementIconUrlTemplate() {
        return isLoaded() ? ADVANCEMENT_ICON_URL_TEMPLATE.get() : DEFAULT_ADVANCEMENT_ICON_URL_TEMPLATE;
    }

    public static boolean isAutoDeathReport() {
        return isLoaded() ? AUTO_DEATH_REPORT.get() : DEFAULT_AUTO_DEATH_REPORT;
    }

    public static int getDeathReportEmbedColor() {
        return isLoaded() ? DEATH_REPORT_EMBED_COLOR.get() : DEFAULT_DEATH_REPORT_EMBED_COLOR;
    }

    public static boolean isShowDeathReportImage() {
        return isLoaded() ? SHOW_DEATH_REPORT_IMAGE.get() : DEFAULT_SHOW_DEATH_REPORT_IMAGE;
    }

    public static String getDeathReportIconUrlTemplate() {
        return isLoaded() ? DEATH_REPORT_ICON_URL_TEMPLATE.get() : DEFAULT_DEATH_REPORT_ICON_URL_TEMPLATE;
    }
}
