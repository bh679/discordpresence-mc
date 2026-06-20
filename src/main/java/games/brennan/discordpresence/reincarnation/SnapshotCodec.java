package games.brennan.discordpresence.reincarnation;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Lossless {@link CompoundTag} ↔ opaque-string codec for the reincarnation relay. The relay stores
 * and returns the snapshot string verbatim (it never parses it), so DP serialises a PlayerMob entity
 * snapshot to a string on the way out and back to a {@link CompoundTag} on the way in.
 *
 * <p>Encoding is gzip-compressed NBT ({@link NbtIo#writeCompressed}) base64-wrapped so it survives
 * a JSON string field. The {@link NbtIo#readCompressed} overload taking an {@link NbtAccounter} is
 * the 1.21.1 signature (the no-accounter overload was removed in 1.20.2); {@link NbtAccounter#unlimitedHeap()}
 * imposes no size cap, matching PlayerMob's own round-trip.</p>
 *
 * <p>Best-effort: a null/blank/corrupt input yields {@code null} (a dropped record) rather than
 * throwing — DP's Discord I/O never blocks or breaks gameplay.</p>
 */
final class SnapshotCodec {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SnapshotCodec() {}

    /** Gzip-NBT + base64 a snapshot tag to its opaque relay string, or {@code null} on failure. */
    static String encode(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: reincarnation snapshot encode failed: {}", e.toString());
            return null;
        }
    }

    /** Inverse of {@link #encode}: opaque relay string back to a {@link CompoundTag}, or {@code null} on failure. */
    static CompoundTag decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: reincarnation snapshot decode failed: {}", e.toString());
            return null;
        }
    }

    /** Encode every friend snapshot, silently dropping any that fail to encode. Never {@code null}. */
    static List<String> encodeAll(List<CompoundTag> tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        for (CompoundTag tag : tags) {
            String s = encode(tag);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** Decode every friend string, silently dropping any that fail to decode. Never {@code null}. */
    static List<CompoundTag> decodeAll(List<String> encoded) {
        List<CompoundTag> out = new ArrayList<>();
        if (encoded == null) {
            return out;
        }
        for (String s : encoded) {
            CompoundTag tag = decode(s);
            if (tag != null) {
                out.add(tag);
            }
        }
        return out;
    }
}
