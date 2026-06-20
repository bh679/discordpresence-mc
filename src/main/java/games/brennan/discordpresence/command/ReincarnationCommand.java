package games.brennan.discordpresence.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.discordpresence.discord.DiscordService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The op-gated {@code /dpreincarnation} command — a diagnostic + test harness for the cross-world
 * reincarnation bridge, so remote echoes can be exercised deterministically instead of waiting on the
 * 60s tick / a cold cache:
 *
 * <ul>
 *   <li>{@code /dpreincarnation status} — report whether the bridge is active and the cache/outbox sizes.</li>
 *   <li>{@code /dpreincarnation post} — force an immediate scrape+POST of PlayerMob's recent deaths.</li>
 *   <li>{@code /dpreincarnation fetch [carriage]} — force an immediate fetch of remote lives for the
 *       running player at {@code carriage} (or "any"), caching them so the next remote-rolled EVENT spawn
 *       can embody one.</li>
 * </ul>
 *
 * <p>Server-side, permission level 2. Best-effort: it reports "bridge inactive" rather than failing when
 * DP isn't in relay-mode or PlayerMob isn't installed. The bridge spawn roll itself stays PlayerMob's.</p>
 */
public final class ReincarnationCommand {

    private ReincarnationCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dpreincarnation")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ReincarnationCommand::status))
                        .then(Commands.literal("post").executes(ReincarnationCommand::post))
                        .then(Commands.literal("fetch")
                                .executes(ctx -> fetch(ctx, null))
                                .then(Commands.argument("carriage", IntegerArgumentType.integer())
                                        .executes(ctx -> fetch(ctx, IntegerArgumentType.getInteger(ctx, "carriage"))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String status = DiscordService.get().reincarnationDebugStatus();
        ctx.getSource().sendSuccess(() -> Component.literal("Reincarnation: " + status), false);
        return 1;
    }

    private static int post(CommandContext<CommandSourceStack> ctx) {
        boolean ok = DiscordService.get().reincarnationDebugPost();
        ctx.getSource().sendSuccess(() -> Component.literal(ok
                ? "Reincarnation: outbound post triggered — check the relay / server log."
                : "Reincarnation: bridge inactive (needs relay-mode + PlayerMob)."), false);
        return ok ? 1 : 0;
    }

    private static int fetch(CommandContext<CommandSourceStack> ctx, Integer carriage)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        // The async fetch reports back by hopping to the server thread to message the player.
        boolean ok = DiscordService.get().reincarnationDebugFetch(player.getUUID(), carriage,
                msg -> server.execute(() -> player.sendSystemMessage(Component.literal("Reincarnation: " + msg))));
        ctx.getSource().sendSuccess(() -> Component.literal(ok
                ? "Reincarnation: fetching remote lives for carriage " + (carriage != null ? carriage : "any") + "…"
                : "Reincarnation: bridge inactive (needs relay-mode + PlayerMob)."), false);
        return ok ? 1 : 0;
    }
}
