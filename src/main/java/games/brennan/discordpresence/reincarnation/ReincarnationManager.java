package games.brennan.discordpresence.reincarnation;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.DiscordHttp;
import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.PostPayload;
import games.brennan.discordpresence.reincarnation.RelayReincarnationClient.RelayRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the cross-world reincarnation bridge between PlayerMob's {@code ReincarnationSource}
 * seam and the relay's reincarnation pool. Owned by {@code DiscordService}; started on server start
 * and stopped on server stop.
 *
 * <p><b>Outbound</b> (export local deaths): a periodic tick reads PlayerMob's death log on the server
 * thread ({@link PlayerMobSeam#recentDeaths}), then off-thread filters to PlayerMob's own deaths,
 * skips ones already sent ({@link ReincarnationOutbox}), and POSTs each new one to the relay.</p>
 *
 * <p><b>Inbound</b> (import remote echoes): the same tick (re)fetches a small band of remote lives for
 * each nearby player and caches them ({@link ReincarnationCache}) as pre-built seam records, so the
 * {@link #candidates} callback — invoked on the server thread during entity spawn — answers
 * synchronously from cache with no I/O.</p>
 *
 * <p>The bridge only runs in relay-mode with PlayerMob present and the network permitted; otherwise it
 * logs why it stayed inert and does nothing. Everything is best-effort and never throws into gameplay.</p>
 */
public final class ReincarnationManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PLAYERMOB_MOD_ID = "playermob";
    private static final String PLAYERMOB_SOURCE_ID = "playermob";
    /** Our own source id for lives imported from the relay (keys the seam's "already met" de-dup). */
    private static final String DP_SOURCE_ID = "discordpresence";
    private static final String OUTBOX_FILE = "discordpresence-reincarnation.json";

    private static final long INTERVAL_SECONDS = 60;
    private static final int OUTBOUND_LIMIT = 50;          // recentDeaths scrape window (≪ outbox cap)
    private static final int INBOUND_RADIUS = 30;          // matches the relay/PlayerMob carriage band
    private static final int INBOUND_LIMIT = 5;            // matches MAX_CANDIDATES_PER_BAND
    private static final long REFRESH_COOLDOWN_MILLIS = 30_000;
    private static final int BAND_DRIFT = INBOUND_RADIUS / 2; // re-fetch before the player leaves the band

    private final ReincarnationCache cache = new ReincarnationCache();
    private final ReincarnationOutbox outbox = new ReincarnationOutbox();

    private volatile PlayerMobSeam seam;
    private volatile boolean registered;
    private volatile MinecraftServer server;
    private volatile ScheduledFuture<?> task;

    /**
     * Start the bridge for {@code server} if it's enabled, PlayerMob is loaded, DP is in relay-mode, and
     * the network is permitted ({@code networkAllowed} — the same gate {@code DiscordService} uses).
     * Registers the source once per process and (re)starts the periodic tick. Logs why it stayed inert
     * otherwise. Safe to call on every server start.
     */
    public void start(MinecraftServer startedServer, boolean networkAllowed) {
        this.server = startedServer;
        boolean bridgeEnabled = DiscordPresenceConfig.isReincarnationBridgeEnabled();
        boolean playerMobLoaded = ModList.get().isLoaded(PLAYERMOB_MOD_ID);
        boolean relayMode = DiscordPresenceConfig.isRelayMode();
        if (!shouldBridge(bridgeEnabled, playerMobLoaded, relayMode, networkAllowed)) {
            LOGGER.info("Discord Presence: cross-world reincarnation inert ({}).",
                    inertReason(bridgeEnabled, playerMobLoaded, relayMode, networkAllowed));
            return;
        }
        if (seam == null) {
            seam = new PlayerMobSeam();
        }
        if (!seam.available()) {
            LOGGER.info("Discord Presence: cross-world reincarnation inert (PlayerMob seam unavailable).");
            return;
        }
        if (!registered) {
            if (!seam.registerSource(this::candidates)) {
                LOGGER.warn("Discord Presence: cross-world reincarnation inert (source registration failed).");
                return;
            }
            registered = true;
        }
        outbox.load(FMLPaths.CONFIGDIR.get().resolve(OUTBOX_FILE));
        task = DiscordHttp.SCHEDULER.scheduleAtFixedRate(
                this::tick, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("Discord Presence: cross-world reincarnation active (relay pool, polling every {}s).",
                INTERVAL_SECONDS);
    }

    /** Stop the periodic tick and drop the inbound cache (the source stays registered; the outbox is on disk). */
    public void stop() {
        ScheduledFuture<?> t = task;
        task = null;
        if (t != null) {
            t.cancel(false);
        }
        cache.clear();
        server = null;
    }

    /** All four gates must hold for the bridge to run. Pure → unit-tested. */
    static boolean shouldBridge(boolean bridgeEnabled, boolean playerMobLoaded, boolean relayMode,
                                boolean networkAllowed) {
        return bridgeEnabled && playerMobLoaded && relayMode && networkAllowed;
    }

    /** The first failing gate, for an inert-reason log line. Pure → unit-tested. */
    static String inertReason(boolean bridgeEnabled, boolean playerMobLoaded, boolean relayMode,
                              boolean networkAllowed) {
        if (!bridgeEnabled) {
            return "disabled by config";
        }
        if (!playerMobLoaded) {
            return "PlayerMob not installed";
        }
        if (!relayMode) {
            return "not in relay-mode — no cross-world pool";
        }
        if (!networkAllowed) {
            return "network not permitted";
        }
        return "active";
    }

    // --- the seam callback (server thread, synchronous, no I/O) ---------------

    /**
     * PlayerMob's {@code ReincarnationSource.candidates} delegate. Runs on the server thread during
     * entity spawn, so it only reads the cache and records where the player is (to drive prefetch).
     * Only the carriage-band path (the Dungeon-Train spawn case) is served — DP doesn't pre-fetch the
     * player/any modes — and only when there's a nearby owner to key the cache by.
     */
    List<Object> candidates(MinecraftServer ignoredServer, ReincarnationQueryData query) {
        UUID owner = query.owner();
        if (!query.isCarriage() || owner == null) {
            return List.of();
        }
        cache.observe(owner, query.carriage());
        return cache.candidatesFor(owner);
    }

    // --- the periodic tick (off the server thread) ---------------------------

    private void tick() {
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        String base = DiscordPresenceConfig.getRelayBaseUrl();
        if (base.isBlank()) {
            return; // relay-mode dropped mid-session — nothing to talk to
        }
        outboundTick(srv, base);
        inboundTick(base);
    }

    /**
     * Read PlayerMob's recent deaths on the server thread (the seam is server-thread-only), then hand
     * the snapshot off to the HTTP executor to filter, encode, and POST the new ones.
     */
    private void outboundTick(MinecraftServer srv, String base) {
        PlayerMobSeam s = seam;
        if (s == null) {
            return;
        }
        try {
            srv.execute(() -> {
                List<ReincarnationRecordData> recents = s.recentDeaths(srv, OUTBOUND_LIMIT);
                if (!recents.isEmpty()) {
                    DiscordHttp.EXECUTOR.execute(() -> postNewDeaths(base, s, recents));
                }
            });
        } catch (Exception e) {
            LOGGER.debug("Discord Presence: reincarnation outbound tick skipped: {}", e.toString());
        }
    }

    /** Off-thread: post each not-yet-sent PlayerMob death to the relay (claimed once via the outbox). */
    private void postNewDeaths(String base, PlayerMobSeam s, List<ReincarnationRecordData> recents) {
        int noCarriage = s.noCarriage();
        for (ReincarnationRecordData d : recents) {
            if (!PLAYERMOB_SOURCE_ID.equals(d.sourceId())) {
                continue; // only PlayerMob's own deaths — never re-post lives we imported (no loop)
            }
            if (!outbox.shouldPost(d.key())) {
                continue;
            }
            String snapshot = SnapshotCodec.encode(d.snapshot());
            if (snapshot == null) {
                continue; // can't reincarnate without a snapshot — don't post a useless record
            }
            outbox.markPosted(d.key()); // optimistic claim: at-most-once, best-effort
            Integer carriage = d.carriage() == noCarriage ? null : d.carriage();
            String playerId = d.playerId() != null ? d.playerId().toString() : null;
            List<String> friends = SnapshotCodec.encodeAll(d.friendSnapshots());
            RelayReincarnationClient.post(base,
                    new PostPayload(snapshot, d.name(), playerId, carriage, d.skinUrl(), friends));
        }
    }

    /** (Re)fetch a band for each nearby player that needs one; cache the built records on completion. */
    private void inboundTick(String base) {
        PlayerMobSeam s = seam;
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<UUID, Integer> toFetch = cache.bandsToFetch(now, REFRESH_COOLDOWN_MILLIS, BAND_DRIFT);
        for (Map.Entry<UUID, Integer> e : toFetch.entrySet()) {
            UUID owner = e.getKey();
            int carriage = e.getValue();
            if (!cache.tryBeginFetch(owner)) {
                continue;
            }
            Integer carriageParam = carriage == s.noCarriage() ? null : carriage;
            RelayReincarnationClient.fetch(base, carriageParam, INBOUND_RADIUS, owner.toString(), INBOUND_LIMIT)
                    .whenComplete((records, err) -> {
                        try {
                            List<Object> built = records == null ? List.of() : buildRecords(s, records);
                            // Store even an empty band so the cooldown applies (don't re-poll every tick).
                            cache.store(owner, carriage, built, System.currentTimeMillis());
                        } catch (Exception ex) {
                            LOGGER.debug("Discord Presence: reincarnation inbound store failed: {}", ex.toString());
                        } finally {
                            cache.endFetch(owner);
                        }
                    });
        }
    }

    /** Decode each relay record and build a real seam record; drop any that fail to decode/build. */
    private List<Object> buildRecords(PlayerMobSeam s, List<RelayRecord> records) {
        List<Object> out = new ArrayList<>(records.size());
        for (RelayRecord r : records) {
            CompoundTag snapshot = SnapshotCodec.decode(r.snapshot());
            if (snapshot == null) {
                continue;
            }
            UUID playerId = parseUuid(r.playerId());
            int carriage = r.carriage() != null ? r.carriage() : s.noCarriage();
            List<CompoundTag> friends = SnapshotCodec.decodeAll(r.friends());
            ReincarnationRecordData dto = new ReincarnationRecordData(
                    DP_SOURCE_ID,
                    r.id() != null ? r.id() : "",
                    playerId,
                    r.name() != null ? r.name() : "",
                    carriage,
                    r.skinUrl() != null ? r.skinUrl() : "",
                    snapshot,
                    friends);
            Object built = s.buildRecord(dto);
            if (built != null) {
                out.add(built);
            }
        }
        return out;
    }

    /** A UUID from the relay's playerId string, or {@code null} when blank/invalid (an unattributed life). */
    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
