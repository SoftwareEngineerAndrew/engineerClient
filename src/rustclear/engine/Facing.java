package net.rustclear.mod.engine;

import net.minecraft.world.level.block.Rotation;

/**
 * Which way a room or door is turned. NORTH is the room data as recorded; EAST, SOUTH and WEST
 * turn it 90, 180 and 270 degrees clockwise (Smalldungeons BlockPos::rotate).
 */
public enum Facing {
    NORTH, EAST, SOUTH, WEST;

    public int rotateX(int x, int z) {
        return switch (this) {
            case NORTH -> x;
            case EAST -> -z;
            case SOUTH -> -x;
            case WEST -> z;
        };
    }

    public int rotateZ(int x, int z) {
        return switch (this) {
            case NORTH -> z;
            case EAST -> x;
            case SOUTH -> -z;
            case WEST -> -x;
        };
    }

    /** This facing applied after [other] (Smalldungeons Direction::rotate). */
    public Facing rotate(Facing other) {
        return values()[(ordinal() + other.ordinal()) % 4];
    }

    /** The undoing turn: EAST and WEST swap, NORTH and SOUTH are their own. */
    public Facing inverse() {
        return values()[(4 - ordinal()) % 4];
    }

    public Rotation rotation() {
        return switch (this) {
            case NORTH -> Rotation.NONE;
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** A yaw turned by this facing (Smalldungeons Rotatable for f32). */
    public float rotateYaw(float yaw) {
        return (yaw + ordinal() * 90f) % 360f;
    }
}
