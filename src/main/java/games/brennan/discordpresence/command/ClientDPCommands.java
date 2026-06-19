package games.brennan.discordpresence.command;

import games.brennan.discordpresence.DiscordPresence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Registers Discord Presence's <b>client-side</b> commands. {@link RegisterClientCommandsEvent}
 * fires on the game bus on the physical client only, so this is gated {@code value = Dist.CLIENT}
 * (matching {@link games.brennan.discordpresence.event.ClientPresenceEvents}) and never class-loads
 * on a dedicated server. Client commands run locally and are always available regardless of op
 * level / "allow cheats" — see {@link ChatConnectCommand}. The server-side {@link DPCommands} /
 * {@code FeedbackCommand} registrar is separate and unaffected.
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID, value = Dist.CLIENT)
public final class ClientDPCommands {

    private ClientDPCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ChatConnectCommand.register(event.getDispatcher());
    }
}
