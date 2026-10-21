package net.rustclear.mod;

/**
 * The floor a Rustclear world's dungeon plays as: Floor VII or Master Mode Floor VII. It sets the
 * mobs' level, health and damage (MobSystem.Archetype), whether master stars count, and what the
 * sidebar and tab list say. Players' own stats come from their gear (engine.PlayerStats).
 */
public enum Floor {
    F7("F7", "Floor VII"),
    M7("M7", "Master Mode Floor VII");

    /** How the sidebar and tab list name it ("(F7)", "(M7)"). */
    public final String code;
    public final String title;

    Floor(String code, String title) {
        this.code = code;
        this.title = title;
    }

    /** "F7" or "M7" (any case), or F7 for anything else and null. */
    public static Floor of(String code) {
        return "M7".equalsIgnoreCase(code) ? M7 : F7;
    }

    public boolean master() {
        return this == M7;
    }
}
