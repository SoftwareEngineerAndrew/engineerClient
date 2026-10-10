package com.devgineerclient.mixin;

import com.devgineerclient.recorder.PacketDecode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: the command tree once the client has applied a commands packet.
 *
 * handleCommands first hops to the game thread (PacketUtils.ensureRunningOnSameThread throws on the
 * network thread before the method returns), so RETURN only runs once, on the game thread, with the
 * dispatcher rebuilt. Optional (require = 0): if the target ever moves, the recorder only loses
 * this line.
 */
@Mixin(ClientPacketListener.class)
public class CommandsAppliedMixin {

    @Inject(
        method = "handleCommands(Lnet/minecraft/network/protocol/game/ClientboundCommandsPacket;)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$commandsApplied(ClientboundCommandsPacket packet, CallbackInfo ci) {
        try {
            PacketDecode.commandsApplied((ClientPacketListener) (Object) this);
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
