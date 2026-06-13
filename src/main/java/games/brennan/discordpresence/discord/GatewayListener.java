package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

/**
 * {@link WebSocket.Listener} that marshals raw frames to {@link DiscordGateway}.
 * Two jobs only: reassemble fragmented text frames into whole JSON payloads, and
 * drive flow control by re-requesting after every callback (the JDK WebSocket
 * delivers nothing until {@code request(n)} is called). All protocol logic lives
 * in {@code DiscordGateway}.
 *
 * <p>The WebSocket invokes these callbacks serially (never re-entrant until the
 * returned stage completes), so the unsynchronised {@link StringBuilder} buffer
 * is safe.</p>
 */
final class GatewayListener implements WebSocket.Listener {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final DiscordGateway gateway;
    private final StringBuilder buffer = new StringBuilder();

    GatewayListener(DiscordGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        LOGGER.debug("Discord gateway WS onOpen — requesting first frame.");
        gateway.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        LOGGER.debug("Discord gateway WS onText (len={}, last={}).", data.length(), last);
        buffer.append(data);
        if (last) {
            String complete = buffer.toString();
            buffer.setLength(0);
            try {
                gateway.onText(complete);
            } catch (Exception e) {
                // Never let a handler error break the receive loop.
                gateway.onHandlerError(e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        gateway.onClose(statusCode, reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        gateway.onError(error);
    }
}
