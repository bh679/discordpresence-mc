package games.brennan.discordpresence.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import games.brennan.discordpresence.client.ClientChatTags;
import games.brennan.discordpresence.discord.ChatTagHighlighter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client mixin that makes configured chat-tag tokens (e.g. {@code @dev}) behave like a command argument
 * in the chat box, via three hooks on {@link CommandSuggestions}:
 * <ul>
 *   <li>{@link #discordpresence$addChatTags} — adds the tokens to vanilla's non-{@code /} suggestion
 *       candidates (reuses its prefix-matching, popup, and Tab-completion);</li>
 *   <li>{@link #discordpresence$autoShowChatTags} — auto-opens the popup while the cursor word is a
 *       {@code @tag} (vanilla only auto-opens it for commands);</li>
 *   <li>{@link #discordpresence$colorChatTags} — colours the {@code @tag} yellow in the input box as
 *       it is typed (vanilla leaves plain chat uncoloured).</li>
 * </ul>
 * Tokens come from the CLIENT config via {@link ClientChatTags}; the highlight masking is shared with
 * the server-side broadcast colouring through {@link ChatTagHighlighter}.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @Shadow @Final Minecraft minecraft;
    @Shadow @Final EditBox input;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private boolean allowSuggestions;
    @Shadow private ParseResults<SharedSuggestionProvider> currentParse;

    @Shadow public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @ModifyArg(
            method = "updateCommandInfo",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/commands/SharedSuggestionProvider;"
                            + "suggest(Ljava/lang/Iterable;Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)"
                            + "Ljava/util/concurrent/CompletableFuture;"),
            index = 0)
    private Iterable<String> discordpresence$addChatTags(Iterable<String> vanilla) {
        return ClientChatTags.augment(vanilla);
    }

    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void discordpresence$autoShowChatTags(CallbackInfo ci) {
        if (!ClientChatTags.shouldAutoShow(this.input.getValue(), this.input.getCursorPosition())) {
            return;
        }
        if (this.allowSuggestions
                && this.minecraft.options.autoSuggestions().get()
                && this.pendingSuggestions != null
                && this.pendingSuggestions.isDone()
                && !this.pendingSuggestions.join().isEmpty()) {
            this.showSuggestions(false);
        }
    }

    @Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
    private void discordpresence$colorChatTags(String text, int offset,
                                               CallbackInfoReturnable<FormattedCharSequence> cir) {
        if (this.currentParse != null) {
            return; // a command — leave vanilla's argument highlighting
        }
        List<String> tokens = ClientChatTags.tokens();
        boolean[] mask = ChatTagHighlighter.highlightMask(text, tokens);
        boolean any = false;
        for (boolean b : mask) {
            if (b) {
                any = true;
                break;
            }
        }
        if (!any) {
            return; // no tags here — let vanilla return the plain text
        }
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            boolean hi = mask[i];
            int j = i;
            while (j < text.length() && mask[j] == hi) {
                j++;
            }
            Style style = hi ? Style.EMPTY.withColor(ChatFormatting.YELLOW) : Style.EMPTY;
            parts.add(FormattedCharSequence.forward(text.substring(i, j), style));
            i = j;
        }
        cir.setReturnValue(FormattedCharSequence.composite(parts));
    }
}
