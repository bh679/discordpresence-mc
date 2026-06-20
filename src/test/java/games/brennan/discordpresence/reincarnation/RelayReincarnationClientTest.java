package games.brennan.discordpresence.reincarnation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.PostPayload;
import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.RelayRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay reincarnation contract: POST body shape, GET query-URL building, and response parsing.
 * Pure string/JSON logic — no network or Minecraft runtime (the snapshot/friends fields are already
 * opaque strings at this layer).
 */
class RelayReincarnationClientTest {

    private static final String BASE = "https://brennan.games/api/dp-relay/CAP123";

    // --- POST body ---------------------------------------------------------

    @Test
    void postBodyIncludesAllFieldsWhenPresent() {
        String body = RelayReincarnationClient.buildPostBody(
                new PostPayload("SNAP", "Steve", "uuid-1", 7, "https://skin", List.of("F1", "F2")));
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("SNAP", o.get("snapshot").getAsString());
        assertEquals("Steve", o.get("name").getAsString());
        assertEquals("uuid-1", o.get("playerId").getAsString());
        assertEquals(7, o.get("carriage").getAsInt());
        assertEquals("https://skin", o.get("skinUrl").getAsString());
        assertEquals(2, o.getAsJsonArray("friends").size());
    }

    @Test
    void postBodyOmitsCarriageWhenNull() {
        // A death not on a train → carriage omitted, so the relay keeps it out of carriage bands.
        String body = RelayReincarnationClient.buildPostBody(
                new PostPayload("SNAP", "Steve", "uuid-1", null, "", List.of()));
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("SNAP", o.get("snapshot").getAsString());
        assertFalse(o.has("carriage"));
        assertFalse(o.has("skinUrl")); // blank omitted
        assertFalse(o.has("friends")); // empty omitted
    }

    @Test
    void postBodyAlwaysIncludesSnapshot() {
        String body = RelayReincarnationClient.buildPostBody(
                new PostPayload("ONLY_SNAP", null, null, null, null, null));
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("ONLY_SNAP", o.get("snapshot").getAsString());
        assertFalse(o.has("name"));
        assertFalse(o.has("playerId"));
    }

    // --- GET query URL -----------------------------------------------------

    @Test
    void queryUrlIncludesCarriageAndEncodedExclude() {
        String uuid = "11111111-2222-3333-4444-555555555555";
        String url = RelayReincarnationClient.buildQueryUrl(BASE, 12, 30, uuid, 5);
        assertEquals(BASE + "/reincarnations?radius=30&limit=5&carriage=12&exclude=" + uuid, url);
    }

    @Test
    void queryUrlOmitsCarriageAndExcludeWhenAbsent() {
        String url = RelayReincarnationClient.buildQueryUrl(BASE, null, 30, null, 5);
        assertEquals(BASE + "/reincarnations?radius=30&limit=5", url);
        assertFalse(url.contains("carriage"));
        assertFalse(url.contains("exclude"));
    }

    // --- response parsing --------------------------------------------------

    @Test
    void parsesRecordsOldestToNewestWithAllFields() {
        String json = "{\"records\":["
                + "{\"id\":1,\"ts\":100,\"playerId\":\"p1\",\"name\":\"Alice\",\"carriage\":3,"
                + "\"skinUrl\":\"s1\",\"snapshot\":\"SNAP1\",\"friends\":[\"a\",\"b\"]},"
                + "{\"id\":2,\"ts\":200,\"playerId\":\"p2\",\"name\":\"Bob\",\"carriage\":null,"
                + "\"skinUrl\":\"\",\"snapshot\":\"SNAP2\",\"friends\":[]}"
                + "]}";
        List<RelayRecord> recs = RelayReincarnationClient.parseRecords(json);
        assertEquals(2, recs.size());

        RelayRecord first = recs.get(0);
        assertEquals("1", first.id()); // numeric id read as its string form (used as the record key)
        assertEquals("p1", first.playerId());
        assertEquals("Alice", first.name());
        assertEquals(3, first.carriage());
        assertEquals("SNAP1", first.snapshot());
        assertEquals(List.of("a", "b"), first.friends());

        RelayRecord second = recs.get(1);
        assertEquals("2", second.id());
        assertNull(second.carriage()); // null carriage tolerated → "any" band life
        assertTrue(second.friends().isEmpty());
    }

    @Test
    void parsesEmptyAndMissingRecordsToEmptyList() {
        assertTrue(RelayReincarnationClient.parseRecords("{\"records\":[]}").isEmpty());
        assertTrue(RelayReincarnationClient.parseRecords("{}").isEmpty());
        assertTrue(RelayReincarnationClient.parseRecords("").isEmpty());
        assertTrue(RelayReincarnationClient.parseRecords("not json").isEmpty()); // tolerant of garbage
    }
}
