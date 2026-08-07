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
 * <p><b>The base64 pair is deliberately asymmetric: write standard, read either.</b> {@link #encode}
 * always emits the standard alphabet ({@code A-Za-z0-9+/}) — the strings live in a JSON body, where
 * {@code + / =} are perfectly legal, and every DP build already in players' hands reads standard only,
 * so emitting URL-safe would make new records undecodable by older clients. {@link #decodeBase64}
 * nonetheless accepts the URL-safe alphabet ({@code A-Za-z0-9-_}) as a fallback, because the relay
 * pool is shared and stores whatever it is given: a record written in the other alphabet by some
 * other producer is recoverable rather than lost. Do not "tidy" this into a matched pair.</p>
 *
 * <p>Best-effort: a null/blank/corrupt input yields {@code null} (a dropped record) rather than
 * throwing — DP's Discord I/O never blocks or breaks gameplay.</p>
 */
final class SnapshotCodec {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Characters of a failed string echoed into the log — enough to spot a placeholder, not player data. */
    private static final int PREVIEW_CHARS = 8;

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

    /**
     * Base64-decode {@code s} in whichever alphabet it is written in: the standard one first, then
     * URL-safe. Returns {@code null} when it is neither (including a string that mixes the two, which
     * is corruption rather than a foreign alphabet). Pure JDK — no Minecraft — so it is unit-testable
     * outside the game. Missing {@code =} padding is tolerated by both decoders.
     *
     * <p>Note {@link Base64#getMimeDecoder()} is deliberately NOT used as a catch-all: it silently
     * skips characters outside its alphabet, so a URL-safe string would decode to <em>wrong bytes</em>
     * instead of failing.</p>
     */
    static byte[] decodeBase64(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(s);
            } catch (IllegalArgumentException notUrlSafe) {
                return null;
            }
        }
    }

    /** Inverse of {@link #encode}: opaque relay string back to a {@link CompoundTag}, or {@code null} on failure. */
    static CompoundTag decode(String encoded) {
        return decode(encoded, null);
    }

    /**
     * Inverse of {@link #encode}, naming the record in any failure log. {@code context} identifies which
     * relay record (and which field of it) the string came from, so a poisoned pool entry can actually be
     * found — without it every failure logs an identical, untraceable line.
     */
    static CompoundTag decode(String encoded, String context) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes = decodeBase64(encoded);
        if (bytes == null) {
            LOGGER.warn("Discord Presence: reincarnation snapshot decode failed ({}): not base64 in either "
                    + "alphabet — {} chars starting \"{}\"", where(context), encoded.length(), preview(encoded));
            return null;
        }
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            LOGGER.warn("Discord Presence: reincarnation snapshot decode failed ({}): {} — {} chars starting \"{}\"",
                    where(context), e.toString(), encoded.length(), preview(encoded));
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

    /** Decode every friend string, dropping any that fail to decode. Never {@code null}. */
    static List<CompoundTag> decodeAll(List<String> encoded) {
        return decodeAll(encoded, null);
    }

    /** As {@link #decodeAll(List)}, labelling each entry with its owning record for failure logs. */
    static List<CompoundTag> decodeAll(List<String> encoded, String context) {
        List<CompoundTag> out = new ArrayList<>();
        if (encoded == null) {
            return out;
        }
        for (int i = 0; i < encoded.size(); i++) {
            CompoundTag tag = decode(encoded.get(i), friendContext(context, i));
            if (tag != null) {
                out.add(tag);
            }
        }
        return out;
    }

    /** Label a friend entry by its owning record and index, e.g. {@code "record 41 friend 2"}. */
    private static String friendContext(String context, int index) {
        return where(context) + " friend " + index;
    }

    /** A human-readable subject for a failure log — the caller's label, or a stand-in when it has none. */
    private static String where(String context) {
        return (context == null || context.isBlank()) ? "unlabelled record" : context;
    }

    /** The first few characters of a failed string: a real snapshot starts {@code H4sI} (the gzip magic). */
    private static String preview(String s) {
        return s.length() <= PREVIEW_CHARS ? s : s.substring(0, PREVIEW_CHARS) + "…";
    }
}
