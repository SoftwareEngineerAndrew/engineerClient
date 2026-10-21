package net.rustclear.mod.engine.boss;

/**
 * A named attack on a player: the chat line Hypixel prints for it ("Storm's Static Field hit you
 * for 10,800 damage."), its F7 damage, whether it is true damage (only True Defense helps), and
 * the name the death line uses ("☠ You were killed by Storm and became a ghost."). M7 scales the
 * damage through Profile.scale("Storm's Static Field").
 */
public final class Attacks {
    public static final String STATIC_FIELD = "Static Field";
    public static final String LIGHTNING_FIREBALL = "Lightning Fireball";
    public static final String GIGA_LIGHTNING = "Giga Lightning";
    public static final String MAXOR_TNT = "Wither TNT";
    public static final String MAXOR_FRENZY = "Frenzy";
    public static final String GREATSWORD = "Greatsword";
    public static final String TNT_TRAP = "TNT Trap";
    public static final String SOUL_SAND_BLIZZARD = "Soul Sand Blizzard";
    public static final String BONZO_BALLOON = "Balloon";

    private Attacks() {
    }

    /**
     * One attack. [owner] is the boss ("Storm"), [name] the attack ("Static Field", also the key
     * Profile scales by), [f7Damage] the measured F7 value.
     */
    public record Attack(String owner, String name, double f7Damage, boolean trueDamage) {
        /** Its damage on [profile]'s floor. */
        public double damage(Profile profile) {
            return f7Damage * profile.scale(owner + "'s " + name);
        }

        /**
         * The chat line for [dealt] damage as Hypixel colours it (7434b1): "§cStorm's§7 Static Field
         * hit you for §c10,800§7 damage." The number has one decimal when it isn't whole ("2,069.6").
         */
        public String line(double dealt) {
            return "§c" + owner + "'s§7 " + name + " hit you for §c" + number(dealt)
                + (trueDamage ? "§7 true damage." : "§7 damage.");
        }
    }

    /** "10,800", "2,069.6". */
    public static String number(double v) {
        double tenths = Math.round(v * 10) / 10.0;
        return tenths == Math.rint(tenths)
            ? String.format(java.util.Locale.ROOT, "%,d", (long) tenths)
            : String.format(java.util.Locale.ROOT, "%,.1f", tenths);
    }
}
