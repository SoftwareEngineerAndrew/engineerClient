package net.rustclear.mod.engine;

import java.util.List;
import java.util.Map;

/**
 * Puts one player's stats together (StatSheet) from their profile (skills, slayers, Accessory
 * Power, Catacombs and class levels), class, armour, equipment, held item and pet, and in a
 * dungeon the blessings found and the Dungeon VII potion. Free of Minecraft: PlayerStats reads
 * the gear off the player and hands it here.
 */
public final class StatBuilder {
    /** Dungeon VII potion (Strength V, Critical III, Archery III, Speed III), always on in a run, as DPS-Bot assumes. */
    static final Map<Stat, Double> DUNGEON_POTION = Map.of(Stat.STRENGTH, 40.0, Stat.CRIT_CHANCE, 20.0, Stat.CRIT_DAMAGE, 30.0, Stat.SPEED, 15.0);
    static final double POTION_BOW_DAMAGE = 50;
    static final List<String> NECRON_BLADES = List.of("HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA", "NECRON_BLADE");

    /** What a run has given: blessing levels by name ("power", "wisdom", "stone", "life", "time"), in a dungeon only. */
    public record Run(boolean inDungeon, boolean masterMode, Map<String, Integer> blessings) {
        public static final Run NONE = new Run(false, false, Map.of());

        int blessing(String name) {
            return blessings == null ? 0 : blessings.getOrDefault(name, 0);
        }
    }

    /**
     * Timed buffs: Ragnarok, and flat Strength and Speed for a while (a Berserk ghost's Strength Potion, the
     * Spirit Mask's and Bonzo's Mask's saves), and a Support Orb's +20% Damage, Defense or Ability Damage
     * (fractions).
     */
    public record Buffs(boolean ragnarok, double strength, double speed, double orbDamage, double orbDefense,
                        double orbAbilityDamage) {
        public static final Buffs NONE = new Buffs(false, 0, 0, 0, 0, 0);
    }

    private StatBuilder() {
    }

    /**
     * [armor] and [equipment] worn, [held] in hand (null: nothing that has stats), [pet] what the
     * active pet adds (PetDefs.Bonus.NONE: none).
     */
    public static StatSheet build(SkyblockProfile profile, DungeonClass dungeonClass, boolean solo, List<ItemDefs.Instance> armor,
                                  List<ItemDefs.Instance> equipment, ItemDefs.Instance held, PetDefs.Bonus pet, Run run, Buffs buffs) {
        StatSheet s = new StatSheet();
        int cata = profile.cataLevel();
        s.cata = cata;
        s.addAll(StatSheet.Source.SKILLS, profile.skillStats());
        // Each Catacombs level gives +2 Health (Catacombs Rewards lore, levels 27-50 recorded; OPEN: 1-26 assumed the same).
        s.add(StatSheet.Source.SKILLS, "Catacombs " + cata, Stat.HEALTH, 2.0 * cata);
        s.addAll(StatSheet.Source.SLAYERS, profile.slayerStats());
        s.addAll(StatSheet.Source.ACCESSORIES, profile.accessoryStats(dungeonClass, run.inDungeon()));
        if (profile.maxAccessories) {
            Accessories.addMax(s, profile, run);
        }
        s.damageAdditive += profile.warriorPercent();

        s.dungeonClass = dungeonClass;
        if (dungeonClass != null) {
            int level = profile.classLevel(dungeonClass);
            DungeonClass.Bonus bonus = dungeonClass.bonus(level, solo);
            s.classBonus = bonus;
            s.classLevel = level;
            s.soloClass = solo;
            s.addAll(StatSheet.Source.CLASS, dungeonClass.title + " " + level, bonus.stats());
            s.addPercent(dungeonClass.title + " " + level, Stat.DEFENSE, bonus.defensePercent());
            s.meleeAdditive += bonus.meleeAdditive();
            s.arrowMultiplier *= bonus.arrowMultiplier();
            s.bowAdditive += bonus.arrowAdditive();
        }

        boolean master = run.masterMode();
        s.speedCap += speedCap(armor, equipment, held);
        for (ItemDefs.Instance item : armor) {
            addItem(s, StatSheet.Source.ARMOR, item, cata, master, false);
        }
        for (ItemDefs.Instance item : equipment) {
            addItem(s, StatSheet.Source.EQUIPMENT, item, cata, master, false);
        }
        if (held != null && !held.def().isArmor()) {
            addItem(s, StatSheet.Source.HELD, held, cata, master, true);
            necronBlade(s, held, cata, master);
        }
        s.addAll(StatSheet.Source.PET, pet.label(), pet.flat());
        pet.percent().forEach((stat, f) -> s.addPercent(pet.percentLabel(), stat, f));

        if (run.inDungeon()) {
            s.addAll(StatSheet.Source.POTION, "Dungeon VII Potion", DUNGEON_POTION);
            s.bowAdditive += POTION_BOW_DAMAGE;
            blessings(s, run);
        }
        if (buffs.ragnarok()) {
            // Ragnarok: +100 Attack Speed, +400 Speed (to the cap) and 50% more damage for 15 s.
            s.add(StatSheet.Source.BUFFS, "Ragnarok", Stat.ATTACK_SPEED, 100);
            s.add(StatSheet.Source.BUFFS, "Ragnarok", Stat.SPEED, 400);
            s.damageMultiplier *= 1.5;
        }
        if (buffs.strength() > 0) {
            s.add(StatSheet.Source.BUFFS, "Buffs", Stat.STRENGTH, buffs.strength());
        }
        if (buffs.speed() > 0) {
            s.add(StatSheet.Source.BUFFS, "Buffs", Stat.SPEED, buffs.speed());
        }
        // Support Orbs. OPEN: whether "+20% Damage" multiplies (as here) or adds to the other percents.
        s.damageMultiplier *= 1 + buffs.orbDamage();
        s.addPercent("Support Orb", Stat.DEFENSE, buffs.orbDefense());
        if (buffs.orbAbilityDamage() > 0) {
            s.add(StatSheet.Source.BUFFS, "Support Orb", Stat.ABILITY_DAMAGE, buffs.orbAbilityDamage() * 100);
        }
        return s;
    }

    private static final java.util.regex.Pattern SPEED_CAP = java.util.regex.Pattern.compile(
        "\\+(\\d+) (?:Max |Walk )?Speed Cap|[Ss]peed cap by \\+?(\\d+)");

    /**
     * What worn [armor], [equipment] and [held] add to the speed cap: their "+100 Speed Cap" /
     * "+50 Max Speed Cap" / "speed cap by +N" lines (stats.md §5: Racing Helmet +100), a full set
     * bonus only with all four pieces worn (Young Dragon's +100 Walk Speed Cap).
     */
    static double speedCap(List<ItemDefs.Instance> armor, List<ItemDefs.Instance> equipment, ItemDefs.Instance held) {
        double cap = 0;
        java.util.List<ItemDefs.Instance> worn = new java.util.ArrayList<>(armor);
        worn.addAll(equipment);
        if (held != null && !held.def().isArmor()) {
            worn.add(held);
        }
        java.util.Set<String> sets = new java.util.HashSet<>();
        for (ItemDefs.Instance item : worn) {
            for (ItemDefs.Ability a : item.def().abilities) {
                boolean set = "Full Set Bonus".equals(a.kind);
                String name = a.name == null ? "" : a.name.replaceAll(" \\(\\d/4\\)$", "");
                if (set && (!sets.add(name) || armor.stream().filter(i -> hasSet(i, name)).count() < 4)) {
                    continue;
                }
                for (String line : a.text) {
                    java.util.regex.Matcher m = SPEED_CAP.matcher(line);
                    if (m.find()) {
                        cap += Double.parseDouble(m.group(1) != null ? m.group(1) : m.group(2));
                    }
                }
            }
        }
        return cap;
    }

    private static boolean hasSet(ItemDefs.Instance item, String name) {
        for (ItemDefs.Ability a : item.def().abilities) {
            if ("Full Set Bonus".equals(a.kind) && a.name != null && a.name.replaceAll(" \\(\\d/4\\)$", "").equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The stats Renowned's "+1% to ALL Combat Stats" raises (Stats & Equipment's Combat Stats list, stats.md §2). */
    private static final Stat[] COMBAT_STATS = {Stat.HEALTH, Stat.DEFENSE, Stat.TRUE_DEFENSE, Stat.STRENGTH, Stat.CRIT_CHANCE,
        Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.SWING_RANGE, Stat.INTELLIGENCE, Stat.ABILITY_DAMAGE,
        Stat.HEALTH_REGEN, Stat.VITALITY, Stat.MENDING};

    /**
     * A reforge's bonus that its item lines don't show (items-stats.md §1: residual 0 on the items):
     * Ancient +1 Crit Damage and Withered +1 Strength per Catacombs level, Loving +5% Ability Damage,
     * Renowned +1% Magic Find and +1% to every combat stat. OPEN: Fabled's crit and Precise's
     * head-hit damage aren't modelled.
     */
    private static void reforgeBonus(StatSheet s, ItemDefs.Instance item, int cata) {
        String r = item.mods().reforge();
        if (r == null) {
            return;
        }
        String label = ItemDefs.displayName(item);
        switch (r) {
            case "ancient" -> s.add(StatSheet.Source.ARMOR, label, Stat.CRIT_DAMAGE, cata);
            case "withered" -> s.add(StatSheet.Source.HELD, label, Stat.STRENGTH, cata);
            case "loving" -> s.add(StatSheet.Source.ARMOR, label, Stat.ABILITY_DAMAGE, 5);
            case "renowned" -> {
                s.add(StatSheet.Source.ARMOR, label, Stat.MAGIC_FIND, 1);
                for (Stat stat : COMBAT_STATS) {
                    s.addPercent(label, stat, 0.01);
                }
            }
            default -> {
            }
        }
    }

    /** Each of [item]'s stats in the Catacombs; its Damage only counts held. */
    private static void addItem(StatSheet s, StatSheet.Source source, ItemDefs.Instance item, int cata, boolean master, boolean held) {
        reforgeBonus(s, item, cata);
        for (String name : item.statNames()) {
            double value = item.inDungeon(name, cata, master);
            if (name.equals("Damage")) {
                if (held) {
                    s.weaponDamage += value;
                }
                continue;
            }
            Stat stat = Stat.of(name);
            if (stat != null) {
                s.add(source, ItemDefs.displayName(item), stat, value);
            }
        }
    }

    /**
     * Necron's Blade and its refinements gain stats per Catacombs level, scaled like their own
     * (DPS-Bot): Hyperion +1 Damage and +2 Intelligence, Valkyrie +1 Damage and Strength, Scylla
     * +1 Damage and Crit Damage, Astraea +1 Damage.
     */
    private static void necronBlade(StatSheet s, ItemDefs.Instance held, int cata, boolean master) {
        String id = held.def().id;
        if (!NECRON_BLADES.contains(id)) {
            return;
        }
        s.weaponDamage += ItemStats.inDungeon("Damage", necronPerCata(id, "Damage") * cata, cata, held.stars(), held.masterStars(), master, 0);
        Stat extra = necronExtra(id);
        if (extra != null) {
            s.add(StatSheet.Source.HELD, ItemDefs.displayName(held), extra,
                ItemStats.inDungeon(extra.key, necronPerCata(id, extra.key) * cata, cata, held.stars(), held.masterStars(), master, 0));
        }
    }

    private static Stat necronExtra(String id) {
        return switch (id) {
            case "HYPERION" -> Stat.INTELLIGENCE;
            case "VALKYRIE" -> Stat.STRENGTH;
            case "SCYLLA" -> Stat.CRIT_DAMAGE;
            default -> null;
        };
    }

    /** What a Necron's Blade (refinement) [id] gains in [stat] per Catacombs level (0: none). */
    static double necronPerCata(String id, String stat) {
        if (!NECRON_BLADES.contains(id)) {
            return 0;
        }
        if (stat.equals("Damage")) {
            return 1;
        }
        Stat extra = necronExtra(id);
        return extra != null && extra.key.equals(stat) ? (extra == Stat.INTELLIGENCE ? 2 : 1) : 0;
    }

    /**
     * Blessings by summed level (Blessings, measured from the "Granted you" lines): Power +5.8 and
     * x(1 + 0.03) a level on Strength and Crit Damage; Wisdom the same on Intelligence plus +4.8
     * Speed; Stone on Defense plus +7.2 Damage; Life x(1 + 0.044) a level on Health and Health Regen;
     * the Quiz's Blessing of Time V +29 and x1.15 on Health, Defense, Intelligence and Strength.
     * OPEN: whether levels of one kind add (as here) or each blessing multiplies.
     */
    private static void blessings(StatSheet s, Run run) {
        int power = run.blessing("power");
        flatAndPercent(s, "Blessing of Power", power, Stat.STRENGTH);
        flatAndPercent(s, "Blessing of Power", power, Stat.CRIT_DAMAGE);
        int wisdom = run.blessing("wisdom");
        flatAndPercent(s, "Blessing of Wisdom", wisdom, Stat.INTELLIGENCE);
        if (wisdom > 0) {
            s.add(StatSheet.Source.BLESSINGS, "Blessing of Wisdom", Stat.SPEED, 4.8 * wisdom);
        }
        int stone = run.blessing("stone");
        flatAndPercent(s, "Blessing of Stone", stone, Stat.DEFENSE);
        s.weaponDamage += 7.2 * stone;
        int life = run.blessing("life");
        if (life > 0) {
            s.multiply("Blessing of Life", Stat.HEALTH, 1 + 0.044 * life);
            s.multiply("Blessing of Life", Stat.HEALTH_REGEN, 1 + 0.044 * life);
        }
        int time = run.blessing("time");
        for (Stat stat : new Stat[] {Stat.HEALTH, Stat.DEFENSE, Stat.INTELLIGENCE, Stat.STRENGTH}) {
            flatAndPercent(s, "Blessing of Time", time, stat);
        }
    }

    private static void flatAndPercent(StatSheet s, String label, int level, Stat stat) {
        if (level <= 0) {
            return;
        }
        s.add(StatSheet.Source.BLESSINGS, label, stat, 5.8 * level);
        s.multiply(label, stat, 1 + 0.03 * level);
    }
}
