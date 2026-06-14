package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DiscordService#joinBody} fills the player template and appends the bundling mod's
 * provider suffix on its own line — degrading to the plain body when no provider is registered,
 * the suffix is blank, or the provider throws. No Minecraft runtime needed.
 */
class JoinBodyFormatTest {

    private static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearProvider() {
        DiscordCredentials.register(null); // isolate: the provider slot is process-wide static
    }

    @Test
    void appendsSuffixOnItsOwnLine() {
        DiscordCredentials.register(suffixProvider((id, name) -> "DungeonTrain 1.2.3"));
        assertEquals("🎮 **Steve** started the game\nDungeonTrain 1.2.3",
                DiscordService.joinBody("🎮 **{player}** started the game", UID, "Steve"));
    }

    @Test
    void blankSuffixLeavesBodyUnchanged() {
        DiscordCredentials.register(suffixProvider((id, name) -> ""));
        assertEquals("🎮 **Steve** started the game",
                DiscordService.joinBody("🎮 **{player}** started the game", UID, "Steve"));
    }

    @Test
    void noProviderLeavesBodyUnchanged() {
        // no register(...) → provider is null → suffix resolves to ""
        assertEquals("Steve joined", DiscordService.joinBody("{player} joined", UID, "Steve"));
    }

    @Test
    void throwingProviderDegradesToBody() {
        DiscordCredentials.register(suffixProvider((id, name) -> { throw new RuntimeException("boom"); }));
        assertEquals("Steve joined", DiscordService.joinBody("{player} joined", UID, "Steve"));
    }

    @Test
    void passesJoiningPlayerIdentityToProvider() {
        DiscordCredentials.register(suffixProvider((id, name) -> id + "/" + name));
        assertEquals("hi\n" + UID + "/Steve", DiscordService.joinBody("hi", UID, "Steve"));
    }

    /** A provider that supplies only a join suffix (blank credentials, like a bundling mod). */
    private static DiscordCredentialsProvider suffixProvider(BiFunction<UUID, String, String> fn) {
        return new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String joinMessageSuffix(UUID playerId, String playerName) {
                return fn.apply(playerId, playerName);
            }
        };
    }
}
