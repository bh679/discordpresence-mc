package games.brennan.discordpresence.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the survey domain: the built-in registry, next-question
 * selection, score clamping, comment sanitising, and question validation. No
 * Minecraft runtime needed.
 */
class SurveyTest {

    @Test
    void npsBuiltInIsRegisteredFirst() {
        List<SurveyQuestion> qs = SurveyRegistry.questions();
        assertFalse(qs.isEmpty(), "registry should contain the built-in NPS question");
        assertEquals(SurveyRegistry.NPS_ID, qs.get(0).id(), "NPS must be asked first");
        assertNotNull(SurveyRegistry.byId(SurveyRegistry.NPS_ID));
    }

    @Test
    void duplicateIdIsIgnored() {
        int before = SurveyRegistry.questions().size();
        SurveyRegistry.register(SurveyQuestion.nps(SurveyRegistry.NPS_ID, "a duplicate prompt"));
        assertEquals(before, SurveyRegistry.questions().size(), "duplicate id must not grow the bank");
    }

    @Test
    void unansweredReturnsRemainingInOrder() {
        SurveyQuestion a = new SurveyQuestion("test:a", "A", 0, 10, true);
        SurveyQuestion b = new SurveyQuestion("test:b", "B", 0, 10, true);
        List<SurveyQuestion> bank = List.of(a, b);

        assertEquals(List.of(a, b), SurveyManager.unanswered(bank, id -> false));
        assertEquals(List.of(b), SurveyManager.unanswered(bank, Set.of("test:a")::contains));
        assertTrue(SurveyManager.unanswered(bank, Set.of("test:a", "test:b")::contains).isEmpty());
    }

    @Test
    void scoreIsClampedToRange() {
        SurveyQuestion q = new SurveyQuestion("test:c", "C", 0, 10, false);
        assertEquals(0, q.clampScore(-5));
        assertEquals(10, q.clampScore(99));
        assertEquals(7, q.clampScore(7));
    }

    @Test
    void commentIsSingleLineAndCapped() {
        assertEquals("a b", SurveyManager.sanitizeComment("a\nb"));
        assertEquals("", SurveyManager.sanitizeComment(null));
        String veryLong = "x".repeat(401);
        assertTrue(SurveyManager.sanitizeComment(veryLong).length() <= 301);
    }

    @Test
    void questionValidationRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new SurveyQuestion("", "p", 0, 10, true));
        assertThrows(IllegalArgumentException.class, () -> new SurveyQuestion("id", " ", 0, 10, true));
        // An inverted scale with no comment leaves no way to answer — rejected. (With a comment
        // it is the valid "text question" form, covered by noScaleQuestionRequiresComment.)
        assertThrows(IllegalArgumentException.class, () -> new SurveyQuestion("id", "p", 10, 0, false));
    }

    @Test
    void textQuestionHasNoScaleButAllowsComment() {
        SurveyQuestion q = SurveyQuestion.text("test:txt", "If you could change one thing?");
        assertFalse(q.hasScale(), "a text question has no rating scale");
        assertTrue(q.allowComment(), "a text question's comment is its answer");
    }

    @Test
    void scaleQuestionHasScale() {
        assertTrue(new SurveyQuestion("test:s", "Rate", 0, 10, true).hasScale());
        assertTrue(SurveyQuestion.nps("test:n", "NPS").hasScale());
    }

    @Test
    void noScaleQuestionRequiresComment() {
        // Allowed: no scale + comment (the text-question form).
        SurveyQuestion ok = new SurveyQuestion("test:ok", "Q", 0, -1, true);
        assertFalse(ok.hasScale());
        // Rejected: no scale + no comment leaves nothing to answer with.
        assertThrows(IllegalArgumentException.class, () -> new SurveyQuestion("test:bad", "Q", 0, -1, false));
    }

    @Test
    void loaderParsesDefaults() {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", "Why?");
        SurveyQuestion q = SurveyQuestionLoader.parse("test:why", o);
        assertEquals("test:why", q.id());
        assertEquals("Why?", q.prompt());
        assertEquals(0, q.scaleMin());
        assertEquals(10, q.scaleMax());
        assertTrue(q.allowComment());
    }

    @Test
    void loaderRespectsExplicitFields() {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", "Rate");
        o.addProperty("scale_min", 1);
        o.addProperty("scale_max", 5);
        o.addProperty("allow_comment", false);
        SurveyQuestion q = SurveyQuestionLoader.parse("test:rate", o);
        assertEquals(1, q.scaleMin());
        assertEquals(5, q.scaleMax());
        assertFalse(q.allowComment());
    }

    @Test
    void loaderParsesTextQuestion() {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", "If you could change one thing about the mod, what would it be?");
        o.addProperty("scale", false);
        SurveyQuestion q = SurveyQuestionLoader.parse("test:change", o);
        assertFalse(q.hasScale(), "scale:false → no rating scale");
        assertTrue(q.allowComment(), "a text question always allows the (answer) comment");
    }

    @Test
    void choiceQuestionModelsOptionsAsAScale() {
        SurveyQuestion q = SurveyQuestion.choice("test:bug", "Any bugs?",
                List.of("The Train Disappeared", "Lag", "Other", "No"), true);
        assertTrue(q.isChoice(), "a question with options is a choice question");
        assertTrue(q.hasScale(), "choice reuses the scale path so the index can be submitted");
        assertEquals(0, q.scaleMin());
        assertEquals(3, q.scaleMax(), "scaleMax is options.size()-1");
        assertEquals(List.of("The Train Disappeared", "Lag", "Other", "No"), q.options());
        assertTrue(q.allowComment());
    }

    @Test
    void choiceQuestionRejectsEmptyOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> SurveyQuestion.choice("test:bad", "Q", List.of(), true));
    }

    @Test
    void plainQuestionIsNotAChoice() {
        assertFalse(SurveyQuestion.nps("test:n", "NPS").isChoice());
        assertFalse(SurveyQuestion.text("test:t", "T").isChoice());
    }

    @Test
    void loaderParsesChoiceQuestionFromOptions() {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", "Did you face any bugs in this run?");
        JsonArray options = new JsonArray();
        options.add("The Train Disappeared");
        options.add("Lag");
        options.add("Other");
        options.add("No");
        o.add("options", options);
        SurveyQuestion q = SurveyQuestionLoader.parse("dungeontrain:bug_report", o);
        assertTrue(q.isChoice(), "options[] → a choice question");
        assertEquals(4, q.options().size());
        assertEquals("The Train Disappeared", q.options().get(0));
        assertTrue(q.allowComment(), "allow_comment defaults to true");
    }

    @Test
    void loaderRejectsBlankPrompt() {
        JsonObject o = new JsonObject();
        o.addProperty("prompt", " ");
        assertThrows(IllegalArgumentException.class, () -> SurveyQuestionLoader.parse("test:blank", o));
    }

    @Test
    void dataDrivenQuestionsAppendAfterProgrammaticAndAreFindable() {
        SurveyQuestion dd = new SurveyQuestion("test:dd", "Data question", 0, 10, true);
        try {
            SurveyRegistry.setDataDriven(List.of(dd));
            List<SurveyQuestion> qs = SurveyRegistry.questions();
            assertEquals(SurveyRegistry.NPS_ID, qs.get(0).id(), "NPS stays first");
            assertTrue(qs.stream().anyMatch(q -> q.id().equals("test:dd")), "data-driven question is included");
            assertNotNull(SurveyRegistry.byId("test:dd"), "byId finds data-driven questions");
        } finally {
            SurveyRegistry.setDataDriven(List.of()); // don't leak into other tests
        }
    }

    // --- Survey prompt/option translation keys (SurveyKeys) + lang-file coverage ---

    @Test
    void surveyKeysDeriveFromId() {
        assertEquals("discordpresence.survey.q.nps", SurveyKeys.promptKey("discordpresence:nps"));
        assertEquals("discordpresence.survey.q.bug_report", SurveyKeys.promptKey("dungeontrain:bug_report"));
        assertEquals("discordpresence.survey.q.change_one_thing",
                SurveyKeys.promptKey("dungeontrain:change_one_thing"));
        assertEquals("discordpresence.survey.q.bug_report.option.0",
                SurveyKeys.optionKey("dungeontrain:bug_report", 0));
        assertEquals("discordpresence.survey.q.bug_report.option.3",
                SurveyKeys.optionKey("dungeontrain:bug_report", 3));
        // A '/' in the path flattens to '.'; a bare id (no namespace) is used as-is.
        assertEquals("discordpresence.survey.q.a.b", SurveyKeys.promptKey("ns:a/b"));
        assertEquals("discordpresence.survey.q.plain", SurveyKeys.promptKey("plain"));
    }

    /** Every prompt/option key the client derives must exist and be non-blank in BOTH lang files. */
    @Test
    void langFilesContainAllSurveyKeys() {
        List<String> keys = List.of(
                SurveyKeys.promptKey(SurveyRegistry.NPS_ID),
                SurveyKeys.promptKey("dungeontrain:bug_report"),
                SurveyKeys.optionKey("dungeontrain:bug_report", 0),
                SurveyKeys.optionKey("dungeontrain:bug_report", 1),
                SurveyKeys.optionKey("dungeontrain:bug_report", 2),
                SurveyKeys.optionKey("dungeontrain:bug_report", 3),
                SurveyKeys.promptKey("dungeontrain:change_one_thing"));
        JsonObject en = readLang("en_us");
        JsonObject zh = readLang("zh_cn");
        for (String key : keys) {
            assertTrue(en.has(key), "en_us.json missing survey key " + key);
            assertFalse(en.get(key).getAsString().isBlank(), "en_us." + key + " is blank");
            assertTrue(zh.has(key), "zh_cn.json missing survey key " + key);
            String zhVal = zh.get(key).getAsString();
            assertFalse(zhVal.isBlank(), "zh_cn." + key + " is blank");
            assertTrue(zhVal.chars().anyMatch(c -> c > 0x7F),
                    "zh_cn." + key + " should be localized (non-ASCII), was: " + zhVal);
        }
    }

    /** The NPS English key must equal the code literal (both in this repo) — locks against drift. */
    @Test
    void npsEnglishKeyMatchesLiteral() {
        String literal = SurveyRegistry.byId(SurveyRegistry.NPS_ID).prompt();
        String enValue = readLang("en_us").get(SurveyKeys.promptKey(SurveyRegistry.NPS_ID)).getAsString();
        assertEquals(literal, enValue,
                "en_us NPS prompt must match the SurveyRegistry literal (the Discord/UI fallback)");
    }

    private static JsonObject readLang(String locale) {
        try (InputStream in = SurveyTest.class.getResourceAsStream(
                "/assets/discordpresence/lang/" + locale + ".json")) {
            assertNotNull(in, locale + ".json must be on the test classpath");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
