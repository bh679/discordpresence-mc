package games.brennan.discordpresence.discord;

/**
 * One labelled field on a death-report embed (e.g. {@code "Distance" → "28 m"}).
 *
 * <p>Public so a bundling mod (e.g. Dungeon Train) can build its own run-summary
 * fields and pass them to {@link DiscordService#postDeathReport}. The caller is
 * responsible for formatting the value (units, etc.) — DiscordPresence only
 * renders {@code name}/{@code value} pairs.</p>
 *
 * @param name  the field label (Discord caps at 256 chars)
 * @param value the field value (Discord caps at 1024 chars)
 */
public record DeathField(String name, String value) {}
