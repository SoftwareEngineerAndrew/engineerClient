package com.engineerclient.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface CarriedSlotAccessor {
    @Accessor("carriedIndex")
    int carriedIndex();
}
