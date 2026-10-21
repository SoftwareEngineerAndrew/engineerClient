package net.rustclear.mod.engine;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Every Dungeon and dungeonizable SkyBlock item (Wither, Undead, Spider, Dragon, Gold, Diamond and
 * Ice essence gear, starred and Necron variants, and the non-gear dungeon items), read from
 * items.json.gz (tools/pack_item_data.py). Most of them are data only: their stats, abilities and
 * looks come from here, and [DataItems] turns one into an ItemStack. Free of Minecraft, so the
 * tooltip can be tested.
 */
public final class ItemDefs {
    /** One item as items.json.gz has it (Gson fills the fields). */
    public static final class Def {
        public String id;
        public String name;
        public String rarity;
        public String rarityLine;
        public String type;
        public boolean nativeDungeon;
        public String essence;
        public int[] starCosts;
        public Map<String, Double> stats = Map.of();
        public Map<String, Double> classStats = Map.of();
        /** Mob drops: each stat's ten floor tiers. */
        public Map<String, double[]> tiered = Map.of();
        public String material;
        public Integer color;
        public String skin;
        public String signature;
        public boolean glowing;
        /** "melee", "shoot" or "none". */
        public String left;
        /** "ability", "shoot", "draw", "class" or "none". */
        public String right;
        public List<Ability> abilities = List.of();
        public List<String> requirements = List.of();
        public Double abilityDamage;
        public Double abilityScaling;
        public Integer cataReq;

        public boolean isTiered() {
            return tiered != null && !tiered.isEmpty();
        }

        public boolean isShortbow() {
            return "shoot".equals(right);
        }

        /** Gear worn in an armour slot (heads included). */
        public boolean isArmor() {
            return switch (type) {
                case "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS" -> true;
                default -> false;
            };
        }

        /** Necron's Blade family: their abilities come from scrolls. */
        public boolean takesScrolls() {
            return "class".equals(right);
        }

        /** The ability [click] ("RIGHT CLICK", "LEFT CLICK") triggers, or null. */
        public Ability ability(String click) {
            for (Ability a : abilities) {
                if ("Ability".equals(a.kind) && click.equals(a.click)) {
                    return a;
                }
            }
            return null;
        }

        /** [stat] before stars and dungeon bonuses: the floor tier's for a mob drop, else the fixed one (0 if none). */
        public double base(String stat, int tier, int quality) {
            if (isTiered() && tiered.containsKey(stat)) {
                return ItemStats.tieredBase(tiered.get(stat), tier, quality);
            }
            Double value = stats.get(stat);
            return value == null ? 0 : value;
        }

        /** Every stat this item has, in tooltip order. */
        public List<String> statNames() {
            List<String> out = new ArrayList<>();
            for (String stat : STAT_ORDER) {
                if (stats.containsKey(stat) || (tiered != null && tiered.containsKey(stat))) {
                    out.add(stat);
                }
            }
            return out;
        }
    }

    /** An ability or bonus line block. [kind]: "Ability", "Full Set Bonus", "Piece Bonus", "Tiered Bonus" or "Shortbow". */
    public static final class Ability {
        public String kind;
        public String name;
        public String click;
        public List<String> text = List.of();
        public Double mana;
        public Double cooldown;
        public Double soulflow;
        public Double healthCost;
        public Double vitalityCost;
    }

    /** A Necron's Blade scroll: its ability, as the scroll items describe it. */
    public static final List<String> SCROLLS = List.of("IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL");

    static final List<String> STAT_ORDER = List.of("Shot Cooldown", "Damage", "Strength", "Crit Chance", "Crit Damage",
        "Attack Speed", "Ability Damage", "Ferocity", "Swing Range", "Health", "Defense", "Speed", "Intelligence",
        "True Defense", "Health Regen", "Magic Find", "Vitality", "Mending", "Fishing Speed");

    private static final String RESOURCE = "/assets/rustclear/engine/items.json.gz";
    private static final String EXTRA = "/assets/rustclear/engine/items_extra.json";
    private static volatile ItemDefs instance;

    private final Map<String, Def> byId = new LinkedHashMap<>();
    private final int[] cataBonus;

    private static final class File {
        int[] cataBonus;
        List<Def> items;
    }

    public static ItemDefs get() {
        ItemDefs d = instance;
        if (d == null) {
            synchronized (ItemDefs.class) {
                if (instance == null) {
                    instance = new ItemDefs();
                }
                d = instance;
            }
        }
        return d;
    }

    private ItemDefs() {
        File file;
        try (InputStream raw = ItemDefs.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            file = new Gson().fromJson(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8), File.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Def def : file.items) {
            if (def.stats == null) {
                def.stats = Map.of();
            }
            if (def.classStats == null) {
                def.classStats = Map.of();
            }
            if (def.tiered == null) {
                def.tiered = Map.of();
            }
            if (def.abilities == null) {
                def.abilities = List.of();
            }
            byId.put(def.id, def);
        }
        cataBonus = file.cataBonus;
        // Items the packed list lacks, written by hand from recorded lore and Hypixel's item API.
        try (InputStream raw = ItemDefs.class.getResourceAsStream(EXTRA)) {
            if (raw != null) {
                File extra = new Gson().fromJson(new InputStreamReader(raw, StandardCharsets.UTF_8), File.class);
                for (Def def : extra.items) {
                    if (def.classStats == null) {
                        def.classStats = Map.of();
                    }
                    if (def.tiered == null) {
                        def.tiered = Map.of();
                    }
                    byId.putIfAbsent(def.id, def);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Def def(String id) {
        return id == null ? null : byId.get(id.toUpperCase(Locale.ROOT));
    }

    public Collection<Def> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** The data file's Catacombs bonus table (the same numbers ItemStats has). */
    int[] cataBonusTable() {
        return cataBonus.clone();
    }

    // ---------------------------------------------------------------- tooltips

    /**
     * One item as carried: stars, master stars, floor tier and quality (mob drops), its scrolls,
     * and what an item read off a Hypixel profile has on top of its base stats ([bonus]: reforge,
     * potato books, gemstones and enchantments, by stat name, as the item's own lore totals them
     * outside dungeons), its reforge's name and whether it's recombobulated.
     */
    public record Instance(Def def, int stars, int masterStars, int tier, int quality, List<String> scrolls,
                           Map<String, Double> bonus, String reforge, boolean recomb, ItemMods mods) {
        public Instance {
            stars = Math.max(0, Math.min(ItemStats.MAX_STARS, stars));
            masterStars = stars == ItemStats.MAX_STARS ? Math.max(0, Math.min(ItemStats.MAX_MASTER_STARS, masterStars)) : 0;
            tier = Math.max(1, Math.min(ItemStats.MAX_TIER, tier));
            quality = Math.max(0, Math.min(ItemStats.MAX_QUALITY, quality));
            scrolls = scrolls == null ? List.of() : List.copyOf(scrolls);
            bonus = bonus == null ? Map.of() : Map.copyOf(bonus);
            mods = mods == null ? ItemMods.NONE : mods;
            if (reforge == null && mods.reforge() != null && ItemMods.reforgeOf(mods.reforge()) != null) {
                reforge = ItemMods.reforgeOf(mods.reforge()).name();
            }
        }

        public Instance(Def def, int stars, int masterStars, int tier, int quality, List<String> scrolls) {
            this(def, stars, masterStars, tier, quality, scrolls, Map.of(), null, false, ItemMods.NONE);
        }

        public Instance(Def def, int stars, int masterStars, int tier, int quality, List<String> scrolls,
                        Map<String, Double> bonus, String reforge, boolean recomb) {
            this(def, stars, masterStars, tier, quality, scrolls, bonus, reforge, recomb, ItemMods.NONE);
        }

        /** A weapon (its potato books add Damage and Strength, Art of War Strength). */
        public boolean weapon() {
            return !def.isArmor() && def.type != null && (def.type.contains("SWORD") || def.type.contains("BOW"));
        }

        /** What the mods (reforge, books, Art of Peace/War, gems, enchantments) add to [stat], for a player at [cata]. */
        public double mod(String stat, int cata) {
            return mods.total(stat, rarity(), def.isArmor(), weapon(), cata);
        }

        /** What a Hypixel profile's item adds to [stat] on top of its base (0: nothing). */
        public double bonus(String stat) {
            Double v = bonus.get(stat);
            return v == null ? 0 : v;
        }

        /** Every stat this item has, its bonus and mod ones too, in tooltip order. */
        public List<String> statNames() {
            if (bonus.isEmpty() && mods.isEmpty()) {
                return def.statNames();
            }
            List<String> out = new ArrayList<>();
            List<String> own = def.statNames();
            java.util.Set<String> modded = mods.stats(rarity(), def.isArmor(), weapon(), ItemStats.MAX_CATA);
            for (String stat : STAT_ORDER) {
                if (own.contains(stat) || bonus.containsKey(stat) || modded.contains(stat)) {
                    out.add(stat);
                }
            }
            return out;
        }

        /** All three Necron scrolls: Wither Impact. */
        public boolean witherImpact() {
            return def.takesScrolls() && scrolls.containsAll(SCROLLS);
        }

        /** [stat] in the Catacombs for a player at [cata], with this item's stars. */
        public double inDungeon(String stat, int cata, boolean masterMode) {
            return ItemStats.inDungeon(stat, def.base(stat, tier, quality) + mod(stat, cata) + bonus(stat), cata, stars, masterStars,
                masterMode, 0);
        }

        public String rarity() {
            int i = RARITIES.indexOf(def.rarity);
            // A perfect (50/50) drop is one rarity up, and so is a recombobulated item.
            int up = (def.isTiered() && quality >= ItemStats.MAX_QUALITY ? 1 : 0) + (recomb ? 1 : 0);
            if (up > 0 && i >= 0 && i < RARITIES.size() - 3) {
                return RARITIES.get(Math.min(i + up, RARITIES.size() - 3));
            }
            return def.rarity;
        }

        /** The bottom line: rarity, DUNGEON once starred (or always for native dungeon items), and the type. */
        public String rarityLine() {
            String rest = def.rarityLine.substring(def.rarity.length()).trim();
            if (stars > 0 && !rest.startsWith("DUNGEON")) {
                rest = rest.isEmpty() ? "DUNGEON" : "DUNGEON " + rest;
            }
            return rest.isEmpty() ? rarity() : rarity() + " " + rest;
        }
    }

    static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE",
        "SPECIAL", "VERY SPECIAL");

    public static String rarityColor(String rarity) {
        return switch (rarity) {
            case "UNCOMMON" -> "§a";
            case "RARE" -> "§9";
            case "EPIC" -> "§5";
            case "LEGENDARY" -> "§6";
            case "MYTHIC" -> "§d";
            case "DIVINE" -> "§b";
            case "SPECIAL", "VERY SPECIAL" -> "§c";
            default -> "§f";
        };
    }

    /** The item's name as Hypixel shows it: rarity colour, then ✪ per star and ➊..➎ for master stars. */
    public static String displayName(Instance item) {
        StringBuilder name = new StringBuilder(rarityColor(item.rarity()));
        if (item.reforge != null && !item.reforge.isEmpty()) {
            // After a leading icon, as Hypixel names them ("⚚ Heroic Bonzo's Staff").
            String own = item.def.name;
            int at = 0;
            while (at < own.length() && !Character.isLetter(own.charAt(at))) {
                at++;
            }
            name.append(own, 0, at).append(item.reforge).append(' ').append(own.substring(at));
        } else {
            name.append(item.def.name);
        }
        if (item.stars > 0) {
            name.append(" §6").append("✪".repeat(item.stars));
            if (item.masterStars > 0) {
                name.append("§c").append((char) ('➊' + item.masterStars - 1));
            }
        }
        return name.toString();
    }

    /**
     * The tooltip under the name, § codes and all, as Hypixel draws it inside the dungeon instance
     * (stats.md §4): each stat's main number is its Catacombs value for a player at [cata] (no grey
     * outside-value bracket), then abilities and bonuses and the rarity line (obfuscated "a" either
     * side when recombobulated). No Item Quality or Floor Tier lines: Hypixel shows none.
     */
    public static List<String> lore(Instance item, int cata, boolean masterMode) {
        Def def = item.def;
        List<String> lines = new ArrayList<>();
        for (String stat : item.statNames()) {
            double base = def.base(stat, item.tier, item.quality);
            StringBuilder line = new StringBuilder("§7").append(label(stat)).append(": ").append(statColor(stat));
            if (stat.equals("Shot Cooldown")) {
                line.append(number(base)).append("s");
            } else {
                line.append("+").append(number(item.inDungeon(stat, cata, masterMode))).append(percent(stat));
                line.append(brackets(item, stat));
            }
            lines.add(line.toString());
        }
        ItemMods mods = item.mods;
        if (!mods.gems().isEmpty()) {
            StringBuilder g = new StringBuilder("§7Gemstones:");
            for (String gem : mods.gems()) {
                // OPEN: the gem's own symbol per type; Hypixel colours the bracket by quality (items-stats.md §4).
                g.append(" ").append(gemQuality(gem)).append("[").append(gemColor(gem)).append("◆").append(gemQuality(gem)).append("]");
            }
            lines.add(g.toString());
        }
        List<String> enchantLines = mods.enchantLines();
        if (!enchantLines.isEmpty()) {
            lines.add("");
            lines.addAll(enchantLines);
        }
        for (Map.Entry<String, Double> e : def.classStats.entrySet()) {
            lines.add("§7" + e.getKey() + ": §a+" + number(e.getValue()));
        }
        if (def.takesScrolls()) {
            lines.add("");
            if (item.witherImpact()) {
                lines.add("§aScroll Abilities:");
                lines.add("§6Ability: Wither Impact  §e§lRIGHT CLICK");
                lines.add("§7Teleport §a10 blocks§7 ahead of you.");
                lines.add("§7Then implode dealing damage to nearby");
                lines.add("§7enemies. Also applies the wither shield");
                lines.add("§7scroll ability reducing damage taken");
                lines.add("§7and granting an absorption shield.");
                lines.add("§8Mana Cost: §3300");
            } else {
                lines.add("§7Right-click to use your class ability!");
                if (!item.scrolls.isEmpty()) {
                    lines.add("§aScrolls: §7" + String.join(", ", item.scrolls));
                }
            }
        }
        for (Ability a : def.abilities) {
            lines.add("");
            switch (a.kind) {
                case "Shortbow" -> lines.add("§dShortbow: " + a.name);
                case "Ability" -> lines.add("§6Ability: " + a.name + (a.click == null ? "" : "  §e§l" + a.click));
                default -> lines.add("§6" + a.kind + ": " + a.name);
            }
            for (String t : a.text) {
                lines.add("§7" + t);
            }
            if (a.mana != null) {
                lines.add("§8Mana Cost: §3" + number(a.mana));
            }
            if (a.healthCost != null) {
                lines.add("§8Health Cost: §c" + number(a.healthCost));
            }
            if (a.vitalityCost != null) {
                lines.add("§8Vitality Cost: §4" + number(a.vitalityCost));
            }
            if (a.soulflow != null) {
                lines.add("§8Soulflow Cost: §3" + number(a.soulflow));
            }
            if (a.cooldown != null) {
                lines.add("§8Cooldown: §a" + number(a.cooldown) + "s");
            }
        }
        lines.add("");
        String color = rarityColor(item.rarity());
        lines.add(item.recomb
            ? color + "§l§ka§r " + color + "§l" + item.rarityLine() + " " + color + "§l§ka"
            : color + "§l" + item.rarityLine());
        return lines;
    }

    /**
     * A stat line's brackets after the main number, raw (not dungeon-scaled), in Hypixel's order:
     * §e(+books) §c[+Art of Peace] §6[+Art of War] §9(+reforge) §d(+gems). Crit lines are §9 all
     * through, so their brackets carry no colour of their own.
     */
    private static String brackets(Instance item, String stat) {
        ItemMods m = item.mods;
        boolean crit = stat.equals("Crit Chance") || stat.equals("Crit Damage");
        String pct = percent(stat);
        StringBuilder sb = new StringBuilder();
        double books = m.bookStat(stat, item.def.isArmor(), item.weapon());
        double peace = m.peaceStat(stat, item.def.isArmor());
        double war = m.warStat(stat, item.weapon());
        double reforge = m.reforgeStat(stat, item.rarity());
        double gem = m.gemStat(stat, item.rarity());
        if (books != 0) {
            sb.append(crit ? " (" : " §e(+").append(crit ? "+" : "").append(number(books)).append(pct).append(")");
        }
        if (peace != 0) {
            sb.append(" §c[+").append(number(peace)).append(pct).append("]");
        }
        if (war != 0) {
            sb.append(" §6[+").append(number(war)).append(pct).append("]");
        }
        if (reforge != 0) {
            sb.append(crit ? " (+" : " §9(+").append(number(reforge)).append(pct).append(")");
        }
        if (gem != 0) {
            sb.append(crit ? " (+" : " §d(+").append(number(gem)).append(pct).append(")");
        }
        return sb.toString();
    }

    private static String gemQuality(String gem) {
        String q = gem.contains(":") ? gem.substring(gem.indexOf(':') + 1) : "";
        return switch (q) {
            case "PERFECT" -> "§6";
            case "FLAWLESS" -> "§5";
            case "FINE" -> "§9";
            default -> "§f";
        };
    }

    private static String gemColor(String gem) {
        return switch (gem.contains(":") ? gem.substring(0, gem.indexOf(':')) : gem) {
            case "SAPPHIRE" -> "§b";
            case "JASPER" -> "§d";
            case "ONYX" -> "§8";
            case "PERIDOT" -> "§2";
            case "AMBER" -> "§6";
            case "JADE" -> "§a";
            case "TOPAZ" -> "§e";
            default -> "§f";
        };
    }

    private static String label(String stat) {
        return stat;
    }

    private static String percent(String stat) {
        return switch (stat) {
            case "Crit Chance", "Crit Damage", "Attack Speed", "Ability Damage" -> "%";
            default -> "";
        };
    }

    /** A stat line's value colour (stats.md §4). */
    private static String statColor(String stat) {
        return switch (stat) {
            case "Damage", "Strength", "Ability Damage", "Ferocity", "Health", "Health Regen", "Vitality" -> "§c";
            case "Crit Chance", "Crit Damage" -> "§9";
            case "Attack Speed", "Swing Range" -> "§e";
            case "Intelligence", "Magic Find" -> "§b";
            case "Speed", "True Defense" -> "§f";
            default -> "§a";
        };
    }

    /** 1,521 or 16.5: thousands separated, at most one decimal. */
    static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.rint(rounded)) {
            return String.format(Locale.ROOT, "%,d", (long) rounded);
        }
        return String.format(Locale.ROOT, "%,.1f", rounded);
    }
}
