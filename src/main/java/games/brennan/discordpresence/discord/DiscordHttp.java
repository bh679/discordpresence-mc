package games.brennan.discordpresence.discord;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Shared daemon HTTP executor + {@link HttpClient} for every Discord REST call and
 * the gateway WebSocket, plus a dedicated scheduler for gateway heartbeats. Daemon
 * threads, so a hanging request can never keep the JVM alive. The HTTP executor is a
 * small cached pool — NOT a single thread — because the JDK {@link java.net.http.WebSocket}
 * dispatches its onOpen/onText listener callbacks on this executor; one thread starves
 * them and the gateway hangs forever "awaiting HELLO".
 */
final class DiscordHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
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
