package net.rustclear.mod.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The health entity-data slot (index 9). Hypixel puts a mob's real HP there (1,250,000 for a
 * Withermancer), past vanilla's max health, and mods read it; setHealth would clamp it.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> rustclear$healthId() {
        throw new AssertionError();
    }
}
