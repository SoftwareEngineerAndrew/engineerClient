package net.rustclear.mod.engine.boss.goldor.device;

/**
 * The Lights device's state (boss-p3-goldor.md §6.2): 20 levers at x 58-62, y 133-136 (z 142),
 * each over a redstone lamp (z 143). A lamp is lit while any lever in its plus (itself and the
 * four next to it) is on: an OR, not a toggle.
 */
final class LightsBoard {
    static final int X0 = 58;
    static final int X1 = 62;
    static final int Y0 = 133;
    static final int Y1 = 136;
    static final int Z_LEVER = 142;
    static final int Z_LAMP = 143;

    /**
     * The six levers that light all 20 lamps from all off (x, y). Hypixel's lamps are all lit at P3's
     * start in 36 of 36 audit runs; which levers a party used isn't visible, but the one recorded S2
     * click (a lever turned off, three lamps out) fits this set.
     */
    static final int[][] SOLVED = {{58, 133}, {58, 136}, {60, 134}, {60, 135}, {62, 133}, {62, 136}};

    private final boolean[][] on = new boolean[X1 - X0 + 1][Y1 - Y0 + 1];

    static boolean isLever(int x, int y, int z) {
        return z == Z_LEVER && x >= X0 && x <= X1 && y >= Y0 && y <= Y1;
    }

    static boolean inSolution(int x, int y) {
        for (int[] l : SOLVED) {
            if (l[0] == x && l[1] == y) {
                return true;
            }
        }
        return false;
    }

    /** Every lever as in [SOLVED] (every lamp lit). */
    void solve() {
        for (int x = X0; x <= X1; x++) {
            for (int y = Y0; y <= Y1; y++) {
                on[x - X0][y - Y0] = inSolution(x, y);
            }
        }
    }

    boolean lever(int x, int y) {
        return x >= X0 && x <= X1 && y >= Y0 && y <= Y1 && on[x - X0][y - Y0];
    }

    void toggle(int x, int y) {
        on[x - X0][y - Y0] = !on[x - X0][y - Y0];
    }

    boolean lamp(int x, int y) {
        return lever(x, y) || lever(x - 1, y) || lever(x + 1, y) || lever(x, y - 1) || lever(x, y + 1);
    }

    boolean allLit() {
        for (int x = X0; x <= X1; x++) {
            for (int y = Y0; y <= Y1; y++) {
                if (!lamp(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
