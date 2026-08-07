package games.brennan.discordpresence.reincarnation;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Full {@link CompoundTag} → string → {@link CompoundTag} round trip, including the same tag re-wrapped
 * in the URL-safe alphabet — the exact input shape that produced {@code Illegal base64 character 5f}
 * in production. Kept apart from {@link SnapshotCodecTest} because this one needs Minecraft's NBT
 * classes to load, while the base64 layer is pure JDK.
 */
class SnapshotCodecNbtRoundTripTest {

    private static CompoundTag sampleSnapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "playermob:player_mob");
        tag.putInt("Carriage", 41);
        tag.putFloat("Health", 17.5f);
        CompoundTag nested = new CompoundTag();
        nested.putString("Name", "a past life");
        tag.put("CustomData", nested);
        return tag;
    }

    @Test
    void roundTripsThroughTheStandardAlphabet() {
        CompoundTag original = sampleSnapshot();
        String encoded = SnapshotCodec.encode(original);
        assertNotNull(encoded);
        assertEquals(original, SnapshotCodec.decode(encoded));
    }

    @Test
    void decodesTheSameSnapshotRewrittenUrlSafe() {
        CompoundTag original = sampleSnapshot();
        byte[] gzipNbt = Base64.getDecoder().decode(SnapshotCodec.encode(original));
        String urlSafe = Base64.getUrlEncoder().encodeToString(gzipNbt);

        assertEquals(original, SnapshotCodec.decode(urlSafe, "record 41"));
    }

    @Test
    void decodeAllKeepsTheGoodEntriesAndDropsTheBad() {
        String good = SnapshotCodec.encode(sampleSnapshot());
        List<CompoundTag> decoded = SnapshotCodec.decodeAll(List.of(good, "not base64 at all!"), "record 41");

        assertEquals(1, decoded.size());
        assertEquals(sampleSnapshot(), decoded.get(0));
    }
}
