package com.engineerclient.mixin.odin;

import com.engineerclient.practice.OdinTermTimes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** {@link OdinTermTimes}' Two Decimals for Terminal Times' section splits (its chat handler). */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.boss.TerminalTimes$2", remap = false)
public class OdinTermTimesMixin {
    @ModifyArg(method = "invoke(Lcom/odtheking/odin/events/MessageEvent$ModifyChat;)V",
        at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/ChatUtilsKt;modMessage$default(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/network/chat/Style;ILjava/lang/Object;)V"),
        index = 0, remap = false)
    private Object ec$twoDecimals(Object message) {
        return OdinTermTimes.format(message);
    }
}
