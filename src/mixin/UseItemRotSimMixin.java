package com.engineerclient.mixin;

import com.engineerclient.p3sim.Fight;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P3 Sim: the player's rotation as a use_item arrives, before handleUseItem snaps it to the packet's: the last
 * movement packet's rotation, which Hypixel aims the Jerry-chine with. Only the sim's server records it
 * ({@link Fight#noteUseItem}); the netty-thread pass of this method is ignored there.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class UseItemRotSimMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void ec$priorRot(ServerboundUseItemPacket packet, CallbackInfo ci) {
        Fight.noteUseItem(this.player);
    }
}
