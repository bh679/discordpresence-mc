package games.brennan.discordpresence;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Discord Presence — standalone NeoForge mod.
 *
 * <p>Posts a Discord message via a webhook when a player logs in, then uses a
 * bot token to add an "online" reaction (removed on logout) and a "death"
 * reaction. Fully generic — hooks only vanilla player events, no game-specific
 * coupling. Designed to be bundled into Dungeon Train via jarJar, and to grow a
 * two-way Discord ↔ in-game chat bridge later (see {@code discord} package).</p>
 */
@Mod(DiscordPresence.MOD_ID)
public class DiscordPresence {
    public static final String MOD_ID = "discordpresence";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DiscordPresence(IEventBus modBus, ModContainer modContainer) {
        // The death-report image is composed with java.awt/ImageIO; force headless so it
        // works on a dedicated server with no display (must be set before any AWT use).
        System.setProperty("java.awt.headless", "true");

        // COMMON config — loaded on both dists but NEVER synced across the network
        // (unlike a SERVER config, which NeoForge syncs to every connecting client and
        // would therefore leak these secrets). Holds the webhook URL + bot token; a
        // blank webhook URL disables the mod. The Discord I/O is server-side only, so a
        // client's own copy stays blank/unused. Filename keeps the historical
        // "-server" name (it's the file a server operator edits; gitignored via *-server.toml).
        modContainer.registerConfig(
                ModConfig.Type.COMMON,
                DiscordPresenceConfig.SPEC,
                "discordpresence-server.toml");

        // CLIENT config — physical client only. Holds the one-time network-access
        // consent shown on the title screen (see ClientPresenceEvents). Harmless on a
        // dedicated server: registered but never opened, so its isLoaded() stays false.
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                DiscordPresenceClientConfig.SPEC,
                "discordpresence-client.toml");

        // No NeoForge.EVENT_BUS.register(this): the game-bus listeners live in
        // DiscordPresenceEvents (@EventBusSubscriber), which FML auto-registers.
        LOGGER.info("Discord Presence loaded (mod construction).");
    }
}
