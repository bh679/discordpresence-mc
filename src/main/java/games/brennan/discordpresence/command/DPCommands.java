package games.brennan.discordpresence.command;

import games.brennan.discordpresence.DiscordPresence;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers Discord Presence's in-game commands. Game-bus subscriber on both dists (the
 * event only fires where a server exists), matching {@link
 * games.brennan.discordpresence.event.DiscordPresenceEvents}. This is the mod's first
 * command registrar.
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID)
public final class DPCommands {

    private DPCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        FeedbackCommand.register(event.getDispatcher());
        ReincarnationCommand.register(event.getDispatcher()); // /dpreincarnation — cross-world reincarnation diagnostics
    }
}
