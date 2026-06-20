package games.brennan.discordpresence.reincarnation;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The reflective bridge to PlayerMob's reincarnation seam ({@code games.brennan.playermob.compat.*}).
 *
 * <p><b>Why reflection.</b> Discord Presence ships standalone and has zero production dependencies;
 * PlayerMob is not published to any Maven DP's CI can reach, so DP cannot compile against the seam.
 * This class therefore references <b>no</b> {@code compat.*} type statically — every interaction is via
 * {@link Class#forName}, cached {@link Method}/{@link Constructor} handles, and a {@link Proxy} that
 * implements the runtime-only {@code ReincarnationSource} interface. Because nothing here names a
 * PlayerMob type in a signature, the class loads safely even when PlayerMob is absent (a failed
 * {@code forName} just leaves {@link #available()} {@code false}). Callers still gate construction on
 * {@code ModList.isLoaded("playermob")} to avoid the cost/log on a standalone launch.</p>
 *
 * <p>All handles are resolved once in the constructor against the exact v0.45.0 signatures; if any is
 * missing (a seam drift), {@link #available()} is {@code false} and the feature goes inert — it never
 * throws into gameplay.</p>
 */
final class PlayerMobSeam {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PKG = "games.brennan.playermob.compat.";

    /** DP-side delegate the registered source routes its synchronous, non-blocking {@code candidates()} to. */
    interface CandidateSupplier {
        /** Pre-built seam records (as opaque {@code Object}s) eligible for {@code query}; must not block. */
        List<Object> candidates(MinecraftServer server, ReincarnationQueryData query);
    }

    private final boolean available;
    private final int noCarriage;

    private final Class<?> sourceClass;
    private final Method registerMethod;     // ReincarnationSources.register(ReincarnationSource)
    private final Method recentDeathsMethod; // ReincarnationSources.recentDeaths(MinecraftServer, int)
    private final Constructor<?> recordCtor; // ReincarnationRecord canonical ctor (8 args)
    private final Method recSourceId, recKey, recPlayerId, recName, recCarriage, recSkinUrl, recSnapshot, recFriends;
    private final Method qMode, qCarriage, qPlayer, qOwner;

    PlayerMobSeam() {
        boolean ok = false;
        int noCar = -1;
        Class<?> srcC = null;
        Method regM = null, recentM = null;
        Constructor<?> ctor = null;
        Method aSrc = null, aKey = null, aPid = null, aName = null, aCar = null, aSkin = null, aSnap = null, aFri = null;
        Method mMode = null, mCar = null, mPlayer = null, mOwner = null;
        try {
            srcC = Class.forName(PKG + "ReincarnationSource");
            Class<?> sourcesC = Class.forName(PKG + "ReincarnationSources");
            Class<?> recordC = Class.forName(PKG + "ReincarnationRecord");
            Class<?> queryC = Class.forName(PKG + "ReincarnationQuery");
            Class<?> trainC = Class.forName(PKG + "TrainConfinement");

            regM = sourcesC.getMethod("register", srcC);
            recentM = sourcesC.getMethod("recentDeaths", MinecraftServer.class, int.class);
            ctor = recordC.getDeclaredConstructor(String.class, String.class, UUID.class, String.class,
                    int.class, String.class, CompoundTag.class, List.class);
            aSrc = recordC.getMethod("sourceId");
            aKey = recordC.getMethod("key");
            aPid = recordC.getMethod("playerId");
            aName = recordC.getMethod("name");
            aCar = recordC.getMethod("carriage");
            aSkin = recordC.getMethod("skinUrl");
            aSnap = recordC.getMethod("snapshot");
            aFri = recordC.getMethod("friendSnapshots");
            mMode = queryC.getMethod("mode");
            mCar = queryC.getMethod("carriage");
            mPlayer = queryC.getMethod("player");
            mOwner = queryC.getMethod("owner");
            noCar = trainC.getField("NO_CARRIAGE").getInt(null);
            ok = true;
        } catch (Throwable t) {
            LOGGER.warn("Discord Presence: PlayerMob reincarnation seam unavailable ({}); "
                    + "cross-world reincarnation disabled.", t.toString());
        }
        this.available = ok;
        this.noCarriage = noCar;
        this.sourceClass = srcC;
        this.registerMethod = regM;
        this.recentDeathsMethod = recentM;
        this.recordCtor = ctor;
        this.recSourceId = aSrc;
        this.recKey = aKey;
        this.recPlayerId = aPid;
        this.recName = aName;
        this.recCarriage = aCar;
        this.recSkinUrl = aSkin;
        this.recSnapshot = aSnap;
        this.recFriends = aFri;
        this.qMode = mMode;
        this.qCarriage = mCar;
        this.qPlayer = mPlayer;
        this.qOwner = mOwner;
    }

    /** Whether the seam resolved fully — PlayerMob is loaded and its v0.45.0 signatures are present. */
    boolean available() {
        return available;
    }

    /** PlayerMob's {@code TrainConfinement.NO_CARRIAGE} sentinel (a death not on a train). */
    int noCarriage() {
        return noCarriage;
    }

    /**
     * Build a {@code ReincarnationSource} {@link Proxy} that delegates {@code candidates()} to
     * {@code supplier} (and answers {@code remote()=true}, {@code recent()=empty}), and register it
     * with {@code ReincarnationSources}. Returns whether registration succeeded.
     */
    boolean registerSource(CandidateSupplier supplier) {
        if (!available) {
            return false;
        }
        try {
            Object proxy = Proxy.newProxyInstance(
                    sourceClass.getClassLoader(),
                    new Class<?>[]{sourceClass},
                    (p, method, args) -> dispatch(supplier, p, method, args));
            registerMethod.invoke(null, proxy);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Discord Presence: failed to register reincarnation source: {}", t.toString());
            return false;
        }
    }

    /** {@link java.lang.reflect.InvocationHandler} body for the source proxy. Never throws into PlayerMob. */
    private Object dispatch(CandidateSupplier supplier, Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "candidates":
                try {
                    MinecraftServer server = (MinecraftServer) args[0];
                    List<Object> out = supplier.candidates(server, readQuery(args[1]));
                    return out != null ? out : List.of();
                } catch (Throwable t) {
                    LOGGER.debug("Discord Presence: reincarnation candidates() failed: {}", t.toString());
                    return List.of();
                }
            case "remote":
                return Boolean.TRUE;
            case "recent":
                return List.of();
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "DiscordPresenceReincarnationSource";
            default:
                return null;
        }
    }

    /** Read a {@code ReincarnationQuery} into a DP DTO via its reflective accessors. */
    private ReincarnationQueryData readQuery(Object query) throws Exception {
        Object mode = qMode.invoke(query);
        String modeName = (mode instanceof Enum<?> e) ? e.name() : String.valueOf(mode);
        int carriage = (Integer) qCarriage.invoke(query);
        UUID player = (UUID) qPlayer.invoke(query);
        UUID owner = (UUID) qOwner.invoke(query);
        return new ReincarnationQueryData(modeName, carriage, player, owner);
    }

    /**
     * PlayerMob's recent death log across all sources, as DP DTOs. <b>Server-thread only</b> — the
     * underlying registry touches unsynchronised global state. Empty (logged) on any failure.
     */
    List<ReincarnationRecordData> recentDeaths(MinecraftServer server, int limit) {
        if (!available) {
            return List.of();
        }
        try {
            Object result = recentDeathsMethod.invoke(null, server, limit);
            if (!(result instanceof List<?> list)) {
                return List.of();
            }
            List<ReincarnationRecordData> out = new ArrayList<>(list.size());
            for (Object rec : list) {
                ReincarnationRecordData dto = readRecord(rec);
                if (dto != null) {
                    out.add(dto);
                }
            }
            return out;
        } catch (Throwable t) {
            LOGGER.debug("Discord Presence: reincarnation recentDeaths() failed: {}", t.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private ReincarnationRecordData readRecord(Object rec) {
        try {
            return new ReincarnationRecordData(
                    (String) recSourceId.invoke(rec),
                    (String) recKey.invoke(rec),
                    (UUID) recPlayerId.invoke(rec),
                    (String) recName.invoke(rec),
                    (Integer) recCarriage.invoke(rec),
                    (String) recSkinUrl.invoke(rec),
                    (CompoundTag) recSnapshot.invoke(rec),
                    (List<CompoundTag>) recFriends.invoke(rec));
        } catch (Throwable t) {
            LOGGER.debug("Discord Presence: failed to read reincarnation record: {}", t.toString());
            return null;
        }
    }

    /**
     * Construct a real seam {@code ReincarnationRecord} (returned as an opaque {@code Object}) from a DP
     * DTO, for the inbound cache. Runs off the server thread (at fetch-ingest time), never inside
     * {@code candidates()}. {@code null} on failure.
     */
    Object buildRecord(ReincarnationRecordData d) {
        if (!available || d == null) {
            return null;
        }
        try {
            List<CompoundTag> friends = d.friendSnapshots() != null ? d.friendSnapshots() : List.of();
            return recordCtor.newInstance(d.sourceId(), d.key(), d.playerId(), d.name(),
                    d.carriage(), d.skinUrl(), d.snapshot(), friends);
        } catch (Throwable t) {
            LOGGER.debug("Discord Presence: failed to build reincarnation record: {}", t.toString());
            return null;
        }
    }
}
