package games.brennan.discordpresence.survey;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

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
}
