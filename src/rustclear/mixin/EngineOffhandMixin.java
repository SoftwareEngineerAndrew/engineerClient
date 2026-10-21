package net.rustclear.mod.mixin;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The offhand swap key with the 9th slot held: its item stays put and the SkyBlock Menu opens (EngineServer.lockedSwap). */
@Mixin(ServerGamePacketListenerImpl.class)
public class EngineOffhandMixin {
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void rustclear$lockMenuSlot(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
            && EngineServer.lockedSwap(((ServerGamePacketListenerImpl) (Object) this).player)) {
            ci.cancel();
        }
    }
}
