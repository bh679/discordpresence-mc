package games.brennan.discordpresence.reincarnation;

import java.util.UUID;

/**
 * DP-side mirror of PlayerMob's {@code ReincarnationQuery}, read by {@link PlayerMobSeam} from the
 * real query via reflection so DP never names a {@code compat.*} type. {@code mode} is the query's
 * {@code Mode} enum name ({@code "CARRIAGE"} / {@code "PLAYER"} / {@code "ANY"}); {@code owner} is the
 * nearby live player (may be {@code null}) — the one DP pre-fetches a band for and excludes from the
 * relay query so a remote echo is never the player themselves.
 *
 * @param mode     the query mode enum name
 * @param carriage the Dungeon-Train carriage band centre (meaningful for {@code CARRIAGE} mode)
 * @param player   a specific player to match (for {@code PLAYER} mode), else {@code null}
 * @param owner    the nearby live player whose session gates reuse, or {@code null}
 */
public record ReincarnationQueryData(String mode, int carriage, UUID player, UUID owner) {

    /** Whether this is a carriage-band query (the Dungeon-Train spawn path DP pre-fetches for). */
    public boolean isCarriage() {
        return "CARRIAGE".equals(mode);
    }
}
