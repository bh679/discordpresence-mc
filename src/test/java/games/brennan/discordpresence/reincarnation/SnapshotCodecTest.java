package games.brennan.discordpresence.reincarnation;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base64 layer of {@link SnapshotCodec} — pure JDK, so it runs without a Minecraft environment.
 *
 * <p>Regression cover for the production failure {@code IllegalArgumentException: Illegal base64
 * character 5f}: {@code 0x5f} is {@code _}, which is in the URL-safe alphabet but not the standard
 * one. The relay pool is shared and stores snapshot strings verbatim, so a record written in the
 * other alphabet must be readable rather than dropped.</p>
 */
class SnapshotCodecTest {

    /**
     * Three bytes chosen so the encoding exercises both alphabet-specific characters: the sextets are
     * 62 and 63, i.e. {@code +} and {@code /} in standard, {@code -} and {@code _} in URL-safe.
     */
    private static final byte[] BOTH_SPECIALS = {(byte) 0xFB, (byte) 0xF0, 0x00};

    @Test
    void fixtureActuallyExercisesBothAlphabets() {
        String standard = Base64.getEncoder().encodeToString(BOTH_SPECIALS);
        String urlSafe = Base64.getUrlEncoder().encodeToString(BOTH_SPECIALS);
        assertEquals("+/AA", standard);
        assertEquals("-_AA", urlSafe);
        assertTrue(urlSafe.indexOf('_') >= 0, "fixture must contain the 0x5f character from the bug report");
    }

    @Test
    void decodesStandardAlphabet() {
        byte[] payload = "a dungeon train snapshot".getBytes();
        assertArrayEquals(payload, SnapshotCodec.decodeBase64(Base64.getEncoder().encodeToString(payload)));
    }

    @Test
    void decodesUrlSafeAlphabet() {
        // The bug: this input is what made the standard-only decoder throw on 0x5f.
        assertArrayEquals(BOTH_SPECIALS, SnapshotCodec.decodeBase64(Base64.getUrlEncoder().encodeToString(BOTH_SPECIALS)));
    }

    @Test
    void bothAlphabetsYieldTheSameBytes() {
        byte[] fromStandard = SnapshotCodec.decodeBase64(Base64.getEncoder().encodeToString(BOTH_SPECIALS));
        byte[] fromUrlSafe = SnapshotCodec.decodeBase64(Base64.getUrlEncoder().encodeToString(BOTH_SPECIALS));
        assertArrayEquals(fromStandard, fromUrlSafe);
        assertArrayEquals(BOTH_SPECIALS, fromStandard);
    }

    @Test
    void toleratesMissingPadding() {
        byte[] payload = {0x41};
        assertArrayEquals(payload, SnapshotCodec.decodeBase64("QQ"));   // "QQ==" unpadded
        assertArrayEquals(payload, SnapshotCodec.decodeBase64("QQ=="));
    }

    @Test
    void nullBlankAndGarbageDecodeToNull() {
        assertNull(SnapshotCodec.decodeBase64(null));
        assertNull(SnapshotCodec.decodeBase64(""));
        assertNull(SnapshotCodec.decodeBase64("   "));
        assertNull(SnapshotCodec.decodeBase64("!!!!"));
    }

    @Test
    void mixedAlphabetsDecodeToNull() {
        // Corruption, not a foreign alphabet: neither decoder accepts both '+' and '_'.
        assertNull(SnapshotCodec.decodeBase64("+_AA"));
    }

    @Test
    void mimeStyleWhitespaceIsRejectedRatherThanSilentlyStripped() {
        // Deliberate: getMimeDecoder() would skip the stray characters and hand back WRONG bytes.
        assertNull(SnapshotCodec.decodeBase64("QQ ==\n"));
    }

    @Test
    void decodeOfNullOrBlankIsNullWithoutTouchingNbt() {
        assertNull(SnapshotCodec.decode(null));
        assertNull(SnapshotCodec.decode(""));
        assertNull(SnapshotCodec.decode("   ", "record 7"));
    }

    @Test
    void decodeAllOfNullIsEmpty() {
        assertNotNull(SnapshotCodec.decodeAll(null));
        assertTrue(SnapshotCodec.decodeAll(null).isEmpty());
    }
}
