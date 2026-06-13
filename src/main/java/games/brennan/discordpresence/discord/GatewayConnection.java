package games.brennan.discordpresence.discord;

/**
 * A startable/stoppable inbound gateway connection — either {@link DiscordGateway} (direct to
 * Discord, using the bot token) or {@link RelayGateway} (relay-bridged, token-less). Lets
 * {@link DiscordService} hold whichever the current mode selected and stop it uniformly.
 */
interface GatewayConnection {
    void start();

    void stop();
}
