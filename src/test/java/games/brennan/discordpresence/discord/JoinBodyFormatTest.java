package games.brennan.discordpresence.discord;

import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DiscordService#joinMessage} fills the player template, appends the bundling mod's provider
 * suffix on its own line, and surfaces the user-ids the <b>trusted suffix</b> is allowed to ping.
 * Degrades to the plain body (no allowed ids) when no provider is registered, the suffix is blank,
 * or the provider throws. No Minecraft runtime needed.
 */
class JoinBodyFormatTest {

    private static final UUID UID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearProvider() {
        DiscordCredentials.register(null); // isolate: the provider slot is process-wide static
    }

    @Test
    void appendsSuffixOnItsOwnLineWithNoMentions() {
        DiscordCredentials.register(suffixProvider((id, name) -> "DungeonTrain 1.2.3"));
        DiscordService.JoinMessage jm = DiscordService.joinMessage("🎮 **{player}** started the game", UID, "Steve");
        assertEquals("🎮 **Steve** started the game\nDungeonTrain 1.2.3", jm.content());
        assertEquals(List.of(), jm.allowedUserIds());
    }

    @Test
    void blankSuffixLeavesBodyUnchanged() {
        DiscordCredentials.register(suffixProvider((id, name) -> ""));
        DiscordService.JoinMessage jm = DiscordService.joinMessage("🎮 **{player}** started the game", UID, "Steve");
        assertEquals("🎮 **Steve** started the game", jm.content());
        assertEquals(List.of(), jm.allowedUserIds());
    }

    @Test
    void noProviderLeavesBodyUnchanged() {
        DiscordService.JoinMessage jm = DiscordService.joinMessage("{player} joined", UID, "Steve");
        assertEquals("Steve joined", jm.content());
        assertEquals(List.of(), jm.allowedUserIds());
    }

    @Test
    void throwingProviderDegradesToBody() {
        DiscordCredentials.register(suffixProvider((id, name) -> { throw new RuntimeException("boom"); }));
        DiscordService.JoinMessage jm = DiscordService.joinMessage("{player} joined", UID, "Steve");
        assertEquals("Steve joined", jm.content());
        assertEquals(List.of(), jm.allowedUserIds());
    }

    @Test
    void extractsTrustedMentionIdsFromSuffix() {
        DiscordCredentials.register(suffixProvider((id, name) -> "DungeonTrain 1.2.3\n<@342110421114945537>"));
        DiscordService.JoinMessage jm = DiscordService.joinMessage("{player} joined", UID, "Steve");
        assertEquals("Steve joined\nDungeonTrain 1.2.3\n<@342110421114945537>", jm.content());
        assertEquals(List.of("342110421114945537"), jm.allowedUserIds());
    }

    @Test
    void playerNameMentionLookalikeIsNotPingable() {
        // The suffix carries no mention; a <@id>-looking PLAYER NAME must NOT become pingable —
        // only the trusted suffix is scanned for the allow-list.
        DiscordCredentials.register(suffixProvider((id, name) -> "DungeonTrain 1.2.3"));
        DiscordService.JoinMessage jm = DiscordService.joinMessage("{player} joined", UID, "<@999>");
        assertEquals(List.of(), jm.allowedUserIds());
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
