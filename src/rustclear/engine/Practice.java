package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;
import net.minecraft.server.level.ServerPlayer;

/**
 * Practice mode (Smalldungeons dungeon/practice.rs): one room, picked by name, built alone into
 * the normal dungeon grid at cell (1, 1), with players spawned just inside a chosen (or random)
 * door. `/rs` rebuilds it from scratch and puts everyone back at that door. A secret-route timer
 * starts at the first move and stops once the room's found secrets reach the target.
 *
 * As on the server, a practice room spawns no mobs (dungeon.rs gates mob spawning on
 * `!practice_room`), and has no Mort or Mort chatter; secrets and puzzles work as in a run.
 */
final class Practice {
    /** Where the room's cells start in the 6x6 grid: margin on every side for a 1x4 or a 2x2. */
    private static final int GRID_X = 1;
    private static final int GRID_Z = 1;
    static final List<String> DOOR_CHOICES = List.of("north", "east", "south", "west", "random");
    private static final String[] SIDE_LABELS = {"north", "east", "south", "west"};
    /** Per side (0 north, 1 east, 2 south, 3 west): the outward x/z step. */
    private static final int[][] SIDE_OFFSET = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    /** Facing into the room from a door on each side (vanilla yaw: south 0, west 90, north 180, east 270). */
    private static final float[] INWARD_YAW = {0f, 90f, 180f, 270f};
    private static final int HIGHER_BLAZE_BOTTOM = 65;
    private static final int LOWER_BLAZE_BOTTOM = 15;

    private Practice() {
    }

    /** A room to practise: its data, and the Water Board maze to force (-1 for a random one). */
    record Pick(RoomData data, int waterPattern) {}

    /** A spawn point just inside one of the room's doors, facing in. */
    record DoorSpawn(String label, double x, double y, double z, float yaw) {}

    record Built(DungeonPlan plan, List<DoorSpawn> spawns) {}

    private static boolean supported(RoomData data) {
        return data.type() != RoomType.BOSS && data.shape() != RoomShape.FOUR_BY_FOUR && data.shape() != RoomShape.EMPTY;
    }

    /**
     * supported_room_names: every buildable room's name with spaces as underscores, except "Blaze"
     * (two rooms share it) which becomes higher_blaze / lower_blaze, plus water_board_0..3.
     */
    static List<String> roomNames(RoomStore store) {
        TreeSet<String> names = new TreeSet<>();
        for (RoomData data : store.rooms) {
            if (supported(data) && !data.name().equals("Blaze")) {
                names.add(data.name().replace(' ', '_'));
            }
        }
        names.add("higher_blaze");
        names.add("lower_blaze");
        for (int pattern = 0; pattern < 4; pattern++) {
            names.add("water_board_" + pattern);
        }
        return List.copyOf(names);
    }

    /** find_room_data: matches a name case-insensitively, underscores standing for spaces. Null when unknown. */
    static Pick find(RoomStore store, String roomName) {
        String lower = roomName.toLowerCase(Locale.ROOT);
        if (lower.equals("higher_blaze") || lower.equals("lower_blaze")) {
            int bottom = lower.equals("higher_blaze") ? HIGHER_BLAZE_BOTTOM : LOWER_BLAZE_BOTTOM;
            for (RoomData data : store.rooms) {
                if (data.name().equals("Blaze") && data.bottom() == bottom) {
                    return new Pick(data, -1);
                }
            }
            return null;
        }
        if (lower.startsWith("water_board_")) {
            try {
                int pattern = Integer.parseInt(lower.substring("water_board_".length()));
                if (pattern >= 0 && pattern < 4) {
                    for (RoomData data : store.rooms) {
                        if (data.name().equals("Water Board")) {
                            return new Pick(data, pattern);
                        }
                    }
                    return null;
                }
            } catch (NumberFormatException ignored) {
                // Not a pattern number: fall through to the plain name match.
            }
        }
        String normalized = roomName.replace('_', ' ');
        for (RoomData data : store.rooms) {
            if (supported(data) && data.name().equalsIgnoreCase(normalized)) {
                return new Pick(data, -1);
            }
        }
        return null;
    }

    /** shape_segments: the cells of each shape, before moving to (GRID_X, GRID_Z). */
    private static int[][] shapeSegments(RoomShape shape) {
        return switch (shape) {
            case ONE_BY_TWO -> new int[][] {{0, 0}, {1, 0}};
            case ONE_BY_THREE -> new int[][] {{0, 0}, {1, 0}, {2, 0}};
            case ONE_BY_FOUR -> new int[][] {{0, 0}, {1, 0}, {2, 0}, {3, 0}};
            case L -> new int[][] {{0, 0}, {1, 0}, {0, 1}};
            case TWO_BY_TWO -> new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
            default -> new int[][] {{0, 0}};
        };
    }

    /**
     * shape_doors: {cell x, cell z, side} per door. For 1x1 shapes the exact reverse of the door
     * pattern Layout.oneByOne reads them from (so they come out facing north); multi-cell shapes
     * get a door at each open end, each arm's end, or each side of a 2x2.
     */
    private static int[][] shapeDoors(RoomShape shape) {
        return switch (shape) {
            case ONE_BY_ONE_END -> new int[][] {{0, 0, 0}};
            case ONE_BY_ONE_STRAIGHT -> new int[][] {{0, 0, 1}, {0, 0, 3}};
            case ONE_BY_ONE_BEND -> new int[][] {{0, 0, 2}, {0, 0, 3}};
            case ONE_BY_ONE_TRIPLE -> new int[][] {{0, 0, 0}, {0, 0, 2}, {0, 0, 3}};
            case ONE_BY_TWO -> new int[][] {{0, 0, 3}, {1, 0, 1}};
            case ONE_BY_THREE -> new int[][] {{0, 0, 3}, {2, 0, 1}};
            case ONE_BY_FOUR -> new int[][] {{0, 0, 3}, {3, 0, 1}};
            case L -> new int[][] {{1, 0, 1}, {0, 1, 2}};
            case TWO_BY_TWO -> new int[][] {{0, 0, 0}, {1, 0, 1}, {1, 1, 2}, {0, 1, 3}};
            // ONE_BY_ONE (fairy, yellow) and ONE_BY_ONE_CROSS: every side.
            default -> new int[][] {{0, 0, 0}, {0, 0, 1}, {0, 0, 2}, {0, 0, 3}};
        };
    }

    /** build_practice_dungeon: a one-room plan, plus a spawn inside each of its doors. */
    static Built build(RoomData data) {
        List<Layout.Segment> segments = new ArrayList<>();
        for (int[] cell : shapeSegments(data.shape())) {
            segments.add(new Layout.Segment(cell[0] + GRID_X, cell[1] + GRID_Z));
        }
        List<Layout.Door> doors = new ArrayList<>();
        List<DoorSpawn> spawns = new ArrayList<>();
        for (int[] d : shapeDoors(data.shape())) {
            int cx = (d[0] + GRID_X) * Layout.CELL + 15 + Layout.ORIGIN_X;
            int cz = (d[1] + GRID_Z) * Layout.CELL + 15 + Layout.ORIGIN_Z;
            int ox = SIDE_OFFSET[d[2]][0];
            int oz = SIDE_OFFSET[d[2]][1];
            int doorX = cx + ox * 16;
            int doorZ = cz + oz * 16;
            // Same rule Layout.parse gives the recorded door positions.
            boolean alongX = ((doorX - Layout.ORIGIN_X) / 16) % 2 == 1;
            doors.add(new Layout.Door(doorX, doorZ, alongX, Layout.DoorType.NORMAL));
            spawns.add(new DoorSpawn(SIDE_LABELS[d[2]], doorX - ox * 2 + 0.5, 69.0, doorZ - oz * 2 + 0.5, INWARD_YAW[d[2]]));
        }
        Layout.Slot slot = new Layout.Slot(data.type(), data.shape(), List.copyOf(segments));
        Layout layout = Layout.of("practice:" + data.name(), doors, List.of(slot));
        DungeonPlan plan = new DungeonPlan(layout, List.of(PlacedRoom.of(slot, data, layout.doors, null)));
        return new Built(plan, List.copyOf(spawns));
    }

    /** "random" or a door label; null when the room has no such door. */
    static DoorSpawn chooseDoor(List<DoorSpawn> spawns, String choice, Random random) {
        if (spawns.isEmpty()) {
            return null;
        }
        if (choice == null || choice.equalsIgnoreCase("random")) {
            return spawns.get(random.nextInt(spawns.size()));
        }
        for (DoorSpawn spawn : spawns) {
            if (spawn.label().equalsIgnoreCase(choice)) {
                return spawn;
            }
        }
        return null;
    }

    static String validDoors(List<DoorSpawn> spawns) {
        return String.join(", ", spawns.stream().map(DoorSpawn::label).toList());
    }

    /**
     * The secret-route timer (Dungeon::tick's practice block): starts the first tick a player has
     * moved off the spawn, finishes when the room's found secrets reach [target], and shows on the
     * action bar - gold while running, green for a moment after each secret and once done.
     */
    static final class Route {
        private static final int FLASH_TICKS = 10;

        final int target;
        final String prefix;
        final DoorSpawn spawn;
        private long startTick = -1;
        private boolean finished;
        private double finishSeconds;
        private int lastFound;
        private int flashTicks;

        /** [target] 0: no timer (a room with no secrets and no target given). */
        Route(int target, String prefix, DoorSpawn spawn) {
            this.target = target;
            this.prefix = prefix;
            this.spawn = spawn;
        }

        /** Ticks the route has run, for the sidebar's and tab list's elapsed time. */
        long elapsedTicks(long runTicks) {
            if (finished) {
                return (long) (finishSeconds * 20.0);
            }
            return startTick < 0 ? 0 : runTicks - startTick;
        }

        void tick(DungeonRun run, long runTicks) {
            if (target <= 0) {
                return;
            }
            List<ServerPlayer> players = run.level.players();
            if (startTick < 0) {
                for (ServerPlayer player : players) {
                    double dx = player.getX() - spawn.x();
                    double dy = player.getY() - spawn.y();
                    double dz = player.getZ() - spawn.z();
                    if (dx * dx + dy * dy + dz * dz > 0.0004) {
                        startTick = runTicks;
                        break;
                    }
                }
            }
            int found = run.rooms.isEmpty() ? 0 : run.rooms.get(0).foundSecrets;
            if (found > lastFound) {
                flashTicks = FLASH_TICKS;
            }
            lastFound = found;
            flashTicks = Math.max(0, flashTicks - 1);

            if (!finished && startTick >= 0 && found >= target) {
                finished = true;
                finishSeconds = (runTicks - startTick) * 0.05;
                run.broadcast(String.format(Locale.ROOT, "§f%s  §fTime: §a%.2fs", prefix, finishSeconds));
            }

            double seconds = finished ? finishSeconds : startTick < 0 ? 0.0 : (runTicks - startTick) * 0.05;
            String color = finished || flashTicks > 0 ? "§a" : "§6";
            String text = String.format(Locale.ROOT, "%s%.2fs", color, seconds);
            for (ServerPlayer player : players) {
                player.sendOverlayMessage(EngineItems.text(text));
            }
        }
    }
}
