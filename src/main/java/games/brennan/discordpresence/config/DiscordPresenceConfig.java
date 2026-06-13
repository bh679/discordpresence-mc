package games.brennan.discordpresence.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-scoped config for Discord Presence, stored at
 * {@code <config>/discordpresence-server.toml}.
 *
 * <p>Holds SECRETS (the webhook URL and bot token), so it is registered as
 * {@code ModConfig.Type.SERVER} — server-side only, never transmitted to
 * clients. A blank webhook URL disables the mod entirely; a blank bot token
 * disables only the reactions (messages still post).</p>
 *
 * <p>SERVER config is loaded only inside an active world/server, so the static
 * getters guard on {@link #isLoaded()} and fall back to defaults otherwise
 * (mirrors Dungeon Train's {@code DungeonTrainConfig}).</p>
 */
public final class DiscordPresenceConfig {

    public static final String DEFAULT_JOIN_TEMPLATE = "🎮 **{player}** started the game";
    public static final String DEFAULT_ONLINE_EMOJI = "🟢"; // 🟢
    public static final String DEFAULT_DEATH_EMOJI = "💀";  // 💀

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> WEBHOOK_URL;
    public static final ModConfigSpec.ConfigValue<String> BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> JOIN_MESSAGE_TEMPLATE;
    public static final ModConfigSpec.ConfigValue<String> ONLINE_EMOJI;
    public static final ModConfigSpec.ConfigValue<String> DEATH_EMOJI;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("discord");
        WEBHOOK_URL = b
                .comment("Discord incoming-webhook URL used to POST the join message.",
                         "Leave blank to disable Discord Presence entirely. SECRET — do not share or commit.")
                .define("webhookUrl", "");
        BOT_TOKEN = b
                .comment("Discord bot token used to add/remove reactions on the join message.",
                         "Required for the online/death reactions — webhooks cannot react.",
                         "The bot must be in the webhook's server with the Add Reactions + Read Message History",
                         "permissions. Leave blank to post messages without reactions. SECRET — do not share or commit.")
                .define("botToken", "");
        JOIN_MESSAGE_TEMPLATE = b
                .comment("Message posted when a player logs in. '{player}' is replaced with the player's name.")
                .define("joinMessageTemplate", DEFAULT_JOIN_TEMPLATE);
        ONLINE_EMOJI = b
                .comment("Emoji reaction added while a player is online and removed when they log out.",
                         "A standard unicode emoji (e.g. 🟢). Leave blank to skip the online reaction.")
                .define("onlineEmoji", DEFAULT_ONLINE_EMOJI);
        DEATH_EMOJI = b
                .comment("Emoji reaction added to the join message when the player dies.",
                         "A standard unicode emoji (e.g. 💀). Leave blank to skip the death reaction.")
                .define("deathEmoji", DEFAULT_DEATH_EMOJI);
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

    public static String getOnlineEmoji() {
        return isLoaded() ? ONLINE_EMOJI.get() : DEFAULT_ONLINE_EMOJI;
    }

    public static String getDeathEmoji() {
        return isLoaded() ? DEATH_EMOJI.get() : DEFAULT_DEATH_EMOJI;
    }
}
