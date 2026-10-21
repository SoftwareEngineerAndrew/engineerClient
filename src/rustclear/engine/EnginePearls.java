package net.rustclear.mod.engine;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ender Pearls as on SkyBlock now: where the pearl lands, you land centred on that block
 * (x/z snapped to the middle, like Aspect of the End and etherwarp) instead of at the exact
 * spot, with no fall damage and no endermite. Pearls still fly as vanilla ones do.
 */
public final class EnginePearls {
    /** How far back along its flight the landing spot is taken, so a wall hit lands in front of the wall. */
    private static final double BACK_OFF = 0.05;

    private EnginePearls() {
    }

    /** ThrownEnderpearl.onHit in the engine world. Returns true when it was handled (the pearl is gone). */
    public static boolean land(Entity pearl, HitResult hit) {
        if (!EngineServer.isEngineLevel(pearl.level()) || pearl.level().isClientSide()) {
            return false;
        }
        if (hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        Entity owner = ((Projectile) pearl).getOwner();
        pearl.discard();
        if (!(owner instanceof ServerPlayer player) || player.level() != pearl.level() || !player.isAlive()) {
            return true;
        }
        Vec3 velocity = pearl.getDeltaMovement();
        Vec3 at = hit.getLocation();
        if (velocity.lengthSqr() > 1.0E-6) {
            at = at.subtract(velocity.normalize().scale(BACK_OFF));
        }
        double x = Math.floor(at.x) + 0.5;
        double z = Math.floor(at.z) + 0.5;
        double y = at.y;
        // Snapping to the block's middle can push the player into a wall or ledge: step up a block
        // at most twice (Smalldungeons ether_transmission resolve_upward), else keep the exact spot.
        double feet = y;
        boolean fits = false;
        for (int i = 0; i <= 2; i++) {
            if (fits(player, x, feet, z)) {
                fits = true;
                break;
            }
            feet = Math.floor(feet) + 1.0;
        }
        if (!fits) {
            x = at.x;
            z = at.z;
            feet = y;
        }
        player.teleportTo(player.level(), x, feet, z, Relative.ROTATION, 0f, 0f, false);
        player.resetFallDistance();
        player.level().playSound(null, x, feet, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
        return true;
    }

    private static boolean fits(ServerPlayer player, double x, double y, double z) {
        AABB box = new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        return player.level().noCollision(player, box);
    }
}
