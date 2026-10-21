package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One dungeon mob's cached path (Smalldungeons ai/pathfinding.rs MobPath + next_steer_point +
 * find_path): room-scoped, budget-capped A* over a 2.5D column/floor-height grid. Nodes are
 * (x, floor y, z); 4-way steps cost 1; a step may rise 1 block or drop up to 3; a floor needs
 * one passable, non-lava block above it. If the goal can't be reached exactly, the path leads
 * to the reachable node closest to it (3D Manhattan). Mob and goal in different rooms (or no
 * progress at all) give no path, and the mob steers straight at the goal.
 */
final class MobPath {
    /** Ticks between path recomputes (0.5s). */
    static final int RECOMPUTE_INTERVAL_TICKS = 10;
    /** Recompute early once the goal has moved this far from where the last search aimed. */
    static final double RECOMPUTE_GOAL_DELTA = 3.0;
    /** Horizontal distance to a waypoint that counts as reaching it. */
    static final double WAYPOINT_ARRIVAL_DISTANCE = 0.6;
    /** Less horizontal movement than this in a tick is no progress. */
    static final double STUCK_MOVEMENT_EPSILON = 0.02;
    /** Ticks without progress before a forced recompute, ignoring the cooldown. */
    static final int STUCK_TICKS_THRESHOLD = 20;
    /** Hard cap on A* node expansions per search. */
    static final int MAX_EXPANDED_NODES = 4096;
    static final int MAX_STEP_UP = 1;
    static final int MAX_SAFE_DROP = 3;
    /** Headroom demanded above a floor (deliberately 1, not the mob's height: see pathfinding.rs). */
    private static final int CLEARANCE = 1;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Where to steer this tick, and whether that is a real waypoint (gates ledge jumps). */
    record Steer(Vec3 point, boolean onPath) {}

    /** Room::get_world_bounds. */
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    private List<Vec3> waypoints = List.of();
    private int currentIndex;
    private int recomputeCooldown;
    private Vec3 lastGoal = new Vec3(0, 0, 0);
    private Vec3 lastPosition = new Vec3(0, 0, 0);
    private int stuckTicks;

    /** next_steer_point. */
    Steer next(ServerLevel level, DungeonRun run, Vec3 mobPos, Vec3 goal) {
        recomputeCooldown = Math.max(0, recomputeCooldown - 1);
        if (horizontal(mobPos, lastPosition) < STUCK_MOVEMENT_EPSILON) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPosition = mobPos;
        boolean stuck = stuckTicks >= STUCK_TICKS_THRESHOLD;

        boolean needsRecompute = stuck
            || (recomputeCooldown == 0
                && (currentIndex >= waypoints.size() || lastGoal.distanceTo(goal) > RECOMPUTE_GOAL_DELTA));
        if (needsRecompute) {
            lastGoal = goal;
            recomputeCooldown = RECOMPUTE_INTERVAL_TICKS;
            stuckTicks = 0;
            List<Vec3> found = findPath(level, run, mobPos, goal);
            waypoints = found == null ? List.of() : found;
            currentIndex = 0;
        }

        // Horizontal only: a waypoint's y assumes a full block step, a stair tread is lower.
        while (currentIndex < waypoints.size() && horizontal(mobPos, waypoints.get(currentIndex)) < WAYPOINT_ARRIVAL_DISTANCE) {
            currentIndex++;
        }
        boolean onPath = currentIndex < waypoints.size();
        return new Steer(onPath ? waypoints.get(currentIndex) : goal, onPath);
    }

    /** The world bounds of the run's room [index], or null. */
    static Bounds bounds(DungeonRun run, int index) {
        if (index < 0 || index >= run.rooms.size()) {
            return null;
        }
        RoomState room = run.rooms.get(index);
        RoomData data = room.data();
        int[][] corners = {{0, 0}, {data.width() - 1, 0}, {0, data.length() - 1}, {data.width() - 1, data.length() - 1}};
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] corner : corners) {
            BlockPos world = room.placed.toWorld(corner[0], 0, corner[1]);
            minX = Math.min(minX, world.getX());
            maxX = Math.max(maxX, world.getX());
            minZ = Math.min(minZ, world.getZ());
            maxZ = Math.max(maxZ, world.getZ());
        }
        return new Bounds(minX, data.bottom(), minZ, maxX, data.bottom() + data.levels() - 1, maxZ);
    }

    /** Dungeon::get_room_at on floored coordinates. */
    static int roomAt(DungeonRun run, double x, double z) {
        return run.roomAt(Math.floor(x), Math.floor(z));
    }

    private record Open(float cost, long node) {}

    /** find_path: room-scoped A*, or null when there is nothing useful to steer along. */
    private static List<Vec3> findPath(ServerLevel level, DungeonRun run, Vec3 start, Vec3 goal) {
        return findPath(level, run, start, goal, MAX_EXPANDED_NODES);
    }

    static List<Vec3> findPath(ServerLevel level, DungeonRun run, Vec3 start, Vec3 goal, int maxNodes) {
        int startRoom = roomAt(run, start.x, start.z);
        int goalRoom = roomAt(run, goal.x, goal.z);
        if (startRoom < 0 || goalRoom < 0 || startRoom != goalRoom) {
            return null;
        }
        Bounds b = bounds(run, startRoom);
        if (b == null) {
            return null;
        }

        int sx = floor(start.x);
        int sz = floor(start.z);
        Integer startFloor = floorNear(level, b.minY, b.maxY, sx, sz, floor(start.y));
        if (startFloor == null) {
            return null;
        }
        int gx = floor(goal.x);
        int gz = floor(goal.z);
        Integer goalFloorFound = floorNear(level, b.minY, b.maxY, gx, gz, floor(goal.y));
        int goalFloor = goalFloorFound != null ? goalFloorFound : floor(goal.y);

        long startNode = key(sx, startFloor, sz);
        long goalNode = key(gx, goalFloor, gz);

        PriorityQueue<Open> open = new PriorityQueue<>((a, c) -> Float.compare(a.cost, c.cost));
        Map<Long, Float> bestG = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        bestG.put(startNode, 0f);
        open.add(new Open(heuristic(startNode, goalNode), startNode));

        int expanded = 0;
        long closestReachable = startNode;
        float closestReachableH = heuristic(startNode, goalNode);

        while (!open.isEmpty()) {
            long current = open.poll().node;
            if (!closed.add(current)) {
                continue;
            }
            // Full (x, y, z) equality: reaching the goal's column at a lower floor is not arriving.
            if (current == goalNode) {
                return reconstruct(parent, current);
            }
            expanded++;
            if (expanded > maxNodes) {
                break;
            }

            float g = bestG.get(current);
            int cx = x(current);
            int cy = y(current);
            int cz = z(current);
            for (int[] d : DIRECTIONS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < b.minX || nx > b.maxX || nz < b.minZ || nz > b.maxZ) {
                    continue;
                }
                Integer neighbourFloor = floorInWindow(level, cy, nx, nz);
                if (neighbourFloor == null) {
                    continue;
                }
                long neighbour = key(nx, neighbourFloor, nz);
                if (closed.contains(neighbour)) {
                    continue;
                }
                float tentative = g + 1f;
                Float known = bestG.get(neighbour);
                if (known == null || tentative < known) {
                    bestG.put(neighbour, tentative);
                    parent.put(neighbour, current);
                    float h = heuristic(neighbour, goalNode);
                    if (h < closestReachableH) {
                        closestReachableH = h;
                        closestReachable = neighbour;
                    }
                    open.add(new Open(tentative + h, neighbour));
                }
            }
        }

        if (closestReachable == startNode) {
            return null;
        }
        return reconstruct(parent, closestReachable);
    }

    /** 3D Manhattan distance (y included on purpose: see pathfinding.rs heuristic). */
    private static float heuristic(long a, long b) {
        return Math.abs(x(a) - x(b)) + Math.abs(z(a) - z(b)) + Math.abs(y(a) - y(b));
    }

    /** A standable floor at (x, z) from MAX_STEP_UP above [fromY] down to MAX_SAFE_DROP below, highest first. */
    private static Integer floorInWindow(ServerLevel level, int fromY, int x, int z) {
        for (int y = fromY + MAX_STEP_UP; y >= fromY - MAX_SAFE_DROP; y--) {
            if (standable(level, x, y, z)) {
                return y;
            }
        }
        return null;
    }

    /** A standable floor at (x, z) searching outward from [nearY] across the room's height. */
    private static Integer floorNear(ServerLevel level, int minY, int maxY, int x, int z, int nearY) {
        for (int offset = 0; offset <= Math.max(maxY - minY, 0); offset++) {
            int[] ys = {nearY - offset, nearY + offset};
            for (int y : ys) {
                if (y >= minY && y <= maxY && standable(level, x, y, z)) {
                    return y;
                }
            }
        }
        return null;
    }

    /** Solid, non-lava floor at y with CLEARANCE passable, non-lava blocks above it. */
    static boolean standable(ServerLevel level, int x, int y, int z) {
        BlockState floor = level.getBlockState(new BlockPos(x, y, z));
        if (passable(floor) || floor.is(Blocks.LAVA)) {
            return false;
        }
        for (int dy = 1; dy <= CLEARANCE; dy++) {
            BlockState above = level.getBlockState(new BlockPos(x, y + dy, z));
            if (!passable(above) || above.is(Blocks.LAVA)) {
                return false;
            }
        }
        return true;
    }

    /** is_block_passable: nothing to collide with. */
    static boolean passable(BlockState state) {
        return !state.isSolid();
    }

    private static List<Vec3> reconstruct(Map<Long, Long> parent, long current) {
        List<Vec3> path = new ArrayList<>();
        path.add(waypoint(current));
        Long p = parent.get(current);
        while (p != null) {
            path.add(waypoint(p));
            p = parent.get(p);
        }
        Collections.reverse(path);
        return path;
    }

    /** node_to_waypoint: feet position on top of the floor, centred on the block. */
    private static Vec3 waypoint(long node) {
        return new Vec3(x(node) + 0.5, y(node) + 1, z(node) + 0.5);
    }

    // Nodes packed into a long: 26 bits x, 26 bits z, 12 bits y.
    private static final int XZ_BIAS = 1 << 25;
    private static final int Y_BIAS = 2048;

    private static long key(int x, int y, int z) {
        return ((long) (x + XZ_BIAS) << 38) | ((long) (z + XZ_BIAS) << 12) | (long) (y + Y_BIAS);
    }

    private static int x(long key) {
        return (int) ((key >>> 38) & 0x3FFFFFF) - XZ_BIAS;
    }

    private static int z(long key) {
        return (int) ((key >>> 12) & 0x3FFFFFF) - XZ_BIAS;
    }

    private static int y(long key) {
        return (int) (key & 0xFFF) - Y_BIAS;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
