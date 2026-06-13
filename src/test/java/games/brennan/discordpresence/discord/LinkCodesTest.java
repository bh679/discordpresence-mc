package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the link-code registry: generation, matching, expiry, single-use. */
class LinkCodesTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final long MINUTE = 60_000L;

    // --- pure generate / match -------------------------------------------

    @Test
    void generatesCodeOfExpectedLengthAndCharset() {
        String code = LinkCodes.generate(new Random(42));
        assertEquals(LinkCodes.CODE_LENGTH, code.length());
        for (char c : code.toCharArray()) {
            assertTrue(LinkCodes.CHARSET.indexOf(c) >= 0, "unexpected char: " + c);
        }
    }

    @Test
    void matchIsCaseInsensitiveAndTokenAware() {
        Set<String> codes = Set.of("ABC234");
        assertEquals("ABC234", LinkCodes.match("ABC234", codes));
        assertEquals("ABC234", LinkCodes.match("abc234", codes));
        assertEquals("ABC234", LinkCodes.match("  my code is abc234  ", codes));
        assertNull(LinkCodes.match("ABC999", codes));
        assertNull(LinkCodes.match("", codes));
        assertNull(LinkCodes.match(null, codes));
        assertNull(LinkCodes.match("ABC234", Set.of()));
    }

    // --- issue / consume lifecycle ---------------------------------------

    @Test
    void issueThenConsumeReturnsOwner() {
        LinkCodes codes = new LinkCodes();
        String code = codes.issue(ALICE, 0, 10 * MINUTE);
        assertEquals(ALICE, codes.consume(code, MINUTE));
    }

    @Test
    void consumeIsSingleUse() {
        LinkCodes codes = new LinkCodes();
        String code = codes.issue(ALICE, 0, 10 * MINUTE);
        assertEquals(ALICE, codes.consume(code, MINUTE));
        assertNull(codes.consume(code, MINUTE), "a consumed code must not match again");
        assertFalse(codes.hasPending());
    }

    @Test
    void expiredCodeIsNotConsumed() {
        LinkCodes codes = new LinkCodes();
        String code = codes.issue(ALICE, 0, 10 * MINUTE);
        assertNull(codes.consume(code, 11 * MINUTE), "expired code must not match");
    }

    @Test
    void pruneExpiredClearsStaleCodes() {
        LinkCodes codes = new LinkCodes();
        codes.issue(ALICE, 0, 10 * MINUTE);
        assertTrue(codes.hasPending());
        codes.pruneExpired(11 * MINUTE);
        assertFalse(codes.hasPending());
    }

    @Test
    void reissueReplacesPlayersPreviousCode() {
        LinkCodes codes = new LinkCodes();
        String first = codes.issue(ALICE, 0, 10 * MINUTE);
        String second = codes.issue(ALICE, 0, 10 * MINUTE);
        assertNotEquals(first, second);
        assertEquals(1, codes.pendingCount(), "one player keeps at most one pending code");
        assertNull(codes.consume(first, MINUTE), "the replaced code must be void");
        assertEquals(ALICE, codes.consume(second, MINUTE));
    }

    @Test
    void distinctPlayersKeepDistinctPendingCodes() {
        LinkCodes codes = new LinkCodes();
        String a = codes.issue(ALICE, 0, 10 * MINUTE);
        String b = codes.issue(BOB, 0, 10 * MINUTE);
        assertEquals(2, codes.pendingCount());
        assertEquals(BOB, codes.consume(b, MINUTE));
        assertEquals(ALICE, codes.consume(a, MINUTE));
    }
}
