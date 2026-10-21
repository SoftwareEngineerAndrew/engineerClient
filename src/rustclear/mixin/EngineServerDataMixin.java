package net.rustclear.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.rustclear.mod.engine.Engine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Many SkyBlock mods only switch on when the server address contains "hypixel.net", which a
 * singleplayer world has none of; in the Rustclear world the client reports mc.hypixel.net.
 */
@Mixin(Minecraft.class)
public class EngineServerDataMixin {
    @Unique
    private static final ServerData RUSTCLEAR_HYPIXEL = new ServerData("Hypixel", "mc.hypixel.net", ServerData.Type.OTHER);

    @Inject(method = "getCurrentServer", at = @At("HEAD"), cancellable = true)
    private void rustclear$hypixelAddress(CallbackInfoReturnable<ServerData> cir) {
        if (Engine.isEngineClient()) {
            cir.setReturnValue(RUSTCLEAR_HYPIXEL);
        }
    }
}
