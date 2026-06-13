package games.brennan.discordpresence.discord;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Shared single-thread daemon executor + {@link HttpClient} for every Discord
 * REST call, plus a dedicated scheduler for the gateway. Mirrors Dungeon Train's
 * {@code GitHubLatestReleaseFetcher}: serialised daemon workers that never block
 * JVM shutdown, so a hanging request can't keep the server process alive.
 */
final class DiscordHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-HTTP");
        t.setDaemon(true);
        return t;
    });

    /**
     * Dedicated daemon scheduler for the gateway's heartbeat ticks and reconnect
     * backoff ({@link DiscordGateway}). Kept separate from {@link #EXECUTOR} so
     * heartbeat timing can never be starved behind in-flight HTTP/WebSocket
     * callbacks (which would trip false zombie-connection detection).
     */
    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-Gateway");
        t.setDaemon(true);
        return t;
    });

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .executor(EXECUTOR)
            .build();

    private DiscordHttp() {}
}
