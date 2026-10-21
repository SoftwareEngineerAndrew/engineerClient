package net.rustclear.mod.engine.boss;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The boss phases' scripted block changes (the beacon, its glass, pylons, platforms, pillars,
 * gates): each goes in without neighbour updates (Hypixel's blocks don't react), and the first
 * change at a spot remembers what the arena had there, so the run's end puts the arena back.
 * The arena itself is built once per world session (BossArena), never on /rs.
 */
public final class BossBlocks {
    /** set_block flags: send to clients, no neighbour updates, no shape updates. */
    private static final int FLAGS = 2 | 16;

    private final ServerLevel level;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();

    public BossBlocks(ServerLevel level) {
        this.level = level;
    }

    public void set(BlockPos pos, BlockState state) {
        BlockPos at = pos.immutable();
        original.putIfAbsent(at, level.getBlockState(at));
        level.setBlock(at, state, FLAGS);
    }

    public BlockState get(BlockPos pos) {
        return level.getBlockState(pos);
    }

    /** Every changed block back as the arena had it. */
    public void restore() {
        for (Map.Entry<BlockPos, BlockState> e : original.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), FLAGS);
        }
        original.clear();
    }
}
