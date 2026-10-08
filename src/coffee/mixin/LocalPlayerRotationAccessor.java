package com.coffeeclient.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** No Rotate: the rotation the client last sent the server. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerRotationAccessor {
    @Accessor("yRotLast")
    void cc$setYRotLast(float value);

    @Accessor("xRotLast")
    void cc$setXRotLast(float value);
}
