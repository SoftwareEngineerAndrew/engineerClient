package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.rustclear.mod.mixin.FallingBlockAccessor;

/**
 * Falling-block debris the way Hypixel animates a breaking door (clear-rooms-doors.md §4): each
 * block is a falling_block riding an invisible bat. On a door the copies spawn on the "opened"
 * line's tick at the block's position less 0.656, hold still for 7 ticks, then the bats drop
 * 0.9375 blocks every 3 ticks, and everything is removed 23 ticks after spawning. Boss debris
 * (pillars, platforms, statues) uses the same pieces with its own timings.
 */
public final class Debris {
    /** One group's motion: [hold] ticks still, then [step] blocks down every [every] ticks, gone at [life]. */
    public record Motion(int hold, int every, double step, int life) {}

    /** Door debris (b1e397 n 293799-293822; 994 of the door-sized groups removed at 23). */
    public static final Motion DOOR = new Motion(7, 3, 0.9375, 23);
    /** Where a door copy appears: the block's y less this (68.344 for the y = 69 layer). */
    public static final double DOOR_DROP = 0.656;

    private static final class Piece {
        final Bat bat;
        final FallingBlockEntity block;
        final Motion motion;
        int age;

        Piece(Bat bat, FallingBlockEntity block, Motion motion) {
            this.bat = bat;
            this.block = block;
            this.motion = motion;
        }
    }

    private final ServerLevel level;
    private final List<Piece> pieces = new ArrayList<>();

    public Debris(ServerLevel level) {
        this.level = level;
    }

    /** A copy of [state] at [pos] (its corner), [drop] blocks lower, moving as [motion]. */
    public void spawn(BlockPos pos, BlockState state, double drop, Motion motion) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() - drop;
        double z = pos.getZ() + 0.5;
        Bat bat = new Bat(EntityTypes.BAT, level);
        bat.setNoAi(true);
        bat.setInvisible(true);
        bat.setSilent(true);
        bat.setNoGravity(true);
        bat.setPermanentlyInvulnerable(true);
        bat.snapTo(x, y, z, 0f, 0f);
        FallingBlockEntity block = new FallingBlockEntity(EntityTypes.FALLING_BLOCK, level);
        ((FallingBlockAccessor) block).rustclear$setBlockState(state);
        block.setNoGravity(true);
        block.dropItem = false;
        block.snapTo(x, y, z, 0f, 0f);
        level.addFreshEntity(bat);
        level.addFreshEntity(block);
        block.startRiding(bat, true, false);
        // The block sits on the bat's passenger point: lower the bat so the block is where it belongs.
        double off = block.getY() - bat.getY();
        bat.snapTo(x, y - off, z, 0f, 0f);
        pieces.add(new Piece(bat, block, motion));
    }

    /** How many pieces are alive (for the profiling log). */
    public int size() {
        return pieces.size();
    }

    public void tick() {
        for (Iterator<Piece> it = pieces.iterator(); it.hasNext(); ) {
            Piece p = it.next();
            p.age++;
            // Falling blocks age out on their own; keep ours young and still.
            p.block.time = 1;
            if (p.age >= p.motion.life() || p.bat.isRemoved()) {
                p.block.discard();
                p.bat.discard();
                it.remove();
                continue;
            }
            int moving = p.age - p.motion.hold();
            if (moving >= 0 && moving % p.motion.every() == 0) {
                p.bat.teleportTo(p.bat.getX(), p.bat.getY() - p.motion.step(), p.bat.getZ());
            }
        }
    }

    public void dispose() {
        for (Piece p : pieces) {
            p.block.discard();
            p.bat.discard();
        }
        pieces.clear();
    }
}
