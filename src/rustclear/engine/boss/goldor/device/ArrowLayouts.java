package net.rustclear.mod.engine.boss.goldor.device;

/**
 * Arrow Align's eight boards (boss-p3-goldor.md §6.3), as read from the item frames of 27 recorded
 * Hypixel P3s (every board matched one of these exactly, final rotations included; all eight
 * seen, 2-6 times each). Rows are y 124 (top) to 120, columns z 75 to 79; the frames hang at
 * x -2 facing east, so from the front z 79 is on the left. A digit is an arrow and its solved
 * rotation (1 points right, 3 down, 5 left, 7 up, as seen from the front), 'S' lime wool named
 * "Start", 'E' red wool named "End", '.' no frame.
 */
final class ArrowLayouts {
    private ArrowLayouts() {
    }

    static final String[][] BOARDS = {
        {"55E11", "7...7", "71.57", ".7.7.", ".S.S."},
        {"..553", "..7.3", "E.S.E", "7.3..", "711.."},
        {"S...S", "3...3", "3.E.3", "3.7.3", "55711"},
        {"31111", "3...7", "3.E.7", "3.7.7", "557.S"},
        {"E.311", "7.3.7", "7.3.7", "7.3.7", "711.S"},
        {"E111S", ".....", "E111S", ".....", "E111S"},
        {".311.", ".3.7.", ".3.7.", ".3.7.", "E1.7S"},
        {"..31S", "E11..", "..71S", "E11..", "..71S"},
    };

    static final int Y_TOP = 124;
    static final int Z0 = 75;

    /** The cell at board [b], height [y], [z]: a digit, 'S', 'E' or '.'. */
    static char cell(int b, int y, int z) {
        return BOARDS[b][Y_TOP - y].charAt(z - Z0);
    }

    static boolean isArrow(char c) {
        return c >= '0' && c <= '7';
    }

    /**
     * The arrow left one click short at P3's start: the one nearest the bottom left as you face the
     * board (y 120, z 79). In the 5 recordings that saw the board before it was done, exactly that
     * frame was one click short (rotation = solution - 1) and every other arrow was solved.
     * Returns {y, z}.
     */
    static int[] unsolved(int b) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int y = 120; y <= 124; y++) {
            for (int z = Z0; z <= Z0 + 4; z++) {
                if (!isArrow(cell(b, y, z))) {
                    continue;
                }
                int dy = y - 120;
                int dz = 79 - z;
                int d = dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = new int[] {y, z};
                }
            }
        }
        return best;
    }
}
