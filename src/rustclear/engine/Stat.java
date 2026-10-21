package net.rustclear.mod.engine;

import java.util.Locale;

/**
 * Every SkyBlock stat that matters in a fight, as the menus and tooltips show it, with the classic
 * symbols: Hypixel draws resource-pack glyphs (U+E0xx) in their place, which show as boxes without
 * its pack (stats.md §1; // OPEN: 69). [key] is the
 * stat's name in the item list (ItemDefs), [base] what every player has with nothing on
 * (Hypixel's own starting stats). Mana and Effective Health aren't stats of their own: Mana is
 * 100 + Intelligence, EHP Health x (1 + Defense / 100) (CombatMath).
 */
public enum Stat {
    HEALTH("Health", "Health", "❤", "§c", 100, false),
    DEFENSE("Defense", "Defense", "❈", "§a", 0, false),
    TRUE_DEFENSE("True Defense", "True Defense", "❂", "§f", 0, false),
    STRENGTH("Strength", "Strength", "❁", "§c", 0, false),
    INTELLIGENCE("Intelligence", "Intelligence", "✎", "§b", 0, false),
    CRIT_CHANCE("Crit Chance", "Crit Chance", "☣", "§9", 30, true),
    CRIT_DAMAGE("Crit Damage", "Crit Damage", "☠", "§9", 50, true),
    ATTACK_SPEED("Attack Speed", "Attack Speed", "⚔", "§e", 0, true),
    ABILITY_DAMAGE("Ability Damage", "Ability Damage", "๑", "§c", 0, true),
    FEROCITY("Ferocity", "Ferocity", "⫽", "§c", 0, false),
    SPEED("Speed", "Speed", "✦", "§f", 100, false),
    VITALITY("Vitality", "Vitality", "♨", "§4", 100, false),
    MENDING("Mending", "Mending", "☄", "§a", 100, false),
    HEALTH_REGEN("Health Regen", "Health Regen", "❣", "§c", 100, false),
    MAGIC_FIND("Magic Find", "Magic Find", "✯", "§b", 0, false),
    SWING_RANGE("Swing Range", "Swing Range", "Ⓢ", "§e", 3, false),
    PET_LUCK("Pet Luck", "Pet Luck", "♣", "§d", 0, false);

    /** The stat's name in items.json.gz. */
    public final String key;
    public final String label;
    public final String symbol;
    public final String color;
    public final double base;
    public final boolean percent;

    Stat(String key, String label, String symbol, String color, double base, boolean percent) {
        this.key = key;
        this.label = label;
        this.symbol = symbol;
        this.color = color;
        this.base = base;
        this.percent = percent;
    }

    /** The stat an item list name stands for, or null (Damage, Shot Cooldown and the class lines aren't player stats). */
    public static Stat of(String key) {
        for (Stat s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** "§c❤ Health", as Hypixel labels it. */
    public String title() {
        return color + symbol + " " + label;
    }

    /** [value] with this stat's unit: "1,234" or "45.5%". */
    public String format(double value) {
        return number(value) + (percent ? "%" : "");
    }

    /** 1,521 or 16.5: thousands separated, at most one decimal. */
    public static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) {
            return String.format(Locale.ROOT, "%,d", (long) rounded);
        }
        return String.format(Locale.ROOT, "%,.1f", rounded);
    }
}
