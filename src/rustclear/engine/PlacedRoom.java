package net.rustclear.mod.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;

/** A room put in a layout slot: which data, which way it is turned and where its corner is (Smalldungeons Room). */
public record PlacedRoom(Layout.Slot slot, RoomData data, Facing facing, int cornerX, int cornerZ) {

    /** [random] turns rooms Hypixel turns freely (null: the fixed turn, for practice's lone room). */
    public static PlacedRoom of(Layout.Slot slot, RoomData data, List<Layout.Door> doors, Random random) {
        List<Layout.Segment> segments = slot.segments().stream()
            .sorted(Comparator.comparingInt(Layout.Segment::x).thenComparingInt(Layout.Segment::z))
            .toList();
        Facing facing = Layout.facingOf(segments, doors);
        if (slot.type() == RoomType.FAIRY) {
            // Hypixel never turns the Fairy room, whatever its doors: Odin reads SOUTH (our NORTH) in 28 of 28
            // runs (docs/f7-sim/clear-rooms-doors.md §9). Door patterns open whichever walls have doors.
            facing = Facing.NORTH;
        } else if (slot.shape() == RoomShape.ONE_BY_ONE_CROSS && random != null) {
            // A room with doors on all four sides takes any turn on Hypixel (all four seen in 17 runs). OPEN: 70
            facing = Facing.values()[random.nextInt(4)];
        }
        if (data.name().equals("Dragon")) {
            // Its recorded door is on the far side from every other dead end (room.rs door_rotation_correction).
            facing = facing.rotate(Facing.SOUTH);
        }
        int x = segments.stream().mapToInt(Layout.Segment::x).min().orElseThrow() * Layout.CELL + Layout.ORIGIN_X;
        int z = segments.stream().mapToInt(Layout.Segment::z).min().orElseThrow() * Layout.CELL + Layout.ORIGIN_Z;
        // Room::get_corner_pos_from: the corner the room data's (0, 0) lands on once turned.
        return switch (facing) {
            case NORTH -> new PlacedRoom(slot, data, facing, x, z);
            case EAST -> new PlacedRoom(slot, data, facing, x + data.length() - 1, z);
            case SOUTH -> new PlacedRoom(slot, data, facing, x + data.length() - 1, z + data.width() - 1);
            case WEST -> new PlacedRoom(slot, data, facing, x, z + data.width() - 1);
        };
    }

    /** A spot in the room data's own coordinates, in the world (y is not moved: rooms keep their recorded heights). */
    public BlockPos toWorld(int x, int y, int z) {
        return new BlockPos(cornerX + facing.rotateX(x, z), y, cornerZ + facing.rotateZ(x, z));
    }
}
