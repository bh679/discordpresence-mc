package games.brennan.discordpresence.event;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.discord.DiscordService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Wires vanilla player-lifecycle + advancement events to {@link DiscordService}.
 * Logic-free delegation. Registered on the game event bus for BOTH dists (no
 * {@code value} = Dist filter) so it also runs on a dedicated server.
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID)
public final class DiscordPresenceEvents {

    private DiscordPresenceEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        DiscordService.get().loadThreads();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiscordService.get().onPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiscordService.get().onPlayerLeave(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiscordService.get().onPlayerDeath(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiscordService.get().onAdvancement(player, event.getAdvancement());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        DiscordService.get().clearAll();
    }
}
