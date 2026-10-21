package net.rustclear.mod.engine.boss.goldor.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The target ("i4"), section 4 (boss-p3-goldor.md §6.4): stand on the light weighted pressure plate
 * (63,127,35) and shoot the nine cells at x 64/66/68, y 126/128/130, z 50 (blue terracotta, the
 * target emerald while it shows). The board itself is [TargetBoard]. Each tick, in Hypixel's order:
 * the plate, the grid step (light or reset), then the arrows that struck this tick in spawn order.
 * A strike on the showing cell turns it blue a tick later; the ninth count completes the device in
 * its tick. Arrows are real entities ([GearSystem]'s): a player's arrow that stuck this tick is a
 * strike on the block it stuck in, and goes a tick later (remove_entities 1 tick after the hit
 * sound in 98.5%); vanilla plays the hit sound.
 */
final class Target extends Device {
    static final BlockPos PLATE = new BlockPos(63, 127, 35);
    static final int Z_BOARD = 50;
    /**
     * Vanilla's pressure plate re-checks every 10 ticks while down, so it releases 1-10 ticks after
     * the last player steps off (recorded: 1-10, median 7, n 15).
     */
    static final int PLATE_CHECK = 10;
    /** Where arrows are watched: from the plate to the board. */
    private static final AABB AREA = new AABB(60, 122, 30, 72, 134, 52);
    /** The plate's touch box (a 1/16 inset, a quarter block high). */
    private static final AABB PLATE_BOX = new AABB(63.0625, 127, 35.0625, 63.9375, 127.25, 35.9375);

    private TargetBoard board;
    private int power;
    private long nextCheck;
    /** Who last stood on the plate. */
    private ServerPlayer plateBy;
    /** Each watched arrow's last motion (to know where it went in when it stuck). */
    private final Map<AbstractArrow, Vec3> flying = new IdentityHashMap<>();
    private final java.util.Set<AbstractArrow> struck = Collections.newSetFromMap(new IdentityHashMap<>());

    Target(Host host) {
        super(host);
    }

    @Override
    public String label() {
        return "Target";
    }

    @Override
    public int section() {
        return 4;
    }

    static BlockPos cellPos(int cell) {
        return new BlockPos(64 + (cell % 3) * 2, 126 + (cell / 3) * 2, Z_BOARD);
    }

    static int cellAt(BlockPos pos) {
        int dx = pos.getX() - 64;
        int dy = pos.getY() - 126;
        if (pos.getZ() != Z_BOARD || dx < 0 || dx > 4 || dy < 0 || dy > 4 || dx % 2 != 0 || dy % 2 != 0) {
            return -1;
        }
        return (dy / 2) * 3 + dx / 2;
    }

    @Override
    public void start() {
        board = new TargetBoard(host.random());
        for (int c = 0; c < TargetBoard.CELLS; c++) {
            setCell(c, false);
        }
        setPlate(0);
    }

    @Override
    public void tick() {
        if (board == null) {
            return;
        }
        long n = host.n();
        plate(n);
        if (done()) {
            return;
        }
        if (!board.running() && power > 0) {
            board.begin(n);
        }
        int g = board.grid(n, power > 0);
        if (power > 0 && g >= 0) {
            setCell(g, true);
        } else if (power == 0 && g <= -2) {
            // Off the plate on a grid tick: the lit cell goes blue and the attempt is over.
            setCell(-g - 2, false);
        }
        arrows();
    }

    // ---------------------------------------------------------------- the plate

    private void plate(long n) {
        int on = 0;
        for (ServerPlayer p : host.ctx().players()) {
            if (!p.isRemoved() && !host.ctx().isGhost(p) && p.getBoundingBox().intersects(PLATE_BOX)) {
                on++;
                plateBy = p;
            }
        }
        if (power == 0) {
            if (on > 0) {
                setPlate(on);
                nextCheck = n + PLATE_CHECK;
            }
        } else if (n >= nextCheck) {
            setPlate(on);
            nextCheck = n + PLATE_CHECK;
        }
    }

    private void setPlate(int p) {
        power = Math.min(15, p);
        host.ctx().blocks().set(PLATE, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE.defaultBlockState()
            .setValue(BlockStateProperties.POWER, power));
    }

    // ---------------------------------------------------------------- arrows

    /**
     * Arrows that stuck this tick, in spawn (entity id) order. Vanilla stops a stuck arrow (motion
     * zero) 0.05 short of where it hit, so the block is found by stepping along its last motion.
     */
    private void arrows() {
        List<AbstractArrow> list = new ArrayList<>();
        for (Entity e : host.ctx().level().getEntities((Entity) null, AREA, e -> e instanceof AbstractArrow)) {
            list.add((AbstractArrow) e);
        }
        list.sort(Comparator.comparingInt(Entity::getId));
        flying.keySet().removeIf(Entity::isRemoved);
        struck.removeIf(Entity::isRemoved);
        for (AbstractArrow arrow : list) {
            Vec3 v = arrow.getDeltaMovement();
            if (v.lengthSqr() > 1e-8) {
                flying.put(arrow, v);
                continue;
            }
            if (!struck.add(arrow)) {
                continue;
            }
            Vec3 last = flying.remove(arrow);
            Vec3 dir = last == null ? new Vec3(0, 0, 1) : last.normalize();
            Vec3 at = arrow.position();
            BlockHitResult hit = host.ctx().level().clip(new ClipContext(at.subtract(dir.scale(0.2)), at.add(dir.scale(0.5)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow));
            host.later(1, () -> {
                if (!arrow.isRemoved()) {
                    arrow.discard();
                }
            });
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            int cell = cellAt(hit.getBlockPos());
            if (cell < 0) {
                continue;
            }
            // OPEN: whether a teammate's arrow (not the plate player's) counts isn't visible in packets; any player's does here.
            ServerPlayer by = arrow.getOwner() instanceof ServerPlayer p ? p : plateBy;
            if (by != null) {
                strike(cell, by);
            }
        }
    }

    void strike(int cell, ServerPlayer by) {
        if (done() || board == null) {
            return;
        }
        TargetBoard.Strike s = board.strike(cell);
        if (s == TargetBoard.Strike.NONE) {
            return;
        }
        if (s == TargetBoard.Strike.SHOWN) {
            host.later(1, () -> setCell(cell, false));
        }
        if (board.complete()) {
            // OPEN: the credit goes to the shooter; the chat names the i4 player, who is usually both.
            complete(by, false);
        }
    }

    private void setCell(int cell, boolean lit) {
        host.ctx().blocks().set(cellPos(cell), lit ? Blocks.EMERALD_BLOCK.defaultBlockState()
            : Blocks.DYED_TERRACOTTA.blue().defaultBlockState());
    }

    /** On the plate; each emerald gets one hit, landing 4 ticks after it shows (~210 ms flight), a volley every 5 ticks. */
    // OPEN: the flight (4) and volley pace (5, a Terminator at 4 shots/s) are rounded, not this device's measurement.

}
