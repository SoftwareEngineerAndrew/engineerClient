package net.rustclear.mod.engine;

/** A room's footprint, as the room data names it (Smalldungeons room_data.rs RoomShape). */
public enum RoomShape {
    ONE_BY_ONE("1x1"),
    ONE_BY_ONE_END("1x1_E"),
    ONE_BY_ONE_CROSS("1x1_X"),
    ONE_BY_ONE_STRAIGHT("1x1_I"),
    ONE_BY_ONE_BEND("1x1_L"),
    ONE_BY_ONE_TRIPLE("1x1_3"),
    ONE_BY_TWO("1x2"),
    ONE_BY_THREE("1x3"),
    ONE_BY_FOUR("1x4"),
    TWO_BY_TWO("2x2"),
    FOUR_BY_FOUR("4x4"),
    L("L"),
    EMPTY("");

    public final String code;

    RoomShape(String code) {
        this.code = code;
    }

    public static RoomShape fromCode(String code) {
        for (RoomShape shape : values()) {
            if (shape.code.equals(code)) {
                return shape;
            }
        }
        throw new IllegalArgumentException("Unknown room shape " + code);
    }
}
