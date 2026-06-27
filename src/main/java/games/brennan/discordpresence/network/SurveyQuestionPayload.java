package games.brennan.discordpresence.network;

import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.client.SurveyClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the player's UNANSWERED survey questions for this death, in ask-order.
 * The client walks them one screen at a time (each submitted + posted to Discord). An
 * empty list hides the death-screen Feedback button. Re-sent on each death, recomputed
 * from what the player has already answered.
 */
public record SurveyQuestionPayload(List<Entry> questions) implements CustomPacketPayload {

    /**
     * One question's client-facing fields. {@code options} is non-empty for a multiple-choice
     * question (the submitted score is the chosen 0-based index into it); empty otherwise.
     */
    public record Entry(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment,
                        List<String> options) {
        public Entry {
            options = options == null ? List.of() : List.copyOf(options);
        }

        /** Back-compat constructor for a plain scale/text question (no choice options). */
        public Entry(String id, String prompt, int scaleMin, int scaleMax, boolean allowComment) {
            this(id, prompt, scaleMin, scaleMax, allowComment, List.of());
        }
    }

    /** The "no questions — hide the button" sentinel. */
    public static final SurveyQuestionPayload NONE = new SurveyQuestionPayload(List.of());

    public static final Type<SurveyQuestionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DiscordPresence.MOD_ID, "survey_question"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurveyQuestionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> payload.encode(buf),
                    SurveyQuestionPayload::decode);

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(questions.size());
        for (Entry e : questions) {
            writeEntry(buf, e);
        }
    }

    private static SurveyQuestionPayload decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> questions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            questions.add(readEntry(buf));
        }
        return new SurveyQuestionPayload(List.copyOf(questions));
    }

    /** Shared entry wire format (also reused by {@link SurveyOpenPayload}). */
    static void writeEntry(RegistryFriendlyByteBuf buf, Entry e) {
        buf.writeUtf(e.id());
        buf.writeUtf(e.prompt());
        buf.writeVarInt(e.scaleMin());
        buf.writeVarInt(e.scaleMax());
        buf.writeBoolean(e.allowComment());
        buf.writeVarInt(e.options().size());
        for (String option : e.options()) {
            buf.writeUtf(option);
        }
    }

    static Entry readEntry(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf();
        String prompt = buf.readUtf();
        int scaleMin = buf.readVarInt();
        int scaleMax = buf.readVarInt();
        boolean allowComment = buf.readBoolean();
        int optionCount = buf.readVarInt();
        List<String> options = new ArrayList<>(optionCount);
        for (int i = 0; i < optionCount; i++) {
            options.add(buf.readUtf());
        }
        return new Entry(id, prompt, scaleMin, scaleMax, allowComment, options);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side: cache the session's questions (or clear). Runs on the client only. */
    public static void handle(SurveyQuestionPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SurveyClientState.set(payload.questions()));
    }
}
