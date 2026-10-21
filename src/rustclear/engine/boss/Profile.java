package net.rustclear.mod.engine.boss;

import java.util.Map;
import net.rustclear.mod.Floor;

/**
 * The numbers that differ between Floor VII and Master Mode Floor VII for the Watcher and the
 * bosses (m7-differences.md). Clear mobs keep theirs in MobSystem.Archetype. Every M7 value is
 * measured on alpha.hypixel.net only (no main-network M7 was recorded), so M7 is "alpha-measured"
 * and these are the numbers to swap once a main M7 recording exists.
 */
public final class Profile {
    /** Floor VII, main network after the 10-06 update. */
    public static final Profile F7 = new Profile(false, false, 3_000, 1_000, 300_000, Map.of());

    /**
     * Master Mode Floor VII (alpha, 10-03/10-05). Attack damage to players, F7 -> M7 medians of
     * "... hit you for N damage" (m7-differences.md Summary): each scale is M7 / F7.
     */
    public static final Profile M7 = new Profile(true, true, 9_000, 3_000, 800_000_000, Map.of(
        "Storm's Static Field", 100.0,
        "Storm's Lightning Fireball", 1_000_000 / 75_000.0,
        "Maxor's Wither TNT", 500.0,
        "Maxor's Frenzy", 2_080_000 / 23_000.0,
        "Goldor's Greatsword", 1_000_000 / 120_000.0,
        // Frenzy: alpha M7 3.0-3.5M a hit vs F7's 2-44k (boss-p3-goldor.md §10): the top hits' ratio.
        "Goldor's Frenzy", 3_250_000 / 43_680.0,
        "Scarf's Soul Sand Blizzard", 10.0,
        "Bonzo's Balloon", 54_000 / 6_480.0));

    public final boolean master;
    /** M7 rests on alpha data only (decision 6: labelled "alpha-measured" in the settings). */
    public final boolean provisional;
    /** The Watcher's health, his kill counter: max/15 off per counted kill. */
    public final double watcherHealth;
    public final double eyeHealth;
    /** The boss withers' max_health attribute (their synced health is a stun/crush flag, critic C2). */
    public final double bossMaxHealth;
    private final Map<String, Double> attackScale;

    private Profile(boolean master, boolean provisional, double watcherHealth, double eyeHealth, double bossMaxHealth,
                    Map<String, Double> attackScale) {
        this.master = master;
        this.provisional = provisional;
        this.watcherHealth = watcherHealth;
        this.eyeHealth = eyeHealth;
        this.bossMaxHealth = bossMaxHealth;
        this.attackScale = attackScale;
    }

    public static Profile of(Floor floor) {
        return floor.master() ? M7 : F7;
    }

    /** How much harder [attack] ("Storm's Static Field") hits on this floor than on F7 (1 when unscaled, as traps are). */
    public double scale(String attack) {
        return attackScale.getOrDefault(attack, 1.0);
    }
}
