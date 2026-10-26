package com.coffeeclient.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's right-click use, called by Leap Extras' Instant Open at the press instead of the next tick. */
@Mixin(Minecraft.class)
public interface InstantLeapUseInvoker {
    @Invoker("startUseItem")
    void cc$startUseItem();
}
