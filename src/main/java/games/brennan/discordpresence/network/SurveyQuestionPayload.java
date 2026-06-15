package games.brennan.discordpresence.network;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.client.SurveyClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the player's CURRENT survey question to offer on the death screen,
 * or "none" ({@code present == false}) to hide the Feedback button. Pushed on death
 * (the next unanswered question) and cleared after the player submits, so each death
 * surfaces exactly one new question.
 */
public record SurveyQuestionPayload(boolean present, String questionId, String prompt,
                                    int scaleMin, int scaleMax, boolean allowComment)
        implements CustomPacketPayload {

    /** The "no question — hide the button" sentinel. */
    public static final SurveyQuestionPayload NONE =
            new SurveyQuestionPayload(false, "", "", 0, 0, false);

    public static final Type<SurveyQuestionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "survey_question"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyQuestionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> payload.encode(buf),
                    SurveyQuestionPayload::decode);

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(present);
        if (!present) {
            return;
        }
        buf.writeUtf(questionId);
        buf.writeUtf(prompt);
        buf.writeVarInt(scaleMin);
        buf.writeVarInt(scaleMax);
        buf.writeBoolean(allowComment);
    }

    private static SurveyQuestionPayload decode(RegistryFriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        if (!present) {
            return NONE;
        }
        String questionId = buf.readUtf();
        String prompt = buf.readUtf();
        int scaleMin = buf.readVarInt();
        int scaleMax = buf.readVarInt();
        boolean allowComment = buf.readBoolean();
        return new SurveyQuestionPayload(true, questionId, prompt, scaleMin, scaleMax, allowComment);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side: cache the offered question (or clear it). Runs on the client only. */
    public static void handle(SurveyQuestionPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SurveyClientState.set(payload));
    }
}
