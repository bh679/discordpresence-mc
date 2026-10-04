package games.brennan.discordpresence.survey;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-player, per-question answer counter and its on-disk shapes (current + legacy array). */
class SurveyStoreTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String Q = "dungeontrain:change_one_thing";

    @Test
    void markAnsweredCountsEachAnswerAndReturnsTheNewCount(@TempDir Path dir) {
        SurveyStore store = new SurveyStore();
        store.load(dir.resolve("surveys.json"));

        assertEquals(0, store.count(PLAYER, Q));
        assertFalse(store.hasAnswered(PLAYER, Q));
        assertEquals(1, store.markAnswered(PLAYER, Q));
        assertEquals(2, store.markAnswered(PLAYER, Q));
        assertEquals(3, store.markAnswered(PLAYER, Q));
        assertEquals(3, store.count(PLAYER, Q));
        assertTrue(store.hasAnswered(PLAYER, Q));
        assertEquals(0, store.count(PLAYER, "discordpresence:nps"), "other questions stay unanswered");
    }

    @Test
    void unusableArgumentsRecordNothing(@TempDir Path dir) {
        SurveyStore store = new SurveyStore();
        store.load(dir.resolve("surveys.json"));
        assertEquals(0, store.markAnswered(null, Q));
        assertEquals(0, store.markAnswered(PLAYER, ""));
        assertEquals(0, store.markAnswered(PLAYER, null));
        assertEquals(0, store.count(PLAYER, Q));
    }

    @Test
    void savesCountsAndReloadsThem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("surveys.json");
        SurveyStore store = new SurveyStore();
        store.load(file);
        store.markAnswered(PLAYER, Q);
        store.markAnswered(PLAYER, Q);

        JsonObject onDisk = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, onDisk.getAsJsonObject(PLAYER.toString()).get(Q).getAsInt(), "file holds {id: count}");

        SurveyStore again = new SurveyStore();
        again.load(file);
        assertEquals(2, again.count(PLAYER, Q));
        assertEquals(3, again.markAnswered(PLAYER, Q), "counting continues across restarts");
    }

    @Test
    void legacyArrayFileLoadsAsAnsweredOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("surveys.json");
        Files.writeString(file, "{\"" + PLAYER + "\": [\"discordpresence:nps\", \"" + Q + "\"]}", StandardCharsets.UTF_8);

        SurveyStore store = new SurveyStore();
        store.load(file);
        assertTrue(store.hasAnswered(PLAYER, Q));
        assertEquals(1, store.count(PLAYER, Q));
        assertEquals(1, store.count(PLAYER, "discordpresence:nps"));
        assertEquals(2, store.markAnswered(PLAYER, Q), "an old answer counts as the first");

        JsonObject onDisk = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(onDisk.get(PLAYER.toString()).isJsonObject(), "next save writes the current shape");
    }

    @Test
    void corruptOrOddEntriesAreSkipped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("surveys.json");
        Files.writeString(file, "{\"not-a-uuid\": {\"" + Q + "\": 4}, \"" + PLAYER + "\": {\"" + Q + "\": \"x\", \"\": 2, \"ok:q\": 0, \"real:q\": 5}}",
                StandardCharsets.UTF_8);
        SurveyStore store = new SurveyStore();
        store.load(file);
        assertEquals(0, store.count(PLAYER, Q), "non-numeric count dropped");
        assertEquals(0, store.count(PLAYER, "ok:q"), "zero count dropped");
        assertEquals(5, store.count(PLAYER, "real:q"));
    }
}
