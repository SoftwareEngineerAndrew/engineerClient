package net.rustclear.mod.engine.boss.goldor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * One of P3's 30 things that count toward a section (boss-p3-goldor.md §1): a terminal, a lever
 * or the section's device. Positions are where Hypixel's status stands stand (the recordings);
 * the stands read "Inactive Terminal" over "CLICK HERE", "Inactive" over "Device", or
 * "Not Activated" (§7), and turn green when done.
 */
public final class Station {
    public enum Kind { TERMINAL, LEVER, DEVICE }

    public final Kind kind;
    /** 1-4. */
    public final int section;
    /** The top stand's spot. */
    public final Vec3 at;
    /** "T1".."T5", "east lever", "SS", "Lights", "Arrows", "Target". */
    public final String label;
    /** The lever's block (levers only). */
    public final BlockPos lever;

    boolean done;
    long doneAt = -1;
    /** The terminal's puzzle type, drawn per station (each open is a new puzzle of it). */
    net.rustclear.mod.engine.boss.goldor.terminal.Terminals.Type type;
    ArmorStand top;
    ArmorStand bottom;

    private Station(Kind kind, int section, Vec3 at, String label, BlockPos lever) {
        this.kind = kind;
        this.section = section;
        this.at = at;
        this.label = label;
        this.lever = lever;
    }

    public boolean done() {
        return done;
    }

    /** The top stand (a terminal's click target). */
    public Entity stand() {
        return top;
    }

    public String id() {
        return "S" + section + " " + label;
    }

    boolean owns(Entity e) {
        return e == top || e == bottom;
    }

    /** Hypixel's stand heights relative to a terminal's spot (after their first position sync). */
    static final double TOP_DY = 0.02;
    static final double BOTTOM_DY = -0.355;

    private static Station t(int s, int n, double x, double y, double z) {
        return new Station(Kind.TERMINAL, s, new Vec3(x, y, z), "T" + n, null);
    }

    private static Station l(int s, String label, int x, int y, int z) {
        return new Station(Kind.LEVER, s, new Vec3(x + 0.5, y + 0.688, z + 0.5), label, new BlockPos(x, y, z));
    }

    private static Station d(int s, String label, double x, double y, double z) {
        return new Station(Kind.DEVICE, s, new Vec3(x, y, z), label, null);
    }

    /** All 30 (positions from the recordings' stands; the levers are blocks in Rustclear's arena). */
    static List<Station> all() {
        return List.of(
            t(1, 1, 110.5, 112.0, 73.5), t(1, 2, 110.5, 118.0, 79.5), t(1, 3, 90.5, 111.0, 92.5), t(1, 4, 90.5, 121.0, 101.5),
            l(1, "east lever", 106, 124, 113), l(1, "west lever", 94, 124, 113),
            d(1, "SS", 110.5, 119.0, 91.5),
            t(2, 1, 68.5, 108.0, 122.5), t(2, 2, 59.5, 119.0, 123.5), t(2, 3, 47.5, 108.0, 122.5), t(2, 4, 39.5, 107.0, 142.5),
            t(2, 5, 40.5, 123.0, 123.5),
            l(2, "low lever", 27, 124, 127), l(2, "high lever", 23, 132, 138),
            d(2, "Lights", 60.5, 131.0, 142.5),
            t(3, 1, -1.5, 108.0, 112.5), t(3, 2, -1.5, 118.0, 93.5), t(3, 3, 18.5, 122.0, 93.5), t(3, 4, -1.5, 108.0, 77.5),
            l(3, "west lever", 2, 122, 55), l(3, "east lever", 14, 122, 55),
            d(3, "Arrows", -1.5, 119.0, 74.5),
            t(4, 1, 41.5, 108.0, 30.5), t(4, 2, 44.5, 120.0, 30.5), t(4, 3, 67.5, 108.0, 30.5), t(4, 4, 72.5, 114.0, 47.5),
            l(4, "low lever", 84, 121, 34), l(4, "high lever", 86, 128, 46),
            d(4, "Target", 63.5, 126.0, 34.5));
    }

    /** Each section's count (terminals + 2 levers + the device): 7, 8, 7, 7. */
    static int total(int section) {
        return section == 2 ? 8 : 7;
    }
}
