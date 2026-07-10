package games.brennan.discordpresence.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-only seam a bundling mod (e.g. Dungeon Train) can register to observe every survey
 * answer the local player submits from {@link SurveyScreen}. DP fires it with the answered
 * question entry and the submitted score after sending the answer to the server; DP itself does
 * nothing with the callback — it exists so the bundling mod can react to specific questions
 * (DT uses it to collect + upload logs when its bug-report question gets a real-bug answer,
 * matching its death-screen behaviour).
 *
 * <p>Best-effort: a misbehaving callback (throws) is swallowed — logged once, then ignored — so
 * it never breaks the survey walk. The slot is {@code volatile} for safe cross-thread publish,
 * though it is only ever read on the client render thread.</p>
 */
public final class SurveySubmitClientHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One-shot WARN so a persistently-throwing callback can't spam the log on every submit. */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    /** Notified with the answered question entry and the submitted score. */
    @FunctionalInterface
    public interface Listener {
        void onSubmit(SurveyQuestionPayload.Entry entry, int score);
    }

    private static volatile Listener listener;

    private SurveySubmitClientHook() {}

    /**
     * Register the callback a bundling mod uses to observe survey submissions. Call once from the
     * bundling mod's client setup; the last registration wins and {@code null} clears it.
     */
    public static void register(Listener newListener) {
        listener = newListener;
    }

    /** Fire the registered callback (if any), swallowing + logging-once any failure. */
    public static void fire(SurveyQuestionPayload.Entry entry, int score) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onSubmit(entry, score);
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Survey-submit client hook threw; ignoring (this warning is logged once).", t);
            }
        }
    }
}
