package com.engineerclient.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    /** SS Practice: a click it handles itself still waits the game's 4 ticks before holding repeats it. */
    @Accessor("rightClickDelay")
    void ec$setRightClickDelay(int ticks);
}
