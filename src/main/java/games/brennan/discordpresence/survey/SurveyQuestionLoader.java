package games.brennan.discordpresence.survey;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Loads survey questions from data files at {@code data/<namespace>/dp_surveys/*.json}
 * across every datapack and bundling mod, so questions can be added declaratively — no
 * code — and reloaded with {@code /reload}. The file's resource id
 * ({@code <namespace>:<path>}) becomes the question id, so e.g.
 * {@code data/dungeontrain/dp_surveys/difficulty_progression.json} →
 * {@code dungeontrain:difficulty_progression}.
 *
 * <p>JSON shape (only {@code prompt} is required):
 * {@code {"prompt": "...", "scale_min": 0, "scale_max": 10, "allow_comment": true, "order": 100}}.
 * Loaded questions are sorted by {@code (order, id)} and handed to
 * {@link SurveyRegistry#setDataDriven}; they appear after the built-in NPS question.</p>
 */
public final class SurveyQuestionLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Scans {@code data/<ns>/dp_surveys/*.json}. */
    public static final String DIRECTORY = "dp_surveys";
    private static final int DEFAULT_ORDER = 1000;

    public SurveyQuestionLoader() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        record Loaded(SurveyQuestion question, int order) {}
        List<Loaded> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            try {
                JsonObject obj = GsonHelper.convertToJsonObject(entry.getValue(), "survey question");
                SurveyQuestion question = parse(entry.getKey().toString(), obj);
                int order = GsonHelper.getAsInt(obj, "order", DEFAULT_ORDER);
                loaded.add(new Loaded(question, order));
            } catch (Exception ex) {
                LOGGER.warn("Discord Presence: skipping invalid survey question '{}': {}",
                        entry.getKey(), ex.toString());
            }
        }
        loaded.sort(Comparator.comparingInt(Loaded::order).thenComparing(l -> l.question().id()));
        List<SurveyQuestion> questions = loaded.stream().map(Loaded::question).toList();
        SurveyRegistry.setDataDriven(questions);
        LOGGER.info("Discord Presence: loaded {} data-driven survey question(s).", questions.size());
    }

    /**
     * Parse one question JSON ({@code id} = its resource location, e.g.
     * {@code "dungeontrain:difficulty_progression"}). Defaults: scale 0–10, comment
     * allowed. Throws when {@code prompt} is missing or blank. Package-visible for tests.
     */
    static SurveyQuestion parse(String id, JsonObject obj) {
        String prompt = GsonHelper.getAsString(obj, "prompt");
        int scaleMin = GsonHelper.getAsInt(obj, "scale_min", 0);
        int scaleMax = GsonHelper.getAsInt(obj, "scale_max", 10);
        boolean allowComment = GsonHelper.getAsBoolean(obj, "allow_comment", true);
        return new SurveyQuestion(id, prompt, scaleMin, scaleMax, allowComment);
    }
}
