package games.brennan.discordpresence.discord;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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

    /**
     * Separate single-thread daemon scheduler for periodic work (the account-link
     * channel poller in {@code LinkService}). Kept off {@link #EXECUTOR} so the
     * poll cadence never delays one-shot REST calls; the HTTP itself still runs
     * through {@link #CLIENT}.
     */
    static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordPresence-Poll");
        t.setDaemon(true);
        return t;
    });

    private DiscordHttp() {}
}
