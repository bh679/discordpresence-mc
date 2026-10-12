package games.brennan.discordpresence.event;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-only: the one-time network-access confirmation. The first time the player
 * reaches the title screen with consent still {@code UNSET}, this opens the custom
 * {@link NetworkConsentScreen} (a small designed card listing what the connection is
 * for) and the screen records the answer in {@link DiscordPresenceClientConfig}.
 * Until consent is granted, the mod makes no Discord network calls in singleplayer/LAN
 * ({@code DiscordService} enforces the gate).
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
        // Due = never answered, OR answered against an older consent version than the bundling mod now
        // asks for (see DiscordCredentialsProvider#networkConsentVersion) — the terms-of-service style
        // re-ask — OR declined, when the bundler asks for the card back while the connection is off
        // (DiscordCredentialsProvider#networkConsentReaskWhenDenied). Still at most once per launch.
        boolean due = DiscordPresenceClientConfig.isConsentDue(DiscordCredentials.providerNetworkConsentVersion())
                || (DiscordPresenceClientConfig.getConsent() == DiscordPresenceClientConfig.Consent.DENIED
                        && DiscordCredentials.providerNetworkConsentReaskWhenDenied());
        if (promptedThisLaunch || !due) {
            return;
        }
        promptedThisLaunch = true;

        Minecraft mc = Minecraft.getInstance();
        Screen titleScreen = event.getScreen();
        mc.setScreen(new NetworkConsentScreen(titleScreen));
    }
}
