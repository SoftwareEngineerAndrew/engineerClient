package net.rustclear.mod.mixin;

import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** ArmorStand.setMarker is private; mob nametag stands are markers so they never catch a hit. Support Orbs drop the base plate. */
@Mixin(ArmorStand.class)
public interface ArmorStandAccessor {
    @Invoker("setMarker")
    void rustclear$setMarker(boolean marker);

    @Invoker("setNoBasePlate")
    void rustclear$setNoBasePlate(boolean noBasePlate);
}
