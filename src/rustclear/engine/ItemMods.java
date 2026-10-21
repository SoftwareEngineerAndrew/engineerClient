package net.rustclear.mod.engine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a SkyBlock item has on top of its base stats and stars, the Hypixel way (items-stats.md):
 * a reforge (by its id, Hypixel's custom_data "modifier"), Hot Potato Books, Art of Peace and Art of
 * War, gemstones, and enchantments. Each part's stats come from the values measured on Hypixel
 * (item_mods.json, packed by tools/items/pack_item_mods.py):
 * - a reforge's stats depend only on it and the item's final rarity (SPECIAL and VERY SPECIAL use
 *   MYTHIC's);
 * - each potato book is +4 Health and +2 Defense on armour, +2 Damage and +2 Strength on a weapon;
 * - Art of Peace +40 Health on armour, Art of War +5 Strength on a weapon;
 * - a gem's stat by its quality and the item's rarity;
 * - Growth, Protection and the other stat enchantments by level, Hecatomb by Catacombs level.
 * All of them are dungeon-scaled with the base (ItemStats). Free of Minecraft.
 *
 * [gems] are "TYPE:QUALITY" ("SAPPHIRE:PERFECT"); [enchants] are by Hypixel's id ("growth": 5).
 */
public record ItemMods(String reforge, int potatoBooks, boolean artOfPeace, boolean artOfWar,
                       Map<String, Integer> enchants, List<String> gems) {
    public static final ItemMods NONE = new ItemMods(null, 0, false, false, Map.of(), List.of());

    public ItemMods {
        reforge = reforge == null || reforge.isBlank() ? null : reforge.toLowerCase(Locale.ROOT);
        potatoBooks = Math.max(0, Math.min(15, potatoBooks));
        enchants = enchants == null ? Map.of() : Map.copyOf(enchants);
        gems = gems == null ? List.of() : List.copyOf(gems);
    }

    public boolean isEmpty() {
        return reforge == null && potatoBooks == 0 && !artOfPeace && !artOfWar && enchants.isEmpty() && gems.isEmpty();
    }

    // ---------------------------------------------------------------- data

    private record Data(Map<String, Reforge> reforges, Map<String, Map<String, Map<String, Double>>> statEnchants,
                        Map<String, Gem> gems, Map<String, String> names) {}

    record Reforge(String name, String kind, Map<String, Map<String, Double>> rarities, List<String> bonus) {}

    private record Gem(String stat, Map<String, Map<String, Double>> qualities) {}

    private static Data data;

    private static synchronized Data data() {
        if (data == null) {
            try (InputStream in = ItemMods.class.getResourceAsStream("/assets/rustclear/engine/item_mods.json")) {
                JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                Map<String, Reforge> reforges = new LinkedHashMap<>();
                root.getAsJsonObject("reforges").entrySet().forEach(e -> {
                    JsonObject r = e.getValue().getAsJsonObject();
                    Map<String, Map<String, Double>> rar = new LinkedHashMap<>();
                    r.getAsJsonObject("rarities").entrySet().forEach(x -> rar.put(x.getKey(), numbers(x.getValue().getAsJsonObject())));
                    List<String> bonus = new ArrayList<>();
                    if (r.has("bonus")) {
                        r.getAsJsonArray("bonus").forEach(b -> bonus.add(b.getAsString()));
                    }
                    reforges.put(e.getKey(), new Reforge(r.get("name").getAsString(), r.get("class").getAsString(), rar, bonus));
                });
                Map<String, Map<String, Map<String, Double>>> stat = new LinkedHashMap<>();
                root.getAsJsonObject("statEnchants").entrySet().forEach(e -> {
                    Map<String, Map<String, Double>> levels = new LinkedHashMap<>();
                    e.getValue().getAsJsonObject().entrySet().forEach(x -> levels.put(x.getKey(), numbers(x.getValue().getAsJsonObject())));
                    stat.put(e.getKey(), levels);
                });
                Map<String, Gem> gems = new LinkedHashMap<>();
                root.getAsJsonObject("gems").entrySet().forEach(e -> {
                    JsonObject g = e.getValue().getAsJsonObject();
                    Map<String, Map<String, Double>> q = new LinkedHashMap<>();
                    g.getAsJsonObject("qualities").entrySet().forEach(x -> q.put(x.getKey(), numbers(x.getValue().getAsJsonObject())));
                    gems.put(e.getKey(), new Gem(g.get("stat").getAsString(), q));
                });
                Map<String, String> names = new LinkedHashMap<>();
                root.getAsJsonObject("names").entrySet().forEach(e -> names.put(e.getKey(), e.getValue().getAsString()));
                data = new Data(reforges, stat, gems, names);
            } catch (Exception e) {
                throw new IllegalStateException("Can't read item_mods.json", e);
            }
        }
        return data;
    }

    private static Map<String, Double> numbers(JsonObject o) {
        Map<String, Double> out = new LinkedHashMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsDouble()));
        return out;
    }

    /** The reforge [id] ("ancient"), or null when unknown. */
    static Reforge reforgeOf(String id) {
        return id == null ? null : data().reforges().get(id.toLowerCase(Locale.ROOT));
    }

    /** The reforge id whose name is [name] ("Ancient" -> "ancient"), or null. */
    static String reforgeId(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, Reforge> e : data().reforges().entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name.trim())) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- stats by part

    /** The rarity row a table is read at: SPECIAL and VERY SPECIAL read MYTHIC's (items-stats.md §1). */
    private static String row(String rarity) {
        return rarity.equals("SPECIAL") || rarity.equals("VERY SPECIAL") ? "MYTHIC" : rarity;
    }

    /**
     * A rarity-keyed table's value for [stat] at [rarity]. OPEN: a rarity never measured reads the
     * nearest measured one below it, else above.
     */
    private static double at(Map<String, Map<String, Double>> table, String rarity, String stat) {
        String want = row(rarity);
        Map<String, Double> exact = table.get(want);
        if (exact != null) {
            return exact.getOrDefault(stat, 0.0);
        }
        int wanted = ItemDefs.RARITIES.indexOf(want);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String r : table.keySet()) {
            int i = ItemDefs.RARITIES.indexOf(row(r));
            int score = i <= wanted ? (wanted - i) * 2 : (i - wanted) * 2 + 1;
            if (i >= 0 && score < bestScore) {
                best = r;
                bestScore = score;
            }
        }
        return best == null ? 0 : table.get(best).getOrDefault(stat, 0.0);
    }

    /** The reforge's §9 bracket for [stat] at [rarity]. */
    public double reforgeStat(String stat, String rarity) {
        Reforge r = reforgeOf(reforge);
        return r == null ? 0 : at(r.rarities(), rarity, stat);
    }

    /** The potato books' §e bracket: +4 Health / +2 Defense a book on armour, +2 Damage / +2 Strength on a weapon. */
    public double bookStat(String stat, boolean armor, boolean weapon) {
        if (armor) {
            return stat.equals("Health") ? 4.0 * potatoBooks : stat.equals("Defense") ? 2.0 * potatoBooks : 0;
        }
        if (weapon) {
            return stat.equals("Damage") || stat.equals("Strength") ? 2.0 * potatoBooks : 0;
        }
        return 0;
    }

    /** Art of Peace's §c[+40] Health on armour. */
    public double peaceStat(String stat, boolean armor) {
        return artOfPeace && armor && stat.equals("Health") ? 40 : 0;
    }

    /** Art of War's §6[+5] Strength on a weapon. */
    public double warStat(String stat, boolean weapon) {
        return artOfWar && weapon && stat.equals("Strength") ? 5 : 0;
    }

    /** The gems' §d bracket for [stat] at [rarity]. */
    public double gemStat(String stat, String rarity) {
        double sum = 0;
        for (String g : gems) {
            String[] p = g.split(":");
            Gem gem = data().gems().get(p[0].toUpperCase(Locale.ROOT));
            Map<String, Double> byRarity = gem == null || p.length < 2 ? null : gem.qualities().get(p[1].toUpperCase(Locale.ROOT));
            if (byRarity != null && gem.stat().equals(stat)) {
                Map<String, Map<String, Double>> table = new LinkedHashMap<>();
                byRarity.forEach((r, v) -> table.put(r, Map.of(stat, v)));
                sum += at(table, rarity, stat);
            }
        }
        return sum;
    }

    /** The stat enchantments' (unbracketed) part of [stat]; Hecatomb needs the player's Catacombs level. */
    public double enchantStat(String stat, int cata) {
        double sum = 0;
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            String id = e.getKey().toLowerCase(Locale.ROOT);
            if (id.equals("hecatomb")) {
                // (2 + 0.6 x level) Health per 10 Catacombs levels (items-stats.md §5).
                sum += stat.equals("Health") ? (2 + 0.6 * e.getValue()) * (Math.min(50, cata) / 10) : 0;
                continue;
            }
            Map<String, Map<String, Double>> levels = data().statEnchants().get(id);
            Map<String, Double> at = levels == null ? null : levels.get(Integer.toString(e.getValue()));
            if (at != null) {
                sum += at.getOrDefault(stat, 0.0);
            }
        }
        return sum;
    }

    /** Everything these mods add to [stat] on an item of [rarity] (armour or a weapon), for a player at [cata]. */
    public double total(String stat, String rarity, boolean armor, boolean weapon, int cata) {
        return reforgeStat(stat, rarity) + bookStat(stat, armor, weapon) + peaceStat(stat, armor) + warStat(stat, weapon)
            + gemStat(stat, rarity) + enchantStat(stat, cata);
    }

    /** The stats these mods add anything to, for [statNames] to list. */
    public Set<String> stats(String rarity, boolean armor, boolean weapon, int cata) {
        Set<String> out = new LinkedHashSet<>();
        for (String stat : ItemDefs.STAT_ORDER) {
            if (total(stat, rarity, armor, weapon, cata) != 0) {
                out.add(stat);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- enchantments

    /** The ability mana cost multiplier from Ultimate Wise: 10% less a level (its lore, measured). */
    public double manaMultiplier() {
        Integer wise = enchants.get("ultimate_wise");
        return wise == null ? 1 : Math.max(0, 1 - 0.1 * wise);
    }

    public int level(String id) {
        Integer v = enchants.get(id);
        return v == null ? 0 : v;
    }

    /** [id] as lore shows it: Hypixel's renames, "ultimate_" dropped (but Ultimate Wise and Ultimate Jerry keep it). */
    static String shownName(String id) {
        String renamed = data().names().get(id);
        if (renamed != null) {
            return renamed;
        }
        String n = id.toLowerCase(Locale.ROOT);
        if (n.startsWith("ultimate_") && !n.equals("ultimate_wise") && !n.equals("ultimate_jerry")) {
            n = n.substring("ultimate_".length());
        }
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("_")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * The lore's enchantment block (items-stats.md §5): the ultimate first in §d§l, the rest §9 in
     * alphabetical order of their shown names, three a line joined by ", " (continuation lines
     * start §9). Telekinesis is never shown. Empty when there's none.
     */
    public List<String> enchantLines() {
        List<String> ultimate = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        enchants.entrySet().stream()
            .filter(e -> !e.getKey().equalsIgnoreCase("telekinesis"))
            .sorted(Comparator.comparing(e -> shownName(e.getKey())))
            .forEach(e -> {
                String text = shownName(e.getKey()) + " " + MenuText.roman(e.getValue());
                if (e.getKey().toLowerCase(Locale.ROOT).startsWith("ultimate_")) {
                    ultimate.add("§d§l" + text);
                } else {
                    rest.add(text);
                }
            });
        List<String> all = new ArrayList<>(ultimate);
        all.addAll(rest);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) {
            StringBuilder line = new StringBuilder();
            for (int j = i; j < Math.min(all.size(), i + 3); j++) {
                String part = all.get(j);
                boolean ult = part.startsWith("§d");
                line.append(j == i ? "" : ", ").append(ult ? part : (j == i || all.get(j - 1).startsWith("§d") ? "§9" : "") + part);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
