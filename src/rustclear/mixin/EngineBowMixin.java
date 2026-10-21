package net.rustclear.mod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A dungeon bow let go in the Rustclear world shoots the run's arrows (GearSystem.release), not vanilla's. */
@Mixin(BowItem.class)
public class EngineBowMixin {
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void rustclear$engineBowRelease(ItemStack stack, Level level, LivingEntity entity, int timeLeft,
                                           CallbackInfoReturnable<Boolean> cir) {
        int drawn = ((BowItem) (Object) this).getUseDuration(stack, entity) - timeLeft;
        if (EngineServer.bowRelease(stack, level, entity, drawn)) {
            cir.setReturnValue(true);
        }
    }
}
