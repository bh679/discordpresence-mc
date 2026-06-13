package games.brennan.discordpresence.event;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-only: the one-time network-access confirmation. The first time the player
 * reaches the title screen with consent still {@code UNSET}, this shows a generic
 * {@link ConfirmScreen} and records the answer in {@link DiscordPresenceClientConfig}.
 * Until consent is granted, the mod makes no Discord network calls in singleplayer/LAN
 * ({@code DiscordService} enforces the gate). The wording is intentionally generic.
 *
 * <p>Isolated in its own {@code value = Dist.CLIENT} subscriber so a dedicated server
 * never class-loads the client-only screen types referenced here.</p>
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID, value = Dist.CLIENT)
public final class ClientPresenceEvents {

    /** Show the prompt at most once per launch, even if the title screen re-initialises. */
    private static boolean promptedThisLaunch = false;

    private ClientPresenceEvents() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        if (promptedThisLaunch || !DiscordPresenceClientConfig.isUnset()) {
            return;
        }
        promptedThisLaunch = true;

        Minecraft mc = Minecraft.getInstance();
        Screen titleScreen = event.getScreen();
        ConfirmScreen prompt = new ConfirmScreen(
                accepted -> {
                    DiscordPresenceClientConfig.setConsent(accepted
                            ? DiscordPresenceClientConfig.Consent.GRANTED
                            : DiscordPresenceClientConfig.Consent.DENIED);
                    mc.setScreen(titleScreen);
                },
                Component.literal("Enable network features?"),
                Component.literal("This mod can use an internet connection to enable extra features. "
                        + "Allow it now? You can change this anytime in the mod's config."),
                Component.literal("Enable"),
                Component.literal("Not now"));
        mc.setScreen(prompt);
    }
}
