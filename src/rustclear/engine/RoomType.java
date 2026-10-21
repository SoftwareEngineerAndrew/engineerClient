package net.rustclear.mod.engine;

/** What a room is for (Smalldungeons room_data.rs RoomType). */
public enum RoomType {
    NORMAL, PUZZLE, TRAP, FAIRY, ENTRANCE, BLOOD, YELLOW, RARE, BOSS;

    /** The room data's "type" string; anything unknown is a normal room, as on the server. */
    public static RoomType fromCode(String code) {
        return switch (code) {
            case "puzzle" -> PUZZLE;
            case "yellow" -> YELLOW;
            case "blood" -> BLOOD;
            case "fairy" -> FAIRY;
            case "entrance" -> ENTRANCE;
            case "trap" -> TRAP;
            case "rare" -> RARE;
            case "boss" -> BOSS;
            default -> NORMAL;
        };
    }
}
