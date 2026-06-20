package games.brennan.discordpresence.reincarnation;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.UUID;

/**
 * DP-side mirror of PlayerMob's {@code games.brennan.playermob.compat.ReincarnationRecord} — one
 * past life crossing the reincarnation seam. This is the <b>boundary type</b>: the reflective
 * {@link PlayerMobSeam} translates the real PlayerMob record to/from this DTO so that no part of
 * Discord Presence ever names a {@code compat.*} type in a signature (which would break standalone
 * class-loading when PlayerMob is absent — see {@link PlayerMobSeam}).
 *
 * <p>The {@code snapshot} (and each {@code friendSnapshots} entry) is the opaque PlayerMob entity
 * {@link CompoundTag}; {@link SnapshotCodec} serialises it to/from the relay's opaque string. DP
 * never interprets the NBT — it only shuttles it verbatim.</p>
 *
 * @param sourceId        the source mod's id ({@code "playermob"} for PlayerMob's own death log,
 *                        {@code "discordpresence"} for lives DP imported from the relay)
 * @param key             the source's own stable id (a death sequence number, or the relay record id)
 * @param playerId        the original player this life belonged to
 * @param name            the player's display name at death
 * @param carriage        Dungeon-Train room index, or PlayerMob's {@code NO_CARRIAGE} sentinel
 * @param skinUrl         captured Mojang skin texture URL, or {@code ""}
 * @param snapshot        the opaque PlayerMob entity NBT to embody this life
 * @param friendSnapshots snapshots of the PlayerMobs that loved this player at death (may be empty)
 */
public record ReincarnationRecordData(String sourceId, String key, UUID playerId, String name,
                                      int carriage, String skinUrl, CompoundTag snapshot,
                                      List<CompoundTag> friendSnapshots) {
}
