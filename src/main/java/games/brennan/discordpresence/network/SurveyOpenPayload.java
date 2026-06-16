package games.brennan.discordpresence.network;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.client.SurveyScreenOpener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: open the feedback survey screen NOW with these questions (the player's
 * unanswered set), in response to the {@code /feedback} command. Distinct from
 * {@link SurveyQuestionPayload}, which only caches questions for the death-screen button
 * and never auto-opens — so the death path stays button-driven while the command opens the
 * screen directly. Reuses {@link SurveyQuestionPayload.Entry} and the same wire format.
 */
public record SurveyOpenPayload(List<SurveyQuestionPayload.Entry> questions) implements CustomPacketPayload {

    public static final Type<SurveyOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "survey_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyOpenPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> payload.encode(buf),
                    SurveyOpenPayload::decode);

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(questions.size());
        for (SurveyQuestionPayload.Entry e : questions) {
            buf.writeUtf(e.id());
            buf.writeUtf(e.prompt());
            buf.writeVarInt(e.scaleMin());
            buf.writeVarInt(e.scaleMax());
            buf.writeBoolean(e.allowComment());
        }
    }

    private static SurveyOpenPayload decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<SurveyQuestionPayload.Entry> questions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf();
            String prompt = buf.readUtf();
            int scaleMin = buf.readVarInt();
            int scaleMax = buf.readVarInt();
            boolean allowComment = buf.readBoolean();
            questions.add(new SurveyQuestionPayload.Entry(id, prompt, scaleMin, scaleMax, allowComment));
        }
        return new SurveyOpenPayload(List.copyOf(questions));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side: open the survey screen. Runs on the client only. */
    public static void handle(SurveyOpenPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SurveyScreenOpener.open(payload.questions()));
    }
}
