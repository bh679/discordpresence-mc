package games.brennan.discordpresence.discord;

/**
 * The handful of fields from a Discord {@code MESSAGE_CREATE} dispatch that the
 * relay needs to decide routing. Immutable value carrier parsed by
 * {@link GatewayPayloads#message}.
 *
 * <p>{@code referencedMessageId} is the replied-to message id (null when the
 * message is not a reply). {@code channelId} doubles as the thread id — Discord
 * sets a message-started thread's id equal to its source message id — so a
 * reply <i>and</i> a thread message both resolve against the same
 * {@link PlayerMessageIndex} lookup.</p>
 */
record InboundMessage(
        String authorId,
        String authorName,
        String content,
        boolean bot,
        boolean hasWebhookId,
        String channelId,
        String referencedMessageId) {

    /**
     * True when the message came from the bot itself or any webhook — i.e. one of
     * <i>our</i> own posts (join message, relayed chat line, reactions' bot). These
     * must never be relayed back in-game, or the bridge would loop.
     */
    boolean isOwnOrBot() {
        return bot || hasWebhookId;
    }
}
