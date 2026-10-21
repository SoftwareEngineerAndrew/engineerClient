package net.rustclear.mod.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon bows never run out of arrows in the Rustclear world (Hypixel's quiver), so they draw
 * without any in the inventory. Both sides ask, so client and server agree the bow can be drawn.
 */
@Mixin(Player.class)
public class EngineProjectileMixin {
    @Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
    private void rustclear$engineArrows(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack arrow = EngineServer.infiniteArrow((Player) (Object) this, weapon);
        if (arrow != null) {
            cir.setReturnValue(arrow);
        }
    }
}
