package net.rustclear.mod.engine.boss.goldor.device;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.rustclear.mod.engine.EngineItems;

/**
 * Arrow Align, section 3 (boss-p3-goldor.md §6.3): item frames at x -2, y 120-124, z 75-79 on one
 * of eight boards ([ArrowLayouts], drawn uniformly: 27 recorded boards, 2-6 of each), arrows plus
 * a lime wool "Start" and red wool "End" frames. Parties set it before P3, so it starts solved but
 * for one arrow one click short. A right click on a frame turns it one step (item_frame.rotate_item,
 * PLAYERS, 1, 1, at the frame); the click that solves the last arrow completes the device in the
 * same tick (chat and frame change in one tick in the recordings).
 *
 * The wall behind (x -3) is blue terracotta with sea lanterns behind every wool frame and some
 * arrow frames; it doesn't follow clicks, and on completion every frame's cell turns sea lantern
 * in that tick (27 of 27 completions: only frame cells, never a wool cell, which is lit already).
 */
final class ArrowAlign extends Device {
    static final int X_FRAME = -2;
    static final int X_WALL = -3;
    /**
     * An arrow cell's wall is lit at the start this often: 47 of 323 arrow cells were already lantern
     * when their board completed.
     */
    static final double PRE_LIT = 47.0 / 323.0;

    private int board;
    /** Each frame by its spot (y, z packed as y * 100 + z). */
    private final Map<Integer, ItemFrame> frames = new LinkedHashMap<>();

    ArrowAlign(Host host) {
        super(host);
    }

    @Override
    public String label() {
        return "Arrows";
    }

    @Override
    public int section() {
        return 3;
    }

    @Override
    public void start() {
        board = host.random().nextInt(ArrowLayouts.BOARDS.length);
        int[] off = ArrowLayouts.unsolved(board);
        for (int y = 120; y <= 124; y++) {
            for (int z = ArrowLayouts.Z0; z <= ArrowLayouts.Z0 + 4; z++) {
                char c = ArrowLayouts.cell(board, y, z);
                boolean lantern = c == 'S' || c == 'E' || ArrowLayouts.isArrow(c) && host.random().nextDouble() < PRE_LIT;
                host.ctx().blocks().set(new BlockPos(X_WALL, y, z),
                    lantern ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.DYED_TERRACOTTA.blue().defaultBlockState());
                if (c == '.') {
                    continue;
                }
                ItemFrame f = new ItemFrame(host.ctx().level(), new BlockPos(X_FRAME, y, z), Direction.EAST);
                f.setPermanentlyInvulnerable(true);
                if (ArrowLayouts.isArrow(c)) {
                    f.setItem(new ItemStack(Items.ARROW), false);
                    int solved = c - '0';
                    f.setRotation(off != null && off[0] == y && off[1] == z ? (solved + 7) % 8 : solved);
                } else {
                    f.setItem(c == 'S' ? EngineItems.named(Items.WOOL.lime(), "§aStart")
                        : EngineItems.named(Items.WOOL.red(), "§cEnd"), false);
                }
                host.ctx().level().addFreshEntity(f);
                frames.put(y * 100 + z, host.ctx().entities().add(f));
            }
        }
    }

    private Integer spotOf(Entity entity) {
        for (Map.Entry<Integer, ItemFrame> e : frames.entrySet()) {
            if (e.getValue() == entity) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    public boolean useEntity(ServerPlayer player, Entity entity) {
        Integer spot = spotOf(entity);
        if (spot == null) {
            return false;
        }
        if (!host.live() || done()) {
            return true;
        }
        ItemFrame f = frames.get(spot);
        // OPEN: the wool frames turn too (as vanilla's would); not seen either way.
        f.setRotation((f.getRotation() + 1) % 8);
        host.ctx().level().playSound(null, f.getX(), f.getY(), f.getZ(), SoundEvents.ITEM_FRAME_ROTATE_ITEM,
            SoundSource.PLAYERS, 1f, 1f);
        if (solved()) {
            for (int s : frames.keySet()) {
                host.ctx().blocks().set(new BlockPos(X_WALL, s / 100, s % 100), Blocks.SEA_LANTERN.defaultBlockState());
            }
            complete(player, false);
        }
        return true;
    }

    /** A left click on a frame does nothing (it would knock the item out of a vanilla frame). */
    @Override
    public boolean attackEntity(ServerPlayer player, Entity entity) {
        return spotOf(entity) != null;
    }

    private boolean solved() {
        for (Map.Entry<Integer, ItemFrame> e : frames.entrySet()) {
            char c = ArrowLayouts.cell(board, e.getKey() / 100, e.getKey() % 100);
            if (ArrowLayouts.isArrow(c) && e.getValue().getRotation() != c - '0') {
                return false;
            }
        }
        return true;
    }

    @Override
    public void dispose() {
        for (ItemFrame f : frames.values()) {
            host.ctx().entities().discard(f);
        }
        frames.clear();
    }

    /** Turn the first arrow that's off, a click every 3 ticks. */
    // OPEN: the 3-tick pace is a guess.

}
