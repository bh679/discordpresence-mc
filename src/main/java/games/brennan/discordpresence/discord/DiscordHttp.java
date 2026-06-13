package games.brennan.discordpresence.discord;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared single-thread daemon executor + {@link HttpClient} for every Discord
 * REST call. Mirrors Dungeon Train's {@code GitHubLatestReleaseFetcher}: one
 * serialised worker that never blocks JVM shutdown (daemon thread), so a
 * hanging request can't keep the server process alive.
 */
final class DiscordHttp {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-HTTP");
        t.setDaemon(true);
        return t;
    });

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .executor(EXECUTOR)
            .build();

    private DiscordHttp() {}
}
