package com.engineerclient.mixin;

import com.engineerclient.recorder.ScreenCapture;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltips as they are about to be drawn, for the Dungeon Recorder (ScreenCapture): the lines after
 * every mod has added to or rewritten them, which is not what the item's own lore says. Both
 * Component-list entry points are hooked; ScreenCapture writes a tooltip only when it changes.
 * Read only; require = 0 so a changed target can never stop the game from starting.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipTapMixin {

    @Inject(
        method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void ec$recTooltip(Font font, List<Component> lines, Optional<TooltipComponent> image, int x, int y, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltip(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }

    @Inject(
        method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"),
        require = 0, expect = 0
    )
    private void ec$recComponentTooltip(Font font, List<Component> lines, int x, int y, Identifier style, CallbackInfo ci) {
        try { ScreenCapture.INSTANCE.tooltip(lines, x, y); } catch (Throwable t) { /* never break drawing */ }
    }
}
