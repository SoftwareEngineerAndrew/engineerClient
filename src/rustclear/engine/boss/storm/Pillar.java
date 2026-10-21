package net.rustclear.mod.engine.boss.storm;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.rustclear.mod.engine.boss.BossContext;

/**
 * One of P2's crushers (boss-p2-storm.md §5): a 7x7 column hanging over the floor. A press steps
 * it down 5 layers, one every 4 ticks (each new layer polished diorite in the column's shape); at
 * the floor (bottom 169) it waits 25, rises a layer every 4 to 189, waits 27 and steps down 3 to
 * 186, its armed rest. It crushes Storm only within 60 ticks of a step; once it has, it collapses
 * 20 ticks later and is spent for good.
 */
final class Pillar {
    private enum State { REST, STEPPING, FLOOR_WAIT, RISING, TOP_WAIT, LOWERING, SPENT }

    final int index;
    final int minX;
    final int minZ;
    private final BossContext ctx;
    /** The column's cells (x, z) in its 7x7, from the arena's own layers. */
    private final List<int[]> shape = new ArrayList<>();
    int bottom;
    private State state = State.REST;
    private int stepsLeft;
    private long nextAt;
    long lastStep = Long.MIN_VALUE / 2;
    /** Layers it went below its start (the debris of a collapse). */
    private int lowest;

    Pillar(BossContext ctx, int index) {
        this.ctx = ctx;
        this.index = index;
        this.minX = StormData.PILLAR_CORNERS[index][0];
        this.minZ = StormData.PILLAR_CORNERS[index][1];
        int sampleY = StormData.TOP + 4;
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                if (!ctx.blocks().get(new BlockPos(minX + dx, sampleY, minZ + dz)).isAir()) {
                    shape.add(new int[] {minX + dx, minZ + dz});
                }
            }
        }
        // The arena's columns hang from 175; Hypixel's start at 177.
        int b = StormData.TOP + 4;
        while (b > StormData.FLOOR && !ctx.blocks().get(new BlockPos(minX + 3, b - 1, minZ + 3)).isAir()) {
            b--;
        }
        bottom = b;
        while (bottom < StormData.START_BOTTOM) {
            layer(bottom, Blocks.AIR.defaultBlockState());
            bottom++;
        }
        lowest = bottom;
    }

    boolean spent() {
        return state == State.SPENT;
    }

    boolean resting() {
        return state == State.REST;
    }

    /** A pad press: 5 steps from the next check tick [from]. Only from rest. */
    void press(long from) {
        if (state != State.REST || index == 3) {
            return;
        }
        state = State.STEPPING;
        stepsLeft = StormData.STEP_LAYERS;
        nextAt = from;
    }

    void tick(long now) {
        if (now < nextAt) {
            return;
        }
        switch (state) {
            case STEPPING, LOWERING -> {
                if (bottom <= StormData.FLOOR || stepsLeft <= 0) {
                    state = bottom <= StormData.FLOOR ? State.FLOOR_WAIT : State.REST;
                    nextAt = now + (state == State.FLOOR_WAIT ? StormData.FLOOR_WAIT : 0);
                    return;
                }
                bottom--;
                layer(bottom, Blocks.POLISHED_DIORITE.defaultBlockState());
                lowest = Math.min(lowest, bottom);
                lastStep = now;
                stepsLeft--;
                nextAt = now + StormData.STEP_TICKS;
                if (stepsLeft == 0) {
                    state = bottom <= StormData.FLOOR ? State.FLOOR_WAIT : State.REST;
                    nextAt = now + (state == State.FLOOR_WAIT ? StormData.FLOOR_WAIT : 0);
                }
            }
            case FLOOR_WAIT -> {
                state = State.RISING;
                nextAt = now;
            }
            case RISING -> {
                layer(bottom, Blocks.AIR.defaultBlockState());
                bottom++;
                nextAt = now + StormData.STEP_TICKS;
                if (bottom >= StormData.TOP) {
                    state = State.TOP_WAIT;
                    nextAt = now + StormData.TOP_WAIT;
                }
            }
            case TOP_WAIT -> {
                state = State.LOWERING;
                stepsLeft = StormData.TOP - StormData.REST;
                nextAt = now;
            }
            default -> {
            }
        }
    }

    /** Whether Storm (feet at [feet]) is in this pillar's 6x6 zone and its bottom reaches his head, while armed. */
    boolean crushes(Vec3 feet, long now) {
        return state != State.SPENT && index != 3 && now - lastStep <= StormData.ARMED
            && feet.x >= minX && feet.x <= minX + 6 && feet.z >= minZ && feet.z <= minZ + 6
            && feet.y + StormData.HEAD >= bottom;
    }

    /** Whether [p] stands in its 7x7 square low enough to hide from Giga Lightning. */
    boolean hides(Vec3 p) {
        return p.x >= minX && p.x < minX + 7 && p.z >= minZ && p.z < minZ + 7 && p.y <= StormData.HIDE_MAX_Y;
    }

    /** Crushed: 20 ticks later its lower layers fall as debris and it is spent at its measured bottom. */
    void collapse() {
        state = State.SPENT;
        int to = Math.max(bottom, StormData.SPENT_BOTTOM[index]);
        for (int y = bottom; y < to; y++) {
            for (int[] c : shape) {
                BlockPos pos = new BlockPos(c[0], y, c[1]);
                BlockState was = ctx.blocks().get(pos);
                ctx.blocks().set(pos, Blocks.AIR.defaultBlockState());
                if (!was.isAir() && (c[0] + c[1] + y) % 3 == 0) {
                    // A share of the blocks fall (2-50 debris per crush on Hypixel).
                    FallingBlockEntity f = FallingBlockEntity.fall(ctx.level(), pos, was);
                    f.dropItem = false;
                    ctx.entities().add(f);
                }
            }
        }
        bottom = to;
    }

    private void layer(int y, BlockState state) {
        for (int[] c : shape) {
            ctx.blocks().set(new BlockPos(c[0], y, c[1]), state);
        }
    }
}
