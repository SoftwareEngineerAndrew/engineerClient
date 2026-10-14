package com.devgineerclient.mixin;

import com.devgineerclient.misc.EarlyJoin;
import com.mojang.realmsclient.client.RealmsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.quickplay.QuickPlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Early join: vanilla's quick play connect after the resource load is skipped when EarlyJoin already connected. */
@Mixin(QuickPlay.class)
public class EarlyJoinQuickPlayMixin {

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void dc$skipIfJoined(Minecraft minecraft, GameConfig.QuickPlayVariant variant, RealmsClient realmsClient, CallbackInfo ci) {
        if (EarlyJoin.skipQuickPlay()) ci.cancel();
    }
}
