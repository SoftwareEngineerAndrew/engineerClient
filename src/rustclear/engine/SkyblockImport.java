package net.rustclear.mod.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * A player's real SkyBlock inventory as a [Kit]: what the lobby's /v1/hypixel/me read off their
 * selected Hypixel profile (the inventory as Hypixel stores it, gzipped NBT in base64, and their
 * pets), turned into the items the Rustclear world has. Their hotbar and inventory slot for slot
 * (the SkyBlock Menu's 9th slot stays the map's), armour, equipment and pets; and their Ender
 * Chest pages and backpacks for the Storage menu. Items Rustclear doesn't have are left out and
 * listed in [Result.missing]. Free of Minecraft, so it can be tested.
 *
 * Each item keeps its stars, master stars, floor tier and quality, Necron scrolls, recombobulator
 * and reforge name, and as [Kit.Entry.bonus] whatever its lore totals beyond its base stats
 * (reforge, potato books, gemstones, enchantments). Hypixel's lore outside dungeons shows each
 * stat as base x (1 + 2% a star) + those extras (+ for Necron's Blades their per-Catacombs-level
 * stats), and its grey dungeon number is (base + extras) x the dungeon bonus, which is how the
 * engine scales a bonus (checked against real profiles, Oct 2026).
 */
public final class SkyblockImport {
    /** Hypixel ids the Rustclear world has under another id. */
    private static final Map<String, String> ALIASES = Map.of("INFINITE_SUPERBOOM_TNT", "SUPERBOOM_TNT");
    /** The Ender Chest's page size; a backpack's is its own. */
    public static final int PAGE = 45;
    /** Hypixel's armour list runs boots first. */
    private static final List<String> ARMOR_ORDER = List.of("feet", "legs", "chest", "head");
    private static final Pattern STAT_LINE = Pattern.compile("^§7([A-Za-z ]+): §[0-9a-fk-or]([+-]?[0-9,]+(?:\\.[0-9]+)?)");
    private static final Pattern COLOR = Pattern.compile("§.");

    /** One Ender Chest page or backpack: its [title], [size] slots, and the items it has by slot ("0".."44"). */
    public static final class Page {
        public String title;
        public int size;
        public Map<String, Kit.Entry> slots = new LinkedHashMap<>();
    }

    /** What a profile gave: [kit] (null when its Inventory API is off), [storage], and names of items left out with how many. */
    public record Result(Kit kit, List<Page> storage, Map<String, Integer> missing, int items) {
        public int missingCount() {
            return missing.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private SkyblockImport() {
    }

    /**
     * [body] (the lobby's answer) as a kit and storage. [kind] is KitItems.kind (null: an id the
     * Rustclear world doesn't have); [cata] the profile's Catacombs level, for Necron's Blades' lore.
     */
    public static Result read(JsonObject body, Function<String, String> kind, int cata) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        int[] items = {0};
        JsonObject inv = body.has("inventory") && body.get("inventory").isJsonObject() ? body.getAsJsonObject("inventory") : null;
        Kit kit = null;
        List<Page> storage = new ArrayList<>();
        if (inv != null) {
            kit = new Kit();
            List<Map<String, Object>> main = items(inv, "inv");
            for (int i = 0; i < main.size() && i < Kit.SLOTS; i++) {
                if (i != Kit.MAP_SLOT) {
                    put(kit, Kit.key(i), main.get(i), kind, cata, missing, items);
                }
            }
            List<Map<String, Object>> armor = items(inv, "armor");
            for (int i = 0; i < armor.size() && i < ARMOR_ORDER.size(); i++) {
                put(kit, ARMOR_ORDER.get(i), armor.get(i), kind, cata, missing, items);
            }
            List<Map<String, Object>> equipment = items(inv, "equipment");
            for (int i = 0; i < equipment.size() && i < Kit.EQUIPMENT.size(); i++) {
                put(kit, Kit.EQUIPMENT.get(i), equipment.get(i), kind, cata, missing, items);
            }
            List<Map<String, Object>> ender = items(inv, "enderChest");
            for (int p = 0; p * PAGE < ender.size(); p++) {
                storage.add(page("Ender Chest Page " + (p + 1), ender.subList(p * PAGE, Math.min(ender.size(), (p + 1) * PAGE)),
                    kind, cata, missing, items));
            }
            JsonObject packs = inv.has("backpacks") && inv.get("backpacks").isJsonObject() ? inv.getAsJsonObject("backpacks") : new JsonObject();
            List<String> slots = new ArrayList<>(packs.keySet());
            slots.sort(Comparator.comparingInt(SkyblockImport::number));
            for (String slot : slots) {
                storage.add(page("Backpack Slot " + (number(slot) + 1), items(packs, slot), kind, cata, missing, items));
            }
            pets(kit, body.get("pets"));
        }
        return new Result(kit, storage, missing, items[0]);
    }

    private static Page page(String title, List<Map<String, Object>> list, Function<String, String> kind, int cata,
                             Map<String, Integer> missing, int[] items) {
        Page page = new Page();
        page.title = title;
        page.size = Math.min(PAGE, list.size());
        for (int i = 0; i < page.size; i++) {
            Kit.Entry e = entry(list.get(i), kind, cata, missing, items);
            if (e != null) {
                page.slots.put(Integer.toString(i), e);
            }
        }
        return page;
    }

    private static void put(Kit kit, String key, Map<String, Object> item, Function<String, String> kind, int cata,
                            Map<String, Integer> missing, int[] items) {
        Kit.Entry e = entry(item, kind, cata, missing, items);
        if (e != null) {
            kit.slots.put(key, e);
        }
    }

    /** One item as a kit entry, or null when the slot is empty or the Rustclear world doesn't have it (then it's in [missing]). */
    static Kit.Entry entry(Map<String, Object> item, Function<String, String> kind, int cata, Map<String, Integer> missing, int[] items) {
        Map<String, Object> tag = map(item == null ? null : item.get("tag"));
        Map<String, Object> extra = map(tag.get("ExtraAttributes"));
        Map<String, Object> display = map(tag.get("display"));
        String hypixelId = extra.get("id") instanceof String s ? s : null;
        if (hypixelId == null) {
            return null;
        }
        if (hypixelId.equals("SKYBLOCK_MENU")) {
            return null;
        }
        items[0]++;
        String id = ALIASES.getOrDefault(hypixelId, hypixelId);
        if (kind.apply(id) == null) {
            String name = display.get("Name") instanceof String n ? plain(n) : hypixelId;
            missing.merge(name.isEmpty() ? hypixelId : name, 1, Integer::sum);
            return null;
        }
        int level = number(extra.get("upgrade_level"), number(extra.get("dungeon_item_level"), 0));
        int stars = Math.min(ItemStats.MAX_STARS, level);
        Kit.Entry e = new Kit.Entry(id, stars, Math.max(0, level - ItemStats.MAX_STARS),
            number(extra.get("item_tier"), ItemStats.MAX_TIER), number(extra.get("baseStatBoostPercentage"), ItemStats.MAX_QUALITY),
            strings(extra.get("ability_scroll")));
        e.recomb = number(extra.get("rarity_upgrades"), 0) > 0;
        e.setMods(mods(extra));
        ItemDefs.Def def = ItemDefs.get().def(id);
        if (def != null) {
            if (display.get("Name") instanceof String name) {
                e.reforge = reforge(plain(name), def.name);
            }
            e.bonus = bonus(def, e, strings(display.get("Lore")), cata);
        }
        return e;
    }

    /** The words before the item's own name in [name] ("Ancient" in "Ancient Necron's Chestplate ✪✪✪✪✪"), or null. */
    static String reforge(String name, String own) {
        own = own.replaceFirst("^[^A-Za-z]+", "");
        int at = name.indexOf(own);
        if (at <= 0) {
            return null;
        }
        String prefix = name.substring(0, at).replaceAll("[^A-Za-z' ]", "").trim();
        return prefix.isEmpty() || prefix.length() > 24 ? null : prefix;
    }

    /**
     * An item's mods from Hypixel's ExtraAttributes: modifier (reforge), hot_potato_count,
     * artOfPeaceApplied, art_of_war_count, enchantments, and gems ("SAPPHIRE_0": "PERFECT", or a
     * universal/combat slot "COMBAT_0": "PERFECT" with its type in "COMBAT_0_gem").
     */
    static ItemMods mods(Map<String, Object> extra) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        map(extra.get("enchantments")).forEach((k, v) -> enchants.put(k, number(v, 0)));
        List<String> gems = new ArrayList<>();
        Map<String, Object> g = map(extra.get("gems"));
        for (Map.Entry<String, Object> slot : g.entrySet()) {
            String key = slot.getKey();
            if (key.endsWith("_gem") || key.equals("unlocked_slots") || !key.matches("[A-Z]+_\\d+")) {
                continue;
            }
            Object v = slot.getValue();
            String quality = v instanceof String s ? s : map(v).get("quality") instanceof String s ? s : null;
            String type = key.substring(0, key.lastIndexOf('_'));
            if (g.get(key + "_gem") instanceof String t) {
                type = t;
            }
            if (quality != null) {
                gems.add(type + ":" + quality);
            }
        }
        return new ItemMods(extra.get("modifier") instanceof String m ? m : null, number(extra.get("hot_potato_count"), 0),
            number(extra.get("artOfPeaceApplied"), 0) > 0, number(extra.get("art_of_war_count"), 0) > 0, enchants, gems);
    }

    /** What [lore]'s stat lines total beyond [def]'s base stats and the entry's mods, by stat; null when nothing. */
    static Map<String, Double> bonus(ItemDefs.Def def, Kit.Entry e, List<String> lore, int cata) {
        Map<String, Double> out = new LinkedHashMap<>();
        ItemDefs.Instance modded = new ItemDefs.Instance(def, e.stars, e.masterStars, e.tier, e.quality, e.scrolls, null, null,
            e.recomb, e.mods());
        for (String line : lore) {
            Matcher m = STAT_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            String stat = m.group(1).equals("Bonus Attack Speed") ? "Attack Speed" : m.group(1);
            if (!ItemDefs.STAT_ORDER.contains(stat) || ItemStats.neverScaled(stat) || ItemStats.starless(stat)) {
                continue;
            }
            double total;
            try {
                total = Double.parseDouble(m.group(2).replace(",", ""));
            } catch (NumberFormatException ex) {
                continue;
            }
            double base = ItemStats.outside(stat, def.base(stat, e.tier, e.quality), e.stars);
            double extra = total - base - StatBuilder.necronPerCata(def.id, stat) * cata - modded.mod(stat, cata);
            extra = Math.round(extra * 100) / 100.0;
            if (Math.abs(extra) >= 0.05) {
                out.put(stat, extra);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The pets the Rustclear world has, the summoned one first, then the best (rarity, level, exp):
     * as many as a kit holds. Hypixel's petInfo gives type, tier, active, exp, heldItem and skin; the
     * level is read when the lobby sends one, else worked out from the exp.
     */
    private static void pets(Kit kit, JsonElement pets) {
        if (pets == null || !pets.isJsonArray()) {
            return;
        }
        record Owned(PetDefs.Type type, PetDefs.Rarity rarity, int level, double exp, boolean active, String heldItem, String skin) {}
        java.util.function.BiFunction<JsonObject, String, String> text = (o, key) ->
            o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        List<Owned> owned = new ArrayList<>();
        for (JsonElement el : (JsonArray) pets) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject p = el.getAsJsonObject();
            PetDefs.Type type = p.has("type") ? PetDefs.Type.of(p.get("type").getAsString()) : null;
            PetDefs.Rarity rarity = p.has("tier") ? PetDefs.Rarity.of(p.get("tier").getAsString()) : null;
            if (type == null || rarity == null) {
                continue;
            }
            double exp = p.has("exp") && p.get("exp").isJsonPrimitive() ? p.get("exp").getAsDouble() : 0;
            int level = p.has("level") ? p.get("level").getAsInt()
                : p.has("exp") ? PetDefs.levelFor(type, PetDefs.fit(type, rarity), exp) : 1;
            owned.add(new Owned(type, rarity, level, exp, p.has("active") && p.get("active").getAsBoolean(),
                text.apply(p, "heldItem"), text.apply(p, "skin")));
        }
        owned.sort(Comparator.comparing(Owned::active).reversed()
            .thenComparing(Comparator.comparing(Owned::rarity).reversed())
            .thenComparing(Comparator.comparingInt(Owned::level).reversed())
            .thenComparing(Comparator.comparingDouble(Owned::exp).reversed()));
        for (Owned o : owned) {
            if (kit.pets.size() >= Kit.MAX_PETS) {
                break;
            }
            if (o.active) {
                kit.activePet = kit.pets.size();
            }
            Kit.Pet pet = new Kit.Pet(o.type.name(), o.rarity.name(), o.level);
            pet.exp = o.exp;
            pet.heldItem = o.heldItem;
            pet.skin = o.skin;
            kit.pets.add(pet);
        }
    }

    // ---------------------------------------------------------------- NBT

    /** The item list in [parent]'s base64 blob [key] (each null for an empty slot), or an empty list. */
    private static List<Map<String, Object>> items(JsonObject parent, String key) {
        JsonElement blob = parent.get(key);
        if (blob == null || !blob.isJsonPrimitive()) {
            return List.of();
        }
        try {
            Object root = nbt(Base64.getDecoder().decode(blob.getAsString()));
            List<Map<String, Object>> out = new ArrayList<>();
            if (map(root).get("i") instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> m = map(o);
                    out.add(m.isEmpty() ? null : m);
                }
            }
            return out;
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
    }

    /** Gzipped NBT as maps, lists, numbers and strings. */
    static Object nbt(byte[] gzipped) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gzipped)))) {
            int type = in.readByte();
            if (type == 0) {
                return Map.of();
            }
            in.readUTF();
            return payload(in, type, 0);
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("NBT nested too deep");
        }
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] b = new byte[length(in)];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int inner = in.readByte();
                int n = length(in);
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, inner, depth + 1));
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t;
                    try {
                        t = in.readByte();
                    } catch (EOFException e) {
                        return map;
                    }
                    if (t == 0) {
                        return map;
                    }
                    String name = in.readUTF();
                    map.put(name, payload(in, t, depth + 1));
                }
            }
            case 11: {
                int n = length(in);
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                int n = length(in);
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1 << 20) {
            throw new IOException("Bad NBT length " + n);
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof String str) {
                    out.add(str);
                }
            }
        }
        return out;
    }

    private static int number(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** [s] without § codes. */
    static String plain(String s) {
        return COLOR.matcher(s).replaceAll("").trim();
    }
}
