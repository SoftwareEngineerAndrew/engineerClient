package com.devgineerclient.mixin;

import com.devgineerclient.recorder.ScreenCapture;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltips as they are about to be drawn, for the Dungeon Recorder (ScreenCapture): the lines after
 * every mod has added to or rewritten them, which is not what the item's own lore says. Both
 * Component-list entry points (item tooltips) and the three FormattedCharSequence-list ones (widget
 * and button tooltips, every single-Component overload, mods' text) are hooked; none calls another,
 * so nothing is written twice. ScreenCapture writes a tooltip only when it changes.
 * Read only; require = 0 so a changed target can never stop the game from starting.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipTapMixin {

    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recTooltip(Font font, List<Component> lines, Optional<TooltipComponent> image, int x, int y, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltip(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }

    @Inject(
        method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recComponentTooltip(Font font, List<Component> lines, int x, int y, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltip(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }

    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recSeqTooltip(Font font, List<? extends FormattedCharSequence> lines, int x, int y, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltipSeq((List<FormattedCharSequence>) lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }

    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;IIZ)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recPositionedTooltip(Font font, List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, int x, int y, boolean focused, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltipSeq(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }

    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;IIZLnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void dc$recWidgetTooltip(Font font, List<FormattedCharSequence> lines, Optional<TooltipComponent> image, ClientTooltipPositioner positioner, int x, int y,
                                     boolean focused, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltipSeq(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }
}
