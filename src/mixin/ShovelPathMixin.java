package com.engineerclient.mixin;

import com.engineerclient.skyblock.ItemTweaks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No dirt path and no flatten sound from a shovel in dungeons; passing on lets the right click go out as an item use. */
@Mixin(Item.class)
public class ShovelPathMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void ec$noShovelPath(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide() && ItemTweaks.noShovelPath(context.getItemInHand())) cir.setReturnValue(InteractionResult.PASS);
    }
}
