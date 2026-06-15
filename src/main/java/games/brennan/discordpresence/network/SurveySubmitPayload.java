package games.brennan.discordpresence.network;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.survey.SurveyManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: a player's answer to one survey question — the question id, the
 * 0–N score they picked, and an optional free-text comment. The server validates the
 * id against the registry, records it answered, and posts the response to Discord
 * (see {@link SurveyManager#record}).
 */
public record SurveySubmitPayload(String questionId, int score, String comment)
        implements CustomPacketPayload {

    public static final Type<SurveySubmitPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "survey_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveySubmitPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.questionId);
                        buf.writeVarInt(payload.score);
                        buf.writeUtf(payload.comment == null ? "" : payload.comment);
                    },
                    buf -> new SurveySubmitPayload(buf.readUtf(), buf.readVarInt(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side: hand the answer to the survey manager (validates + posts to Discord). */
    public static void handle(SurveySubmitPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                SurveyManager.get().record(player, payload.questionId(), payload.score(), payload.comment());
            }
        });
    }
}
