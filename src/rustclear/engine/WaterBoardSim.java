package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;

/**
 * WaterSim (Smalldungeons server/world/fluid.rs): the server's own 1.8 water flow, limited to an
 * exact set of cells. Blocks are 1.8 ids as the puzzle reads and writes them: still water (9) and
 * flowing water (8) with their level, {@link #AIR}, or {@link #SOLID} for anything else.
 */
final class WaterBoardSim {
    static final int AIR = 0;
    static final int SOLID = -1;
    static final int FLOWING = 8 << 4;
    static final int STILL = 9 << 4;
    private static final int MAX_DECAY = 7;
    private static final int FALLING_BIT = 0x8;
    private static final long TICK_DELAY = 5;
    private static final int DROP_OFF_LOOKAHEAD = 4;
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final ToIntFunction<BlockPos> get;
    private final ObjIntConsumer<BlockPos> set;
    private final Set<BlockPos> sources = new HashSet<>();
    private final Map<BlockPos, Long> scheduled = new HashMap<>();
    private final Set<BlockPos> valid;
    private final Set<BlockPos> terminals = new HashSet<>();

    WaterBoardSim(Set<BlockPos> valid, ToIntFunction<BlockPos> get, ObjIntConsumer<BlockPos> set) {
        this.valid = valid;
        this.get = get;
        this.set = set;
    }

    /** add_terminal: an output cell, fed only from above, that never spreads or supplies sideways. */
    void addTerminal(BlockPos pos) {
        terminals.add(pos);
    }

    boolean isActive() {
        return !sources.isEmpty() || !scheduled.isEmpty();
    }

    void addSource(BlockPos pos, long now) {
        sources.add(pos);
        setIfChanged(pos, STILL);
        schedule(pos, now);
        scheduleNeighbors(pos, now);
    }

    void removeSource(BlockPos pos, long now) {
        if (sources.remove(pos)) {
            setIfChanged(pos, AIR);
            scheduleNeighbors(pos, now);
        }
    }

    /** notify_neighbors: a plain block at [pos] changed. */
    void notifyNeighbors(BlockPos pos, long now) {
        scheduleNeighbors(pos, now);
        if (isWater(get.applyAsInt(pos))) {
            schedule(pos, now);
        }
    }

    /** tick: every update that has come due. */
    void tick(long now) {
        List<BlockPos> due = new ArrayList<>();
        for (Map.Entry<BlockPos, Long> e : scheduled.entrySet()) {
            if (e.getValue() <= now) {
                due.add(e.getKey());
            }
        }
        for (BlockPos pos : due) {
            scheduled.remove(pos);
            updateCell(pos, now);
        }
    }

    private void schedule(BlockPos pos, long now) {
        if (valid.contains(pos)) {
            scheduled.merge(pos, now + TICK_DELAY, Math::min);
        }
    }

    private void scheduleNeighbors(BlockPos pos, long now) {
        schedule(pos.above(), now);
        schedule(pos.below(), now);
        for (int[] d : HORIZONTAL) {
            schedule(side(pos, d), now);
        }
    }

    /** update_cell: one vanilla updateTick for a water cell. */
    private void updateCell(BlockPos pos, long now) {
        if (!valid.contains(pos)) {
            return;
        }
        int current = get.applyAsInt(pos);
        if (!isWater(current)) {
            sources.remove(pos);
            return;
        }
        if (!sources.contains(pos)) {
            int supply = computeSupply(pos);
            if (supply < 0) {
                setIfChanged(pos, AIR);
                scheduleNeighbors(pos, now);
                return;
            }
            setIfChanged(pos, FLOWING | supply);
            if (supply != flowingRaw(current)) {
                scheduleNeighbors(pos, now);
            }
            if (terminals.contains(pos)) {
                return;
            }
            maybePromoteToSource(pos, now);
        }
        trySpread(pos, now);
    }

    /** maybe_promote_to_source: vanilla's infinite source rule. */
    private void maybePromoteToSource(BlockPos pos, long now) {
        if (sources.contains(pos)) {
            return;
        }
        int count = 0;
        for (int[] d : HORIZONTAL) {
            if (isSourceBlock(side(pos, d))) {
                count++;
            }
        }
        if (count < 2) {
            return;
        }
        BlockPos below = pos.below();
        if (isSolid(get.applyAsInt(below)) || isSourceBlock(below)) {
            sources.add(pos);
            setIfChanged(pos, STILL);
            scheduleNeighbors(pos, now);
        }
    }

    private boolean isSourceBlock(BlockPos pos) {
        return sources.contains(pos) || get.applyAsInt(pos) == STILL;
    }

    /** try_spread: fall if possible, otherwise spread toward the nearest drop-off. */
    private void trySpread(BlockPos pos, long now) {
        if (terminals.contains(pos)) {
            return;
        }
        int level = ownDecay(get.applyAsInt(pos));
        BlockPos below = pos.below();
        if (valid.contains(below) && canFlowInto(get.applyAsInt(below), 0)) {
            setIfChanged(below, FLOWING | FALLING_BIT);
            schedule(below, now);
            return;
        }
        if (level >= MAX_DECAY) {
            return;
        }
        int newLevel = level + 1;
        List<int[]> open = new ArrayList<>();
        for (int[] d : HORIZONTAL) {
            BlockPos n = side(pos, d);
            if (valid.contains(n) && canFlowInto(get.applyAsInt(n), newLevel)) {
                open.add(d);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        int[] costs = new int[open.size()];
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < costs.length; i++) {
            int[] d = open.get(i);
            costs[i] = flowCostSearch(side(pos, d), 1, d);
            best = Math.min(best, costs[i]);
        }
        for (int i = 0; i < costs.length; i++) {
            if (best == Integer.MAX_VALUE || costs[i] == best) {
                BlockPos n = side(pos, open.get(i));
                setIfChanged(n, FLOWING | newLevel);
                schedule(n, now);
            }
        }
    }

    /** flow_cost_search: vanilla's corner-aware search for the nearest drop-off. */
    private int flowCostSearch(BlockPos pos, int distance, int[] cameFrom) {
        if (!valid.contains(pos)) {
            return Integer.MAX_VALUE;
        }
        BlockPos below = pos.below();
        if (valid.contains(below) && isPassable(get.applyAsInt(below))) {
            return distance;
        }
        if (distance >= DROP_OFF_LOOKAHEAD || !isPassable(get.applyAsInt(pos))) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (int[] d : HORIZONTAL) {
            if (d[0] == -cameFrom[0] && d[1] == -cameFrom[1]) {
                continue;
            }
            best = Math.min(best, flowCostSearch(side(pos, d), distance + 1, d));
        }
        return best;
    }

    /** compute_supply: the cell's raw level (falling bit included), or -1 when nothing feeds it. */
    private int computeSupply(BlockPos pos) {
        if (isWater(get.applyAsInt(pos.above()))) {
            return FALLING_BIT;
        }
        if (terminals.contains(pos)) {
            return -1;
        }
        int best = -1;
        for (int[] d : HORIZONTAL) {
            BlockPos n = side(pos, d);
            if (terminals.contains(n)) {
                continue;
            }
            int decay = neighborSupplyDecay(get.applyAsInt(n));
            if (decay >= 0) {
                best = best < 0 ? decay : Math.min(best, decay);
            }
        }
        return best >= 0 && best < MAX_DECAY ? best + 1 : -1;
    }

    private void setIfChanged(BlockPos pos, int block) {
        if (get.applyAsInt(pos) != block) {
            set.accept(pos, block);
        }
    }

    private static BlockPos side(BlockPos pos, int[] d) {
        return new BlockPos(pos.getX() + d[0], pos.getY(), pos.getZ() + d[1]);
    }

    static boolean isWater(int block) {
        return block >= 0 && (block >> 4 == 8 || block >> 4 == 9);
    }

    private static boolean isSolid(int block) {
        return block != AIR && !isWater(block);
    }

    private static boolean isPassable(int block) {
        return block == AIR || isWater(block);
    }

    private static boolean isFlowing(int block) {
        return block >= 0 && block >> 4 == 8;
    }

    private static int ownDecay(int block) {
        if (isFlowing(block)) {
            return block & MAX_DECAY;
        }
        return isWater(block) ? 0 : MAX_DECAY;
    }

    private static int flowingRaw(int block) {
        return isFlowing(block) ? block & 15 : 255;
    }

    private static boolean canFlowInto(int block, int newDecay) {
        if (block == AIR) {
            return true;
        }
        return isFlowing(block) && (block & MAX_DECAY) > newDecay;
    }

    private static int neighborSupplyDecay(int block) {
        if (isFlowing(block)) {
            return (block & FALLING_BIT) != 0 ? 0 : block & MAX_DECAY;
        }
        return isWater(block) ? block & MAX_DECAY : -1;
    }
}
