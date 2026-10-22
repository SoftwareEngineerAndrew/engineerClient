package net.rustclear.mod.engine;

import net.rustclear.mod.Floor;

/**
 * One thing to practise in the engine's solo world, as the ADS Practice page asks for it
 * (EnginePractice): what kind, on which floor, and that kind's own choices. Fields a kind doesn't
 * use are null. PracticeSession sets the world up for it.
 *
 * @param seed  FULL_CLEAR and BLOOD_RUSH: the map to replay, or null for a new one
 * @param room  ROOM: a Practice.roomNames name ("higher_blaze", "Water_Board"...)
 * @param door  ROOM: north, east, south, west or random
 * @param phase BOSS: p1 (Maxor), p2 (Storm), p4 (Necron) or p5 (the Wither King, always M7)
 */
public record PracticeSpec(Kind kind, Floor floor, Long seed, String room, String door, String phase) {
    public enum Kind { FULL_CLEAR, ROOM, BLOOD_RUSH, BLOOD_CAMP, BOSS }

    public PracticeSpec {
        if (kind == null) {
            throw new IllegalArgumentException("A practice needs a kind");
        }
        if (floor == null) {
            floor = Floor.F7;
        }
    }

    public static PracticeSpec fullClear(Floor floor, Long seed) {
        return new PracticeSpec(Kind.FULL_CLEAR, floor, seed, null, null, null);
    }

    public static PracticeSpec room(Floor floor, String room, String door) {
        return new PracticeSpec(Kind.ROOM, floor, null, room, door, null);
    }

    public static PracticeSpec bloodRush(Floor floor, Long seed) {
        return new PracticeSpec(Kind.BLOOD_RUSH, floor, seed, null, null, null);
    }

    public static PracticeSpec bloodCamp(Floor floor) {
        return new PracticeSpec(Kind.BLOOD_CAMP, floor, null, null, null, null);
    }

    public static PracticeSpec boss(Floor floor, String phase) {
        return new PracticeSpec(Kind.BOSS, floor, null, null, null, phase);
    }
}
