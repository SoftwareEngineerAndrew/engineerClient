package com.engineerclient.mixin;

import com.engineerclient.betterpf.BetterPF;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Better PF: every container slot click the client sends (whatever sends it: the vanilla screen,
 * Odin's custom terminal window, hotbar/drop keys - they all end up here), and every block the
 * player right-clicks, for the replay. Recording only; nothing is changed or cancelled.
 */
@Mixin(MultiPlayerGameMode.class)
public class GameModeRecordMixin {

    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void ec$recordSlotClick(int containerId, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        BetterPF.INSTANCE.onSlotClick(slot, button, input.name());
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void ec$recordBlockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<?> cir) {
        BetterPF.INSTANCE.onBlockUse(hit.getBlockPos());
    }
}
