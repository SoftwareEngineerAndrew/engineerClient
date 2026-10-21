package net.rustclear.mod.engine;

import java.util.List;

/**
 * The dungeon map's 128x128 pixels, drawn as rooms are found (Smalldungeons map.rs). Colours are
 * map colour ids (base * 4 + shade), the same numbers in 1.8 and today.
 */
public final class DungeonMap {
    static final byte RED = 4 * 4 + 2;
    static final byte GREEN = 7 * 4 + 2;
    static final byte ORANGE = 15 * 4 + 2;
    static final byte PURPLE = 16 * 4 + 2;
    static final byte PINK = 20 * 4 + 2;
    static final byte YELLOW = 18 * 4 + 2;
    static final byte BROWN = 63;
    static final byte GRAY = 21 * 4 + 1;
    static final byte CLEARED = 8 * 4 + 2;
    static final byte FAILED = 18;
    static final byte BLACK = (byte) (29 * 4 + 3);

    private static final int[][] QUESTION_MARK = {
        {0, 1}, {1, 0}, {2, 0}, {3, 0}, {4, 1}, {4, 2}, {3, 3}, {2, 4}, {2, 5}, {2, 7}, {2, 8},
    };
    private static final int[][] CHECKMARK = {
        {7, 0}, {8, 0}, {6, 1}, {7, 1}, {8, 1}, {5, 2}, {6, 2}, {7, 2}, {4, 3}, {5, 3},
        {6, 3}, {0, 4}, {1, 4}, {3, 4}, {4, 4}, {5, 4}, {0, 5}, {1, 5}, {2, 5}, {3, 5},
        {4, 5}, {0, 6}, {1, 6}, {2, 6}, {3, 6}, {1, 7}, {2, 7}, {3, 7}, {1, 8}, {2, 8},
    };
    private static final int[][] X_MARK = {
        {0, 0}, {1, 0}, {7, 0}, {8, 0}, {0, 1}, {1, 1}, {7, 1}, {8, 1},
        {1, 2}, {2, 2}, {6, 2}, {7, 2}, {2, 3}, {3, 3}, {5, 3}, {6, 3},
        {3, 4}, {4, 4}, {5, 4}, {2, 5}, {3, 5}, {5, 5}, {6, 5},
        {1, 6}, {2, 6}, {6, 6}, {7, 6}, {0, 7}, {1, 7}, {7, 7}, {8, 7}, {0, 8}, {8, 8},
    };

    public final byte[] pixels = new byte[128 * 128];
    private final int offsetX;
    private final int offsetY;
    /** Something changed since the last [takeDirty]. */
    private boolean dirty;

    public DungeonMap(int gridMaxX, int gridMaxZ) {
        this.offsetX = offset(gridMaxX + 1);
        this.offsetY = offset(gridMaxZ + 1);
    }

    /**
     * Where the first room column starts on the map. Hypixel puts a 6-room-wide map at pixel 5
     * and a 5-wide one at 11, and dtMap and Odin tell a map's width from that start, so match it.
     */
    private static int offset(int rooms) {
        return switch (rooms) {
            case 6 -> 5;
            case 5 -> 11;
            default -> (128 - rooms * 20) / 2;
        };
    }

    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    /** A world position as a map icon position (-128..127 each). */
    public byte[] toIcon(double worldX, double worldZ) {
        double perBlock = 20.0 / 32.0;
        double px = (worldX - Layout.ORIGIN_X) * perBlock + offsetX;
        double pz = (worldZ - Layout.ORIGIN_Z) * perBlock + offsetY;
        return new byte[] {
            (byte) Math.max(-128, Math.min(127, Math.round(px * 2 - 128))),
            (byte) Math.max(-128, Math.min(127, Math.round(pz * 2 - 128))),
        };
    }

    private void set(int x, int y, byte color) {
        x += offsetX;
        y += offsetY;
        if (x >= 0 && y >= 0 && x < 128 && y < 128) {
            pixels[y * 128 + x] = color;
            dirty = true;
        }
    }

    private void fill(int x, int y, int width, int height, byte color) {
        for (int i = x; i < x + width; i++) {
            for (int j = y; j < y + height; j++) {
                set(i, j, color);
            }
        }
    }

    /** A practice room's stand-in entrance: a green square at an empty grid cell, for mods that anchor on it. */
    public void drawPracticeEntrance(int cellX, int cellZ) {
        fill(cellX * 20, cellZ * 20, 16, 16, roomColor(RoomType.ENTRANCE));
    }

    /** DungeonMap::draw_room. */
    public void drawRoom(List<RoomState> rooms, List<DoorState> doors, int roomIndex) {
        RoomState room = rooms.get(roomIndex);
        byte color = roomColor(room.type());
        List<Layout.Segment> segments = room.segments();
        for (int s = 0; s < segments.size(); s++) {
            Layout.Segment segment = segments.get(s);
            int x = segment.x() * 20;
            int y = segment.z() * 20;
            fill(x, y, 16, 16, color);
            if (has(segments, segment.x() + 1, segment.z())) {
                fill(x + 16, y, 4, 16, color);
            }
            if (has(segments, segment.x(), segment.z() + 1)) {
                fill(x, y + 16, 16, 4, color);
            }
            for (RoomState.Neighbour neighbour : room.neighbours.get(s)) {
                RoomState other = rooms.get(neighbour.roomIndex());
                DoorState door = doors.get(neighbour.doorIndex());
                int dx = x + 6;
                int dy = y + 6;
                int side = neighbour.side();
                switch (side) {
                    case 0 -> dy -= 10;
                    case 1 -> dx += 10;
                    case 2 -> dy += 10;
                    default -> dx -= 10;
                }
                int width = door.door.alongX() ? 4 : 5;
                int height = door.door.alongX() ? 5 : 4;
                int ox = x + (side == 1 ? 20 : side == 3 ? -20 : 0);
                int oy = y + (side == 2 ? 20 : side == 0 ? -20 : 0);
                if (other.entered) {
                    fill(dx, dy, width, height, doorColor(room, other));
                } else if (door.opened) {
                    // An opened door shows the room beyond in its real colour before anyone walks in.
                    fill(dx, dy, width, height, doorColor(room, other));
                    fill(ox, oy, 16, 16, roomColor(other.type()));
                } else {
                    byte doorColor = switch (door.type()) {
                        case WITHER -> BLACK;
                        case BLOOD -> RED;
                        default -> GRAY;
                    };
                    fill(dx, dy, width, height, doorColor);
                    fill(ox, oy, 16, 16, GRAY);
                    for (int[] p : QUESTION_MARK) {
                        set(ox + p[0] + 5, oy + p[1] + 5, BLACK);
                    }
                }
            }
        }
        if (room.data().shape() == RoomShape.TWO_BY_TWO) {
            fill(segments.get(0).x() * 20 + 16, segments.get(0).z() * 20 + 16, 4, 4, color);
        }

        boolean cleared = room.cleared();
        if (room.type() != RoomType.ENTRANCE && room.type() != RoomType.FAIRY && cleared) {
            int x = segments.get(0).x() * 20 + 4;
            int y = segments.get(0).z() * 20 + 4;
            if (room.puzzleFailed) {
                for (int[] p : X_MARK) {
                    set(x + p[0], y + p[1], FAILED);
                }
            } else {
                byte mark = room.data().secrets() == 0 || room.foundSecrets >= room.data().secrets() ? GREEN : CLEARED;
                for (int[] p : CHECKMARK) {
                    set(x + p[0], y + p[1], mark);
                }
            }
        }
    }

    private static boolean has(List<Layout.Segment> segments, int x, int z) {
        for (Layout.Segment s : segments) {
            if (s.x() == x && s.z() == z) {
                return true;
            }
        }
        return false;
    }

    static byte roomColor(RoomType type) {
        return switch (type) {
            case NORMAL, RARE -> BROWN;
            case PUZZLE -> PURPLE;
            case TRAP -> ORANGE;
            case FAIRY -> PINK;
            case ENTRANCE -> GREEN;
            case BLOOD, BOSS -> RED;
            case YELLOW -> YELLOW;
        };
    }

    static byte doorColor(RoomState room, RoomState neighbour) {
        switch (room.type()) {
            case PUZZLE, TRAP, BLOOD, YELLOW, FAIRY, BOSS -> {
                return roomColor(room.type());
            }
            default -> {
            }
        }
        switch (neighbour.type()) {
            case PUZZLE, TRAP, BLOOD, YELLOW, BOSS -> {
                return roomColor(neighbour.type());
            }
            default -> {
            }
        }
        return BROWN;
    }
}
