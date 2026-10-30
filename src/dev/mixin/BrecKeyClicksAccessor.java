package com.devgineerclient.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Better Recorder: the presses a key has waiting for the game's tick (read before the tick takes them). */
@Mixin(KeyMapping.class)
public interface BrecKeyClicksAccessor {
    @Accessor("clickCount")
    int brec$clicks();
}
