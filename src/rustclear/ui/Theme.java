package net.rustclear.mod.ui;

/**
 * Rustclear's palette: slate stone panels, the dungeon map's colours for room types (brown rooms,
 * purple puzzles, orange traps, yellow minibosses, green entrance, red blood) and gold for secrets.
 * Every Rustclear screen draws with these, so they all read as one thing.
 */
public final class Theme {
    public static final int SHADE_TOP = 0xB0070A0D;
    public static final int SHADE_BOTTOM = 0xD8040506;
    public static final int PANEL = 0xF2161B21;
    public static final int PANEL_EDGE = 0xFF2C343E;
    public static final int RAIL = 0xFF101418;
    public static final int ROW_HOVER = 0xFF1E252D;
    public static final int ROW_ON = 0xFF27303A;
    public static final int TEXT = 0xFFE7EAEE;
    public static final int MUTED = 0xFF8C96A1;
    public static final int DIM = 0xFF4D5661;
    public static final int GOLD = 0xFFF2B33D;
    public static final int GOLD_DEEP = 0xFF7A5718;
    /** Text on a gold button. */
    public static final int ON_GOLD = 0xFF1A1206;
    public static final int NORMAL = 0xFF8A5A2E;
    public static final int PUZZLE = 0xFF8E4CC2;
    public static final int TRAP = 0xFFDB8236;
    public static final int MINIBOSS = 0xFFE3D83A;
    public static final int ENTRANCE = 0xFF5DBB4D;
    public static final int BLOOD = 0xFFC94A3F;
    public static final int GOOD = 0xFF6BCB77;
    public static final int BAD = 0xFFE0675A;
    public static final int SILVER = 0xFFC3CBD3;
    public static final int BRONZE = 0xFFC07A45;
    /** Duel team colours: the map's own blue and red door tints. */
    public static final int TEAM_A = 0xFF4F9BE0;
    public static final int TEAM_B = 0xFFE0675A;

    private Theme() {
    }

    /** Blends [a] toward [b] by [t] (0..1), channel by channel. */
    public static int lerp(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return out;
    }

    /** [c] with alpha [a] (0..255). */
    public static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }
}
