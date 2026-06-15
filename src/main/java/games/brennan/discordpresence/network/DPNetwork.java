package games.brennan.discordpresence.network;

import games.brennan.discordpresence.DiscordPresence;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers Discord Presence's client/server payloads. The mod had no Minecraft
 * networking before the death-screen feedback survey; this is its first registrar.
 *
 * <p>The payloads are {@code optional()} so a client without Discord Presence (or
 * with an older protocol) can still connect — the survey simply does not appear.</p>
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID)
public final class DPNetwork {

    public static final String PROTOCOL_VERSION = "2";

    private DPNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(DiscordPresence.MOD_ID)
                .versioned(PROTOCOL_VERSION)
                .optional();
        registrar.playToClient(SurveyQuestionPayload.TYPE, SurveyQuestionPayload.STREAM_CODEC,
                SurveyQuestionPayload::handle);
        registrar.playToServer(SurveySubmitPayload.TYPE, SurveySubmitPayload.STREAM_CODEC,
                SurveySubmitPayload::handle);
    }

    /** Send a payload to one player (server → client). */
    public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Send a payload to the server (client → server). */
    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
