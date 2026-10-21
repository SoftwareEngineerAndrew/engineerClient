package net.rustclear.mod.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the dungeon nothing shoves a player: mobs don't push players (or get pushed by them) and players don't
 * push each other, as on Hypixel, where walking into a mob or a teammate doesn't move anyone. Mobs still push
 * each other. Both sides (a player's movement runs on its client). Engine world only.
 */
@Mixin(Entity.class)
public class EnginePushMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void rustclear$noPlayerPush(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player) && !(other instanceof Player)) {
            return;
        }
        boolean engine = self.level().isClientSide() ? Engine.isEngineClient() : EngineServer.isEngineLevel(self.level());
        if (engine) {
            ci.cancel();
        }
    }
}
