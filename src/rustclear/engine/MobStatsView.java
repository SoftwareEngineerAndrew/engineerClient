package net.rustclear.mod.engine;

import net.rustclear.mod.Floor;

/** The few mob numbers the Stats tab shows players, read from MobSystem's per-floor table. */
public final class MobStatsView {
    private MobStatsView() {
    }

    /** A Zombie Lord's melee damage on [floor]: the hardest regular hit, as a yardstick. */
    public static long zombieLordDamage(Floor floor) {
        return MobSystem.Archetype.ZOMBIE_LORD.stats(floor).damage();
    }

    /** A Zombie Lord's health on [floor]: the toughest regular mob. */
    public static long zombieLordHealth(Floor floor) {
        return MobSystem.Archetype.ZOMBIE_LORD.stats(floor).health();
    }
}
