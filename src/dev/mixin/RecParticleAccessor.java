package com.devgineerclient.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Dungeon Recorder: where a spawned particle starts, how fast it moves and how long it lives. These
 * are protected fields on {@link Particle} (x, y, z, xd, yd, zd, lifetime), read once
 * as the particle is added to the engine. Read-only.
 */
@Mixin(Particle.class)
public interface RecParticleAccessor {

    @Accessor("x")
    double ec_getX();

    @Accessor("y")
    double ec_getY();

    @Accessor("z")
    double ec_getZ();

    @Accessor("xd")
    double ec_getXd();

    @Accessor("yd")
    double ec_getYd();

    @Accessor("zd")
    double ec_getZd();

    @Accessor("lifetime")
    int ec_getLifetime();
}
