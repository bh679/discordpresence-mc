package games.brennan.discordpresence.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import games.brennan.discordpresence.DiscordPresence;
import games.brennan.discordpresence.config.DiscordPresenceConfig;
import games.brennan.discordpresence.discord.LinkService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Registers the player-facing verification commands:
 * {@code /discordpresence link|status|unlink} (alias {@code /dp}). Each requires a
 * player source (console can't own a link) and delegates to {@link LinkService};
 * default permission level 0, so any player can self-verify.
 *
 * <p>Logic-free delegation, mirroring {@code DiscordPresenceEvents}. Registered on
 * the game event bus for both dists so it also runs on a dedicated server.</p>
 */
@EventBusSubscriber(modid = DiscordPresence.MOD_ID)
public final class DiscordPresenceCommands {

    private DiscordPresenceCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(
                Commands.literal("discordpresence")
                        .then(Commands.literal("link").executes(DiscordPresenceCommands::link))
                        .then(Commands.literal("status").executes(DiscordPresenceCommands::status))
                        .then(Commands.literal("unlink").executes(DiscordPresenceCommands::unlink)));
        // Short alias.
        event.getDispatcher().register(Commands.literal("dp").redirect(root));
    }

    private static int link(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        LinkService svc = LinkService.get();

        boolean alreadyLinked = svc.status(player.getUUID()) != null;
        String code = svc.requestLink(player);
        if (code == null) {
            ctx.getSource().sendFailure(Component.literal(
                            "Account linking isn't configured on this server.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        int ttl = DiscordPresenceConfig.getLinkCodeTtlMinutes();
        if (alreadyLinked) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                            "You're already linked — completing this re-links your account.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Discord link code: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(code).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                        "Post this code in the server's Discord link channel within "
                                + ttl + " minute(s) to verify your account.")
                .withStyle(ChatFormatting.GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean linked = LinkService.get().status(player.getUUID()) != null;
        if (linked) {
            ctx.getSource().sendSuccess(() -> Component.literal("✅ Your Minecraft account is linked to Discord.")
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                            "Your account isn't linked. Run /discordpresence link to verify.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int unlink(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean was = LinkService.get().unlink(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                        was ? "Your Discord link has been removed." : "Your account wasn't linked.")
                .withStyle(was ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }
}
