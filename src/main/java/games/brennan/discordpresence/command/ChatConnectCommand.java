package games.brennan.discordpresence.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig.Consent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The {@code /chatconnect} command: toggles this client's network-access consent (the same
 * {@code networkConsent} the title-screen prompt and the mod config set). Registered as a
 * <b>client</b> command (see {@link ClientDPCommands}), so it runs locally and is always available
 * regardless of op level or whether "allow cheats" is on — no packet, no protocol change.
 *
 * <ul>
 *   <li>{@code /chatconnect} — report the current state.</li>
 *   <li>{@code /chatconnect on} — grant consent (enable online features).</li>
 *   <li>{@code /chatconnect off} — deny consent (disable online features).</li>
 * </ul>
 */
public final class ChatConnectCommand {

    private ChatConnectCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("chatconnect")
                        .executes(ChatConnectCommand::report)
                        .then(Commands.literal("on").executes(ctx -> set(ctx, Consent.GRANTED)))
                        .then(Commands.literal("off").executes(ctx -> set(ctx, Consent.DENIED))));
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = DiscordPresenceClientConfig.getConsent() == Consent.GRANTED;
        ctx.getSource().sendSuccess(
                () -> Component.literal("Online features are currently " + (enabled ? "enabled" : "disabled") + "."),
                false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, Consent consent) {
        DiscordPresenceClientConfig.setConsent(consent);
        ctx.getSource().sendSuccess(
                () -> Component.literal(consent == Consent.GRANTED
                        ? "Online features enabled."
                        : "Online features disabled."),
                false);
        return 1;
    }
}
