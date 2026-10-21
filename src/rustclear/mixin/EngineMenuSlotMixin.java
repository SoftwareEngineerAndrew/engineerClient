package net.rustclear.mod.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 9th hotbar slot is locked in the Rustclear world, as on Hypixel: no click can take, move,
 * swap or throw its item, in any window, and a click on it opens the SkyBlock Menu instead
 * (EngineServer.lockedClick). Cancelled on the client too, so nothing seems to move first.
 */
@Mixin(AbstractContainerMenu.class)
public class EngineMenuSlotMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void rustclear$lockMenuSlot(int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        if (EngineServer.lockedClick((AbstractContainerMenu) (Object) this, slot, button, input, player)) {
            ci.cancel();
        }
    }
}
