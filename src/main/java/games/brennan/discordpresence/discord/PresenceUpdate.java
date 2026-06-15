package games.brennan.discordpresence.discord;

/**
 * The handful of fields from a Discord {@code PRESENCE_UPDATE} dispatch (and each
 * entry of a {@code GUILD_CREATE} {@code presences} snapshot) that the presence
 * tracker needs. Immutable value carrier parsed by
 * {@link GatewayPayloads#presence} / {@link GatewayPayloads#guildCreatePresences}.
 *
 * <p>{@code userId} is the Discord user id the presence belongs to ({@code d.user.id}).
 * {@code status} is Discord's status string — {@code "online"}, {@code "idle"},
 * {@code "dnd"} or {@code "offline"} (a user set to "invisible" appears as
 * {@code "offline"}). Both may be {@code null}/blank on a malformed frame, so the
 * accessors are null-tolerant and a bad frame can never throw into the gateway loop.</p>
 */
record PresenceUpdate(String userId, String status) {

    /** Discord's "this user is offline" status; a frozen-last-seen anchor, not "seen online". */
    static final String OFFLINE = "offline";

    /**
     * True when the status counts as "seen online" — any non-blank status other than
     * {@code offline}. {@code online}, {@code idle} and {@code dnd} all count (the user
     * is connected); {@code offline} / blank / null do not.
     */
    boolean isOnline() {
        return status != null && !status.isBlank() && !OFFLINE.equals(status);
    }

    /** Whether this carries a usable user id (a blank id can't be tracked or recorded). */
    boolean hasUserId() {
        return userId != null && !userId.isBlank();
    }
}
