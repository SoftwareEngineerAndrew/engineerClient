package net.rustclear.mod.engine;

import java.util.ArrayList;
import java.util.List;

/** A room during a run: where it is and what has happened in it (Smalldungeons Room's run state). */
public final class RoomState {
    /** A door out of one of the room's cells, on side 0 north, 1 east, 2 south, 3 west. */
    public record Neighbour(int side, int doorIndex, int roomIndex) {}

    public final int index;
    public final PlacedRoom placed;
    /** Per segment (in the slot's order), the neighbours through doors. */
    public final List<List<Neighbour>> neighbours = new ArrayList<>();

    public boolean entered;
    /** Mobs have been spawned (phase 3); until then a room with no mobs counts as having spawned none. */
    public boolean mobsSpawned;
    public int starredMobsRemaining;
    public int foundSecrets;
    public boolean trapCompleted;
    public boolean puzzleCompleted;
    public boolean puzzleFailed;
    /** Who failed the puzzle last (the tab names them while it is [✖]). */
    public String failedBy;

    public RoomState(int index, PlacedRoom placed) {
        this.index = index;
        this.placed = placed;
    }

    /** Cleared as the map's checkmark shows it: a trap or puzzle once done, any other room once its starred mobs are dead. */
    public boolean cleared() {
        return switch (type()) {
            case TRAP -> trapCompleted;
            case PUZZLE -> puzzleCompleted;
            default -> mobsSpawned && starredMobsRemaining == 0;
        };
    }

    public RoomData data() {
        return placed.data();
    }

    public RoomType type() {
        return placed.slot().type();
    }

    public List<Layout.Segment> segments() {
        return placed.slot().segments();
    }

    /** Room::get_local_block_pos: a world spot in the room data's own coordinates. Returns {x, y, z}. */
    public int[] toLocal(int x, int y, int z) {
        Facing inverse = placed.facing().inverse();
        int dx = x - placed.cornerX();
        int dz = z - placed.cornerZ();
        return new int[] {inverse.rotateX(dx, dz), y, inverse.rotateZ(dx, dz)};
    }
}
