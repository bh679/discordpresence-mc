package games.brennan.discordpresence.compat;

import org.jetbrains.annotations.Nullable;

/**
 * Everything DiscordPresence knows about one inbound human Discord message, handed to host-mod seams
 * ({@link DiscordCommandHooks.ContextHandler}, {@link DiscordChatHooks.InboundDeliverer}).
 *
 * @param messageId        the Discord message id (snowflake), or {@code null} when the transport
 *                         didn't carry it (e.g. an older relay)
 * @param channelId        the channel (or thread) id the message arrived in, or {@code null}
 * @param authorId         the Discord user id (may be empty if the transport didn't carry it)
 * @param authorName       the author's display name
 * @param content          the raw message content
 * @param targetPlayerUuid the Minecraft UUID (as a string) of the player whose thread this message
 *                         belongs to, or {@code null} when it isn't anchored to a player's thread
 */
public record InboundContext(
        @Nullable String messageId,
        @Nullable String channelId,
        String authorId,
        String authorName,
        String content,
        @Nullable String targetPlayerUuid) {
}
