package games.brennan.discordpresence.survey;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void firstUnansweredSkipsAnswered() {
        SurveyQuestion a = new SurveyQuestion("test:a", "A", 0, 10, true);
        SurveyQuestion b = new SurveyQuestion("test:b", "B", 0, 10, true);
        List<SurveyQuestion> bank = List.of(a, b);

        assertEquals(a, SurveyManager.firstUnanswered(bank, id -> false));
        assertEquals(b, SurveyManager.firstUnanswered(bank, Set.of("test:a")::contains));
        assertNull(SurveyManager.firstUnanswered(bank, Set.of("test:a", "test:b")::contains));
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
        assertThrows(IllegalArgumentException.class, () -> new SurveyQuestion("id", "p", 10, 0, true));
    }
}
