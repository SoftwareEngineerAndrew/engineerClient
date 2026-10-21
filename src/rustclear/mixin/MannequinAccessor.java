package net.rustclear.mod.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Mannequin.setImmovable is private; dungeon NPCs are immovable so walking into one doesn't shove it. */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
    @Invoker("setImmovable")
    void rustclear$setImmovable(boolean immovable);
}
