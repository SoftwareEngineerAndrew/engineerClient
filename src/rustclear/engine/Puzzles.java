package net.rustclear.mod.engine;

/** Which puzzle logic a room gets, by the room's name. */
final class Puzzles {
    private Puzzles() {
    }

    /** The puzzle for [room], or null when it has none. */
    static Puzzle create(DungeonRun run, RoomState room) {
        return switch (room.data().name()) {
            case "Three Weirdos" -> new ThreeWeirdosPuzzle(run, room);
            case "Quiz" -> new QuizPuzzle(run, room);
            case "Tic Tac Toe" -> new TicTacToePuzzle(run, room);
            case "Creeper Beams" -> new CreeperBeamsPuzzle(run, room);
            case "Teleport Maze" -> new TeleportMazePuzzle(run, room);
            case "Ice Path" -> new IcePathPuzzle(run, room);
            case "Ice Fill" -> new IceFillPuzzle(run, room);
            case "Boulder" -> new BoulderPuzzle(run, room);
            case "Blaze" -> new BlazePuzzle(run, room);
            case "Water Board" -> new WaterBoardPuzzle(run, room);
            default -> null;
        };
    }
}
