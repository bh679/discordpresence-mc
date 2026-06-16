package games.brennan.discordpresence.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.discordpresence.survey.SurveyManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /feedback} command: opens the feedback survey for the running player, in any
 * game mode. Not op-gated ({@code requires(s -> true)}); player-only, since a screen can
 * only open on a client (the console gets the vanilla "player only" error). The eligibility
 * checks live in {@link SurveyManager#openSurveyFor}.
 */
public final class FeedbackCommand {

    private FeedbackCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("feedback")
                        .requires(source -> true) // everyone, any game mode
                        .executes(FeedbackCommand::run));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SurveyManager.get().openSurveyFor(player);
        return 1;
    }
}
