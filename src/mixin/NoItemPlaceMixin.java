package com.engineerclient.mixin;

import com.engineerclient.skyblock.NoItemPlace;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class NoItemPlaceMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void ec$skipPlace(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (context.getLevel().isClientSide() && NoItemPlace.INSTANCE.blocks(context)) cir.setReturnValue(true);
    }

    @Inject(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"), cancellable = true)
    private void ec$skipAfterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide() && NoItemPlace.INSTANCE.blocks(context)) cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
