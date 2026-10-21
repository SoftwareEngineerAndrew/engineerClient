package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A recorded dungeon layout, parsed (Smalldungeons dungeon.rs parse_layout).
 *
 * The string is 36 two-digit cells of the 6x6 grid (00 empty, 01-06 special rooms, other numbers
 * group the cells of one normal room), then one character per possible door position
 * (0 normal, 1 wither, 2 blood, 3 entrance, anything else none).
 */
public final class Layout {
    public static final int ORIGIN_X = -200;
    public static final int ORIGIN_Z = -200;
    public static final int GRID = 6;
    public static final int CELL = 32;
    /** Rooms' floor corner and doors sit on this y; the room data carries its own heights. */
    public static final int ROOM_Y = 68;

    public enum DoorType { NORMAL, ENTRANCE, WITHER, BLOOD }

    /** A door between two cells, centred on (x, z). [alongX]: the doorway runs along x. */
    public record Door(int x, int z, boolean alongX, DoorType type) {}

    public record Segment(int x, int z) {}

    /** A place in the layout that needs a room of [type] and [shape]. */
    public record Slot(RoomType type, RoomShape shape, List<Segment> segments) {}

    /** The 60 door positions, in the order the layout string lists them. */
    public static final List<int[]> DOOR_POSITIONS = doorPositions();

    public final String source;
    public final List<Door> doors;
    public final List<Slot> slots;

    private Layout(String source, List<Door> doors, List<Slot> slots) {
        this.source = source;
        this.doors = doors;
        this.slots = slots;
    }

    /** A layout made in code rather than parsed (practice mode's lone room, see [Practice]). */
    static Layout of(String source, List<Door> doors, List<Slot> slots) {
        return new Layout(source, List.copyOf(doors), List.copyOf(slots));
    }

    private static List<int[]> doorPositions() {
        List<int[]> positions = new ArrayList<>();
        // Rows alternate: doors between columns (x = 31, 63, ...) then between rows (x = 15, 47, ...).
        for (int row = 0; row < 11; row++) {
            int z = ORIGIN_Z + 15 + row * 16;
            if (row % 2 == 0) {
                for (int col = 0; col < 5; col++) {
                    positions.add(new int[] {ORIGIN_X + 31 + col * 32, z});
                }
            } else {
                for (int col = 0; col < 6; col++) {
                    positions.add(new int[] {ORIGIN_X + 15 + col * 32, z});
                }
            }
        }
        return List.copyOf(positions);
    }

    public static Layout parse(String layout) {
        layout = layout.trim();
        if (layout.length() < 72 + DOOR_POSITIONS.size()) {
            throw new IllegalArgumentException("Layout string too short: " + layout.length());
        }
        List<Door> doors = new ArrayList<>();
        for (int i = 0; i < DOOR_POSITIONS.size(); i++) {
            DoorType type = switch (layout.charAt(72 + i)) {
                case '0' -> DoorType.NORMAL;
                case '1' -> DoorType.WITHER;
                case '2' -> DoorType.BLOOD;
                case '3' -> DoorType.ENTRANCE;
                default -> null;
            };
            if (type != null) {
                int[] pos = DOOR_POSITIONS.get(i);
                boolean alongX = ((pos[0] - ORIGIN_X) / 16) % 2 == 1;
                doors.add(new Door(pos[0], pos[1], alongX, type));
            }
        }

        List<Slot> slots = new ArrayList<>();
        Map<Integer, List<Segment>> normal = new LinkedHashMap<>();
        for (int i = 0; i < GRID * GRID; i++) {
            int x = i % GRID;
            int z = i / GRID;
            int id = Integer.parseInt(layout.substring(i * 2, i * 2 + 2));
            if (id == 0) {
                continue;
            }
            if (id <= 6) {
                RoomType type = switch (id) {
                    case 1 -> RoomType.ENTRANCE;
                    case 2 -> RoomType.FAIRY;
                    case 3 -> RoomType.BLOOD;
                    case 4 -> RoomType.PUZZLE;
                    case 5 -> RoomType.TRAP;
                    default -> RoomType.YELLOW;
                };
                RoomShape shape = type == RoomType.FAIRY ? RoomShape.ONE_BY_ONE : RoomShape.ONE_BY_ONE_END;
                slots.add(new Slot(type, shape, List.of(new Segment(x, z))));
                continue;
            }
            normal.computeIfAbsent(id, k -> new ArrayList<>()).add(new Segment(x, z));
        }
        List<List<Segment>> rooms = new ArrayList<>(normal.values());
        rooms.sort(Comparator.comparingInt(segments -> segments.get(0).z() * GRID + segments.get(0).x()));
        for (List<Segment> segments : rooms) {
            slots.add(new Slot(RoomType.NORMAL, shapeOf(segments, doors), List.copyOf(segments)));
        }
        return new Layout(layout, List.copyOf(doors), List.copyOf(slots));
    }

    /** RoomShape::from_segments. */
    public static RoomShape shapeOf(List<Segment> segments, List<Door> doors) {
        Set<Integer> xs = new HashSet<>();
        Set<Integer> zs = new HashSet<>();
        for (Segment s : segments) {
            xs.add(s.x());
            zs.add(s.z());
        }
        boolean notLong = xs.size() > 1 && zs.size() > 1;
        return switch (segments.size()) {
            case 1 -> oneByOne(segments.get(0), doors).shape;
            case 2 -> RoomShape.ONE_BY_TWO;
            case 3 -> notLong ? RoomShape.L : RoomShape.ONE_BY_THREE;
            case 4 -> notLong ? RoomShape.TWO_BY_TWO : RoomShape.ONE_BY_FOUR;
            default -> RoomShape.EMPTY;
        };
    }

    record ShapeAndFacing(RoomShape shape, Facing facing) {}

    /** Room::get_1x1_shape_and_type: a 1x1 room's shape and turn, from which sides have doors. */
    static ShapeAndFacing oneByOne(Segment segment, List<Door> doors) {
        int cx = segment.x() * CELL + 15 + ORIGIN_X;
        int cz = segment.z() * CELL + 15 + ORIGIN_Z;
        int[][] sides = {{cx, cz - 16}, {cx + 16, cz}, {cx, cz + 16}, {cx - 16, cz}};
        int bits = 0;
        for (int[] side : sides) {
            bits <<= 1;
            for (Door door : doors) {
                if (door.x() == side[0] && door.z() == side[1]) {
                    bits |= 1;
                    break;
                }
            }
        }
        return switch (bits) {
            case 0b1111 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_CROSS, Facing.NORTH);
            case 0b1000 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_END, Facing.NORTH);
            case 0b0100 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_END, Facing.EAST);
            case 0b0010 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_END, Facing.SOUTH);
            case 0b0001 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_END, Facing.WEST);
            case 0b0101 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_STRAIGHT, Facing.NORTH);
            case 0b1010 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_STRAIGHT, Facing.EAST);
            case 0b0011 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_BEND, Facing.NORTH);
            case 0b1001 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_BEND, Facing.EAST);
            case 0b1100 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_BEND, Facing.SOUTH);
            case 0b0110 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_BEND, Facing.WEST);
            case 0b1011 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_TRIPLE, Facing.NORTH);
            case 0b1101 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_TRIPLE, Facing.EAST);
            case 0b1110 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_TRIPLE, Facing.SOUTH);
            case 0b0111 -> new ShapeAndFacing(RoomShape.ONE_BY_ONE_TRIPLE, Facing.WEST);
            default -> new ShapeAndFacing(RoomShape.ONE_BY_ONE, Facing.NORTH);
        };
    }

    /** Room::get_rotation_from_segments. [segments] sorted by x (then z), as Room::new sorts them. */
    public static Facing facingOf(List<Segment> segments, List<Door> doors) {
        Set<Integer> xs = new HashSet<>();
        Set<Integer> zs = new HashSet<>();
        for (Segment s : segments) {
            xs.add(s.x());
            zs.add(s.z());
        }
        boolean notLong = xs.size() > 1 && zs.size() > 1;
        switch (segments.size()) {
            case 1:
                return oneByOne(segments.get(0), doors).facing;
            case 3:
                if (notLong) {
                    Segment corner = null;
                    for (Segment a : segments) {
                        boolean touchesAll = true;
                        for (Segment b : segments) {
                            if (Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) > 1) {
                                touchesAll = false;
                                break;
                            }
                        }
                        if (touchesAll) {
                            corner = a;
                            break;
                        }
                    }
                    if (corner == null) {
                        throw new IllegalArgumentException("Invalid L room: " + segments);
                    }
                    int minX = segments.stream().mapToInt(Segment::x).min().orElseThrow();
                    int minZ = segments.stream().mapToInt(Segment::z).min().orElseThrow();
                    int maxX = segments.stream().mapToInt(Segment::x).max().orElseThrow();
                    int maxZ = segments.stream().mapToInt(Segment::z).max().orElseThrow();
                    if (corner.x() == minX && corner.z() == minZ) return Facing.EAST;
                    if (corner.x() == maxX && corner.z() == minZ) return Facing.SOUTH;
                    if (corner.x() == maxX && corner.z() == maxZ) return Facing.WEST;
                    return Facing.NORTH;
                }
                return zs.size() == 1 ? Facing.NORTH : Facing.EAST;
            case 4:
                if (xs.size() == 2 && zs.size() == 2) {
                    return Facing.NORTH;
                }
                return zs.size() == 1 ? Facing.NORTH : Facing.EAST;
            default:
                return zs.size() == 1 ? Facing.NORTH : Facing.EAST;
        }
    }
}
