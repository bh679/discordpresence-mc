package games.brennan.discordpresence.event;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.discordpresence.survey.SurveyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Wires vanilla player-lifecycle, chat, and advancement events to {@link DiscordService}.
 * Logic-free delegation. Registered on the game event bus for BOTH dists (no
 * {@code value} = Dist filter) so it runs on a dedicated server and the singleplayer
 * integrated server alike — these are all server-side events. The client-only
 * network-consent prompt lives separately in {@code ClientPresenceEvents}.
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID)
public final class DiscordPresenceEvents {

    private DiscordPresenceEvents() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        DiscordService service = DiscordService.get();
        service.loadThreads();                       // load the persisted player→thread map first
        SurveyManager.get().load();                  // and the per-player survey answers
        service.onServerStarted(event.getServer());  // then open the gateway
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
            DiscordService.get().onPlayerLeave(player);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DiscordService.get().onPlayerDeath(player, event.getSource());
            SurveyManager.get().onPlayerDeath(player); // offer the next survey question on the death screen
        }
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        // Observe only — never cancel; relay the raw line to Discord.
        DiscordService service = DiscordService.get();
        service.onGameChat(event.getPlayer(), event.getRawText());
        // Highlight configured @tags yellow in the broadcast message everyone sees (best-effort).
        Component highlighted = service.colorizeChatTags(event.getRawText());
        if (highlighted != null) {
            event.setMessage(highlighted);
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
