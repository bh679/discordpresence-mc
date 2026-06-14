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
    public static final int DEFAULT_ONLINE_REACTION_REFRESH_MINUTES = 15;
    public static final String DEFAULT_THREAD_NAME_TEMPLATE = "{player}";
    public static final int DEFAULT_THREAD_AUTO_ARCHIVE_MINUTES = 10080; // 1 week
    public static final String DEFAULT_ADVANCEMENT_TEMPLATE = "{player} earned";
    public static final boolean DEFAULT_SHOW_ADVANCEMENT_ICON = true;
    public static final String DEFAULT_ADVANCEMENT_ICON_URL_TEMPLATE =
            "https://static.minecraftitemids.com/64/{path}.png";
    public static final boolean DEFAULT_SHOW_ADVANCEMENT_REQUIREMENTS = true;
    public static final int DEFAULT_ADVANCEMENT_REQUIREMENTS_MAX = 5;
    public static final String DEFAULT_ADVANCEMENT_REQUIREMENTS_LABEL = "Requirements";

    public static final boolean DEFAULT_AUTO_DEATH_REPORT = true;
    public static final int DEFAULT_DEATH_REPORT_EMBED_COLOR = 0xFF5555; // death-red
    public static final boolean DEFAULT_SHOW_DEATH_REPORT_IMAGE = true;
    public static final String DEFAULT_DEATH_REPORT_ICON_URL_TEMPLATE =
            "https://static.minecraftitemids.com/64/{path}.png";

    public static final boolean DEFAULT_RELAY_DISCORD_TO_GAME = true;
    public static final boolean DEFAULT_RELAY_GAME_TO_DISCORD = true;
    public static final String DEFAULT_DISCORD_TO_GAME_FORMAT = "<{user}> {msg}";

    public static final boolean DEFAULT_AUTO_RESPONSE_ENABLED = true;
    public static final int DEFAULT_AUTO_RESPONSE_REARM_MINUTES = 30;
    public static final int DEFAULT_AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS = 30;
    public static final int DEFAULT_AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS = 300;
    public static final String DEFAULT_AUTO_RESPONSE_ALONE_TEMPLATE = "{player} {verb} into the {place}, {phrase}";
    public static final List<String> DEFAULT_AUTO_RESPONSE_VERBS = List.of(
            "whispers", "yells", "screams", "mutters", "mumbles", "shouts", "murmurs",
            "cries out", "calls out", "hollers", "bellows", "echoes", "pleads");
    public static final List<String> DEFAULT_AUTO_RESPONSE_PLACES = List.of(
            "darkness", "void", "open world", "minecraft world", "computer", "chat",
            "abyss", "silence", "wilderness", "ether", "emptiness", "unknown",
            "shadows", "nothingness", "expanse");
    public static final List<String> DEFAULT_AUTO_RESPONSE_PHRASES = List.of(
            "will anyone answer?", "the silence lingers...", "hoping for a reply...",
            "but no one stirs...", "maybe the world is listening?", "who's out there?",
            "a voice in the dark...", "waiting for an echo...", "is the void listening?",
            "perhaps a friend lurks nearby...");
    public static final List<String> DEFAULT_AUTO_RESPONSE_GROUP_MESSAGES =
            List.of("{player} mutters to themselves...");

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> RELAY_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> FIRST_JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> ONLINE_EMOJI;
    public static final ModConfigSpec.ConfigValue<String> DEATH_EMOJI;
    public static final ModConfigSpec.IntValue ONLINE_REACTION_REFRESH_MINUTES;
    public static final ModConfigSpec.BooleanValue RELAY_DISCORD_TO_GAME;
    public static final ModConfigSpec.BooleanValue RELAY_GAME_TO_DISCORD;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TO_GAME_FORMAT;
    public static final ModConfigSpec.BooleanValue CREATE_THREAD_ON_JOIN;
    public static final ModConfigSpec.ConfigValue<String> THREAD_NAME_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<Integer> THREAD_AUTO_ARCHIVE_MINUTES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ADVANCEMENT_NAMESPACES;
    public static final ModConfigSpec.BooleanValue ONLY_DISPLAY_ADVANCEMENTS;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.BooleanValue AUTO_RESPONSE_ENABLED;
    public static final ModConfigSpec.IntValue AUTO_RESPONSE_REARM_MINUTES;
    public static final ModConfigSpec.IntValue AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> AUTO_RESPONSE_ALONE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_RESPONSE_VERBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_RESPONSE_PLACES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_RESPONSE_PHRASES;
    public static final ModConfigSpec.IntValue AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AUTO_RESPONSE_GROUP_MESSAGES;
    public static final ModConfigSpec.BooleanValue SHOW_ADVANCEMENT_ICON;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_ICON_URL_TEMPLATE;
    public static final ModConfigSpec.BooleanValue SHOW_ADVANCEMENT_REQUIREMENTS;
    public static final ModConfigSpec.IntValue ADVANCEMENT_REQUIREMENTS_MAX;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_REQUIREMENTS_LABEL;
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
        RELAY_BASE_URL = b
                .comment("Optional relay base URL. When set, Discord Presence routes ALL Discord I/O through",
                         "this relay instead of Discord directly: webhook posts go to '<base>/hook' and bot",
                         "REST (reactions/threads/advancement embeds) to '<base>/bot', with NO bot token sent",
                         "(the relay holds + injects it server-side). Lets a bundling mod — or you — point DP",
                         "at a central feed without holding any Discord secret. Blank = talk to Discord directly",
                         "using webhookUrl + botToken above.")
                .define("relayBaseUrl", "");
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
        ONLINE_REACTION_REFRESH_MINUTES = b
                .comment("Minutes between refreshes of the online reaction (a heartbeat). While a player is",
                         "online the green reaction and a persisted 'last seen' timestamp are refreshed on this",
                         "cadence. If a session is never refreshed — because the server crashed without removing",
                         "the reaction — the stale green reaction is cleared on the next server start (and during",
                         "this refresh if a player left but the removal failed), so they are no longer shown as",
                         "online. 0 disables the periodic refresh (startup crash-cleanup still runs). Requires the",
                         "bot token and a non-blank onlineEmoji.")
                .defineInRange("onlineReactionRefreshMinutes", DEFAULT_ONLINE_REACTION_REFRESH_MINUTES, 0, 1440);
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
        SHOW_ADVANCEMENT_REQUIREMENTS = b
                .comment("Add a 'Requirements' field to the advancement embed listing the advancement's actual",
                         "sub-goals (its criteria — e.g. the biomes for Adventuring Time, foods for A Balanced",
                         "Diet). Only sub-goals NOT already reflected in the description are listed, so simple",
                         "advancements stay unchanged. The list is capped (see advancementRequirementsMax).")
                .define("showAdvancementRequirements", DEFAULT_SHOW_ADVANCEMENT_REQUIREMENTS);
        ADVANCEMENT_REQUIREMENTS_MAX = b
                .comment("Maximum number of sub-goals to list in the Requirements field before truncating with",
                         "a '+N more' suffix. Keeps long advancements (e.g. ~42 biomes) readable.")
                .defineInRange("advancementRequirementsMax", DEFAULT_ADVANCEMENT_REQUIREMENTS_MAX, 1, 25);
        ADVANCEMENT_REQUIREMENTS_LABEL = b
                .comment("Title of the Requirements embed field (the bold label shown above the sub-goal list).")
                .define("advancementRequirementsLabel", DEFAULT_ADVANCEMENT_REQUIREMENTS_LABEL);
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

        b.push("autoResponse");
        AUTO_RESPONSE_ENABLED = b
                .comment("Show an in-game flavour line when a player chats while no Discord conversation is",
                         "active (\"whispering into the darkness\"). Purely in-game — it posts nothing new to",
                         "Discord; the chat line itself still relays via relayGameToDiscord. Turns off once a",
                         "Discord reply reaches the server, re-arming after rearmMinutes of silence.")
                .define("enabled", DEFAULT_AUTO_RESPONSE_ENABLED);
        AUTO_RESPONSE_REARM_MINUTES = b
                .comment("Minutes of Discord silence (no relayed reply for that player) before auto-responses",
                         "re-arm. For local games this is remembered across worlds.")
                .defineInRange("rearmMinutes", DEFAULT_AUTO_RESPONSE_REARM_MINUTES, 1, 525_600);
        AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS = b
                .comment("Minimum seconds between auto-responses while the player is ALONE (no other players",
                         "online). A 30-second floor always applies — values below 30 are treated as 30.")
                .defineInRange("aloneCooldownSeconds", DEFAULT_AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS, 0, 86_400);
        AUTO_RESPONSE_ALONE_TEMPLATE = b
                .comment("Template for the ALONE auto-response, assembled from random picks of the lists below.",
                         "Placeholders: '{player}' = name, '{verb}', '{place}', '{phrase}'. Default reads e.g.",
                         "\"Steve whispers into the darkness, is anyone there?\"")
                .define("aloneTemplate", DEFAULT_AUTO_RESPONSE_ALONE_TEMPLATE);
        AUTO_RESPONSE_VERBS = b
                .comment("'{verb}' options for the alone template (e.g. whispers, yells). One picked at random.",
                         "Empty = no alone auto-response.")
                .defineListAllowEmpty("verbs", () -> DEFAULT_AUTO_RESPONSE_VERBS, () -> "whispers",
                        o -> o instanceof String);
        AUTO_RESPONSE_PLACES = b
                .comment("'{place}' options for the alone template (e.g. darkness, void). One picked at random.",
                         "Empty = no alone auto-response.")
                .defineListAllowEmpty("places", () -> DEFAULT_AUTO_RESPONSE_PLACES, () -> "darkness",
                        o -> o instanceof String);
        AUTO_RESPONSE_PHRASES = b
                .comment("'{phrase}' options for the alone template (the trailing line). One picked at random.",
                         "Empty = no alone auto-response.")
                .defineListAllowEmpty("phrases", () -> DEFAULT_AUTO_RESPONSE_PHRASES, () -> "is anyone there?",
                        o -> o instanceof String);
        AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS = b
                .comment("Minimum seconds between auto-responses while OTHER players are online.",
                         "A 30-second floor always applies — values below 30 are treated as 30.")
                .defineInRange("groupCooldownSeconds", DEFAULT_AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS, 0, 86_400);
        AUTO_RESPONSE_GROUP_MESSAGES = b
                .comment("Flavour lines used when OTHER players are online; one is chosen at random.",
                         "'{player}' is replaced with the player's name. Empty list = no group auto-response.")
                .defineListAllowEmpty("groupMessages", () -> DEFAULT_AUTO_RESPONSE_GROUP_MESSAGES, () -> "",
                        o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private DiscordPresenceConfig() {}

    /** SERVER config is only loaded inside an active world/server. */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    /**
     * Precedence for merging the admin's config value with a bundling mod's
     * provider value (see {@link DiscordCredentials}). PROVIDER_WINS = a bundling
     * mod's central feed overrides local config; flip to CONFIG_WINS to let a
     * server owner's own webhook/token take priority. Standalone DP (no provider)
     * is unaffected either way — the provider value is blank.
     */
    private static final CredentialResolver.Policy CREDENTIAL_POLICY =
            CredentialResolver.Policy.PROVIDER_WINS;

    /**
     * Resolved relay base URL, or {@code ""} for direct-to-Discord. When non-blank, DP is in
     * RELAY-MODE: it routes webhook + bot REST through the relay and sends no bot token. Any
     * trailing slash is stripped so derived paths are {@code <base>/hook}, not {@code <base>//hook}.
     */
    public static String getRelayBaseUrl() {
        String configValue = isLoaded() ? RELAY_BASE_URL.get() : "";
        String resolved = CredentialResolver.resolve(configValue, DiscordCredentials.providerRelayBaseUrl(), CREDENTIAL_POLICY);
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    /** Whether a relay base URL is configured (route via the relay; send no bot token). */
    public static boolean isRelayMode() {
        return !getRelayBaseUrl().isBlank();
    }

    public static String getWebhookUrl() {
        String relay = getRelayBaseUrl();
        if (!relay.isBlank()) {
            return relay + "/hook";
        }
        String configValue = isLoaded() ? WEBHOOK_URL.get() : "";
        return CredentialResolver.resolve(configValue, DiscordCredentials.providerWebhookUrl(), CREDENTIAL_POLICY);
    }

    /** Base URL for bot REST calls: the relay's {@code /bot} in relay-mode, else Discord's API directly. */
    public static String getBotApiBase() {
        String relay = getRelayBaseUrl();
        return relay.isBlank() ? "https://discord.com/api/v10" : relay + "/bot";
    }

    /**
     * WebSocket URL for the relay's inbound gateway ({@code <relayBase>/gateway} with a ws/wss
     * scheme), or {@code ""} when not in relay-mode.
     */
    public static String getRelayGatewayUrl() {
        String base = getRelayBaseUrl();
        if (base.isBlank()) {
            return "";
        }
        String ws = base.startsWith("https://") ? "wss://" + base.substring("https://".length())
                : base.startsWith("http://") ? "ws://" + base.substring("http://".length())
                : base;
        return ws + "/gateway";
    }

    public static String getBotToken() {
        String configValue = isLoaded() ? BOT_TOKEN.get() : "";
        return CredentialResolver.resolve(configValue, DiscordCredentials.providerBotToken(), CREDENTIAL_POLICY);
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

    public static int getOnlineReactionRefreshMinutes() {
        return isLoaded() ? ONLINE_REACTION_REFRESH_MINUTES.get() : DEFAULT_ONLINE_REACTION_REFRESH_MINUTES;
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

    public static boolean isAutoResponseEnabled() {
        return isLoaded() ? AUTO_RESPONSE_ENABLED.get() : DEFAULT_AUTO_RESPONSE_ENABLED;
    }

    public static int getAutoResponseRearmMinutes() {
        return isLoaded() ? AUTO_RESPONSE_REARM_MINUTES.get() : DEFAULT_AUTO_RESPONSE_REARM_MINUTES;
    }

    public static int getAutoResponseAloneCooldownSeconds() {
        return isLoaded() ? AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS.get() : DEFAULT_AUTO_RESPONSE_ALONE_COOLDOWN_SECONDS;
    }

    public static String getAutoResponseAloneTemplate() {
        return isLoaded() ? AUTO_RESPONSE_ALONE_TEMPLATE.get() : DEFAULT_AUTO_RESPONSE_ALONE_TEMPLATE;
    }

    public static List<? extends String> getAutoResponseVerbs() {
        return isLoaded() ? AUTO_RESPONSE_VERBS.get() : DEFAULT_AUTO_RESPONSE_VERBS;
    }

    public static List<? extends String> getAutoResponsePlaces() {
        return isLoaded() ? AUTO_RESPONSE_PLACES.get() : DEFAULT_AUTO_RESPONSE_PLACES;
    }

    public static List<? extends String> getAutoResponsePhrases() {
        return isLoaded() ? AUTO_RESPONSE_PHRASES.get() : DEFAULT_AUTO_RESPONSE_PHRASES;
    }

    public static int getAutoResponseGroupCooldownSeconds() {
        return isLoaded() ? AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS.get() : DEFAULT_AUTO_RESPONSE_GROUP_COOLDOWN_SECONDS;
    }

    public static List<? extends String> getAutoResponseGroupMessages() {
        return isLoaded() ? AUTO_RESPONSE_GROUP_MESSAGES.get() : DEFAULT_AUTO_RESPONSE_GROUP_MESSAGES;
    }

    public static boolean isShowAdvancementIcon() {
        return isLoaded() ? SHOW_ADVANCEMENT_ICON.get() : DEFAULT_SHOW_ADVANCEMENT_ICON;
    }

    public static String getAdvancementIconUrlTemplate() {
        return isLoaded() ? ADVANCEMENT_ICON_URL_TEMPLATE.get() : DEFAULT_ADVANCEMENT_ICON_URL_TEMPLATE;
    }

    public static boolean isShowAdvancementRequirements() {
        return isLoaded() ? SHOW_ADVANCEMENT_REQUIREMENTS.get() : DEFAULT_SHOW_ADVANCEMENT_REQUIREMENTS;
    }

    public static int getAdvancementRequirementsMax() {
        return isLoaded() ? ADVANCEMENT_REQUIREMENTS_MAX.get() : DEFAULT_ADVANCEMENT_REQUIREMENTS_MAX;
    }

    public static String getAdvancementRequirementsLabel() {
        return isLoaded() ? ADVANCEMENT_REQUIREMENTS_LABEL.get() : DEFAULT_ADVANCEMENT_REQUIREMENTS_LABEL;
    }

    public static boolean isAutoDeathReport() {
        // A bundling mod (e.g. Dungeon Train) that posts its own death report suppresses DP's generic one.
        if (DiscordCredentials.providerSuppressAutoDeathReport()) {
            return false;
        }
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
