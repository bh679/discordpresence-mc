package games.brennan.discordpresence.discord;

/**
 * Immutable reference to a posted Discord message — enough to react to or edit
 * it later. {@code channelId} comes back in the webhook's {@code ?wait=true}
 * response alongside {@code messageId}, so no separate channel config is needed.
 */
record DiscordMessageRef(String channelId, String messageId) {}
