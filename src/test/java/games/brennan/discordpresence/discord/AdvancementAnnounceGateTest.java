package games.brennan.discordpresence.discord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the thread-local advancement-announce suppression gate that
 * {@link DiscordService#runWithAdvancementAnnounceSuppressed(Runnable)} exposes for
 * bundling mods (e.g. Dungeon Train wrapping a cross-world advancement replay). The
 * gate must be off by default, on only for the duration of the body, and always
 * restored — including after an exception and across nesting.
 */
class AdvancementAnnounceGateTest {

    @AfterEach
    void ensureClearedBetweenTests() {
        // Guard against a leaked flag on the test thread polluting later tests.
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed(),
                "suppression flag leaked across a test");
    }

    @Test
    void defaultsToNotSuppressed() {
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed());
    }

    @Test
    void suppressedOnlyInsideBody() {
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed());
        DiscordService.runWithAdvancementAnnounceSuppressed(() ->
                assertTrue(DiscordService.isAdvancementAnnounceSuppressed()));
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed());
    }

    @Test
    void restoresAfterException() {
        assertThrows(RuntimeException.class, () ->
                DiscordService.runWithAdvancementAnnounceSuppressed(() -> {
                    throw new RuntimeException("boom");
                }));
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed());
    }

    @Test
    void nestingRestoresToOuterState() {
        DiscordService.runWithAdvancementAnnounceSuppressed(() -> {
            assertTrue(DiscordService.isAdvancementAnnounceSuppressed());
            DiscordService.runWithAdvancementAnnounceSuppressed(() ->
                    assertTrue(DiscordService.isAdvancementAnnounceSuppressed()));
            // Still suppressed after the inner call returns (restored to the outer "true").
            assertTrue(DiscordService.isAdvancementAnnounceSuppressed());
        });
        assertFalse(DiscordService.isAdvancementAnnounceSuppressed());
    }
}
