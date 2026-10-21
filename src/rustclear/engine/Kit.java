package net.rustclear.mod.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A player's own starting inventory, built in the Kit editor: which item (and how upgraded) goes
 * in each of the 36 inventory slots and the 4 armour slots, and the hotbar slot held. Saved as
 * config/rustclear/kit.json; a guest's travels to the host on joining. Free of Minecraft, so the
 * file format can be tested ([KitItems] turns entries into ItemStacks).
 *
 * Slot keys are "0".."35" (0-8 the hotbar, 9-35 the bag; never 8, the map's), "head", "chest",
 * "legs", "feet", and the equipment slots "necklace", "cloak", "belt", "gloves". An id is a Hypixel
 * id from [ItemDefs], or an [EngineItem] name, or CHEST. [pets] go in the pet menu, [activePet]
 * (an index into them, -1 none) is summoned. [power] is the selected power stone's power.
 */
public final class Kit {
    /** The armour slot keys, top to bottom. */
    public static final List<String> ARMOR = List.of("head", "chest", "legs", "feet");
    /** The equipment slot keys, in Hypixel's order (Equipment.Slot). */
    public static final List<String> EQUIPMENT = List.of("necklace", "cloak", "belt", "gloves");
    /** A pet menu holds at most this many pets from a kit. */
    public static final int MAX_PETS = 28;
    /** Slot 9 always holds the run's map. */
    public static final int MAP_SLOT = 8;
    public static final int SLOTS = 36;
    /** Every valid slot key, in the order the file lists them. */
    public static final List<String> KEYS;
    private static final int MAX_ID = 64;

    static {
        List<String> keys = new ArrayList<>(ARMOR);
        keys.addAll(EQUIPMENT);
        for (int i = 0; i < SLOTS; i++) {
            if (i != MAP_SLOT) {
                keys.add(Integer.toString(i));
            }
        }
        KEYS = List.copyOf(keys);
    }

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /**
     * One slot's item. Unset numbers in a hand-written file mean a dungeon's best tier and quality.
     * [bonus], [reforge] and [recomb] are only set on items read off a Hypixel profile
     * (SkyblockImport): see ItemDefs.Instance.
     */
    public static final class Entry {
        public String id;
        public int stars;
        public int masterStars;
        public int tier = ItemStats.MAX_TIER;
        public int quality = ItemStats.MAX_QUALITY;
        public List<String> scrolls = new ArrayList<>();
        public Map<String, Double> bonus;
        public String reforge;
        public boolean recomb;
        /** The item's mods (ItemMods): reforge id, potato books, Art of Peace / War, enchantments by id, gems "TYPE:QUALITY". */
        public String modifier;
        public int potatoBooks;
        public boolean artOfPeace;
        public boolean artOfWar;
        public Map<String, Integer> enchants;
        public List<String> gems;

        public Entry() {
        }

        public Entry(String id, int stars, int masterStars, int tier, int quality, List<String> scrolls) {
            this.id = id;
            this.stars = stars;
            this.masterStars = masterStars;
            this.tier = tier;
            this.quality = quality;
            this.scrolls = scrolls == null ? new ArrayList<>() : new ArrayList<>(scrolls);
        }

        /** [item] as a kit entry, upgrades and all. */
        public static Entry of(ItemDefs.Instance item) {
            Entry e = new Entry(item.def().id, item.stars(), item.masterStars(), item.tier(), item.quality(), item.scrolls());
            e.bonus = item.bonus().isEmpty() ? null : new LinkedHashMap<>(item.bonus());
            e.reforge = item.reforge();
            e.recomb = item.recomb();
            e.setMods(item.mods());
            return e;
        }

        /** The entry's mods as ItemMods. */
        public ItemMods mods() {
            return new ItemMods(modifier, potatoBooks, artOfPeace, artOfWar, enchants, gems);
        }

        public void setMods(ItemMods m) {
            modifier = m.reforge();
            potatoBooks = m.potatoBooks();
            artOfPeace = m.artOfPeace();
            artOfWar = m.artOfWar();
            enchants = m.enchants().isEmpty() ? null : new LinkedHashMap<>(m.enchants());
            gems = m.gems().isEmpty() ? null : new ArrayList<>(m.gems());
        }

        public Entry copy() {
            Entry e = new Entry(id, stars, masterStars, tier, quality, scrolls == null ? List.of() : scrolls);
            e.bonus = bonus == null ? null : new LinkedHashMap<>(bonus);
            e.reforge = reforge;
            e.recomb = recomb;
            e.setMods(mods());
            return e;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry e && Objects.equals(id, e.id) && stars == e.stars && masterStars == e.masterStars
                && tier == e.tier && quality == e.quality && Objects.equals(scrolls, e.scrolls)
                && Objects.equals(bonus, e.bonus) && Objects.equals(reforge, e.reforge) && recomb == e.recomb
                && mods().equals(e.mods());
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, stars, masterStars, tier, quality, scrolls, bonus, reforge, recomb, mods());
        }
    }

    /** One pet: its type (PetDefs.Type), rarity and level, and (from Hypixel) its exp, held item and skin. */
    public static final class Pet {
        public String type;
        public String rarity;
        public int level;
        public double exp;
        public String heldItem;
        public String skin;

        public Pet() {
        }

        public Pet(String type, String rarity, int level) {
            this.type = type;
            this.rarity = rarity;
            this.level = level;
        }

        public Pet copy() {
            Pet p = new Pet(type, rarity, level);
            p.exp = exp;
            p.heldItem = heldItem;
            p.skin = skin;
            return p;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pet p && Objects.equals(type, p.type) && Objects.equals(rarity, p.rarity) && level == p.level
                && exp == p.exp && Objects.equals(heldItem, p.heldItem) && Objects.equals(skin, p.skin);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, rarity, level, exp, heldItem, skin);
        }
    }

    public Map<String, Entry> slots = new LinkedHashMap<>();
    public int selected;
    public List<Pet> pets = new ArrayList<>();
    public int activePet = -1;
    /** The selected power stone's power (SkyblockProfile.powers(), "silky"...); null: the class's usual one. */
    public String power;

    public Kit copy() {
        Kit out = new Kit();
        slots.forEach((k, v) -> out.slots.put(k, v.copy()));
        out.selected = selected;
        pets.forEach(p -> out.pets.add(p.copy()));
        out.activePet = activePet;
        out.power = power;
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Kit k && selected == k.selected && slots.equals(k.slots) && pets.equals(k.pets) && activePet == k.activePet
            && Objects.equals(power, k.power);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slots, selected, pets, activePet, power);
    }

    public static boolean isArmor(String key) {
        return ARMOR.contains(key);
    }

    public static boolean isEquipment(String key) {
        return EQUIPMENT.contains(key);
    }

    /** An armour or equipment slot: it takes only the piece made for it. */
    public static boolean isWorn(String key) {
        return isArmor(key) || isEquipment(key);
    }

    /** The key of inventory slot [slot]. */
    public static String key(int slot) {
        return Integer.toString(slot);
    }

    /** [json] as a kit, unchecked (see [sanitized]), or null when it isn't one. */
    public static Kit parse(String json) {
        try {
            Kit kit = GSON.fromJson(json, Kit.class);
            if (kit != null && kit.slots == null) {
                kit.slots = new LinkedHashMap<>();
            }
            if (kit != null && kit.pets == null) {
                kit.pets = new ArrayList<>();
            }
            return kit;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String toJson(boolean pretty) {
        return (pretty ? PRETTY : GSON).toJson(this);
    }

    /** The kit in [file], checked, or null when there is none or it can't be read. */
    public static Kit read(Path file, Function<String, String> kind, Consumer<String> warn) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Kit kit = parse(Files.readString(file));
            if (kit == null) {
                warn.accept("Couldn't read " + file + ": not a kit");
                return null;
            }
            return kit.sanitized(kind, warn);
        } catch (IOException | RuntimeException e) {
            warn.accept("Couldn't read " + file + ": " + e);
            return null;
        }
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(true));
    }

    /**
     * This kit with everything out of range fixed or dropped: unknown slots, ids [kind] doesn't know
     * (it answers null for those, the armour or equipment key a piece is worn in, or "" for anything
     * else), armour and equipment in the wrong slot, numbers past ItemStats' limits, and pets
     * PetDefs doesn't know (levels and rarities are fitted to the pet). Master stars need
     * all five stars; scrolls are only the Necron ones. [warn] hears what was dropped.
     */
    public Kit sanitized(Function<String, String> kind, Consumer<String> warn) {
        Kit out = new Kit();
        out.selected = clamp(selected, 0, 8);
        out.power = SkyblockProfile.powerId(power);
        if (power != null && out.power == null) {
            warn.accept("Unknown kit power " + power + "; using the class's usual one");
        }
        if (slots == null) {
            return out;
        }
        for (String key : KEYS) {
            Entry e = slots.get(key);
            if (e == null) {
                continue;
            }
            Entry checked = checked(e, key, kind, warn);
            if (checked != null) {
                out.slots.put(key, checked);
            }
        }
        for (String key : slots.keySet()) {
            if (!KEYS.contains(key)) {
                warn.accept("Kit slot " + key + " isn't a slot a kit can fill; skipped it");
            }
        }
        if (pets != null) {
            for (int i = 0; i < pets.size(); i++) {
                Pet p = pets.get(i);
                PetDefs.Type type = p == null ? null : PetDefs.Type.of(p.type);
                if (type == null) {
                    warn.accept("Unknown kit pet " + (p == null ? null : p.type) + "; skipped it");
                    continue;
                }
                if (out.pets.size() >= MAX_PETS) {
                    warn.accept("A kit holds at most " + MAX_PETS + " pets; skipped the rest");
                    break;
                }
                if (i == activePet) {
                    out.activePet = out.pets.size();
                }
                out.pets.add(new Pet(type.name(), PetDefs.fit(type, PetDefs.Rarity.of(p.rarity)).name(), PetDefs.clampLevel(type, p.level)));
            }
        }
        return out;
    }

    /** A reforge name as an item's name shows it ("Ancient", "Withered"). */
    private static final java.util.regex.Pattern REFORGE = java.util.regex.Pattern.compile("[A-Za-z' ]{1,24}");
    /** The most an item's bonus can add to (or take from) one stat. */
    private static final double MAX_BONUS = 5000;

    /**
     * [e] for slot [key] with everything out of range fixed, or null (and [warn] hears why) when
     * it has no id, [kind] doesn't know it, or it's worn in the wrong slot ([key] null: any slot,
     * as in storage).
     */
    public static Entry checked(Entry e, String key, Function<String, String> kind, Consumer<String> warn) {
        String where = key == null ? "" : " in slot " + key;
        if (e.id == null || e.id.isBlank() || e.id.length() > MAX_ID) {
            warn.accept("Kit item" + where + " has no item id; skipped it");
            return null;
        }
        String id = e.id.trim();
        String fits = kind.apply(id);
        if (fits == null) {
            warn.accept("Unknown kit item " + id + where + "; skipped it");
            return null;
        }
        if (key != null && isWorn(key) && !key.equals(fits)) {
            warn.accept("Kit item " + id + " can't be worn in " + key + "; skipped it");
            return null;
        }
        int stars = clamp(e.stars, 0, ItemStats.MAX_STARS);
        List<String> scrolls = new ArrayList<>();
        if (e.scrolls != null) {
            for (String s : e.scrolls) {
                if (ItemDefs.SCROLLS.contains(s) && !scrolls.contains(s)) {
                    scrolls.add(s);
                }
            }
        }
        Entry out = new Entry(id, stars,
            stars == ItemStats.MAX_STARS ? clamp(e.masterStars, 0, ItemStats.MAX_MASTER_STARS) : 0,
            clamp(e.tier, 1, ItemStats.MAX_TIER), clamp(e.quality, 0, ItemStats.MAX_QUALITY), scrolls);
        if (e.bonus != null) {
            Map<String, Double> bonus = new LinkedHashMap<>();
            e.bonus.forEach((stat, v) -> {
                if (ItemDefs.STAT_ORDER.contains(stat) && v != null && Double.isFinite(v) && v != 0) {
                    bonus.put(stat, Math.max(-MAX_BONUS, Math.min(MAX_BONUS, v)));
                }
            });
            out.bonus = bonus.isEmpty() ? null : bonus;
        }
        out.reforge = e.reforge != null && REFORGE.matcher(e.reforge).matches() ? e.reforge.trim() : null;
        out.recomb = e.recomb;
        // Mods from a guest stay within what Hypixel items can have: a known reforge, at most 15 books,
        // enchantment ids and levels 1-10, and "TYPE:QUALITY" gems.
        out.modifier = ItemMods.reforgeOf(e.modifier) != null ? e.modifier.toLowerCase(java.util.Locale.ROOT) : null;
        out.potatoBooks = clamp(e.potatoBooks, 0, 15);
        out.artOfPeace = e.artOfPeace;
        out.artOfWar = e.artOfWar;
        if (e.enchants != null) {
            Map<String, Integer> ench = new LinkedHashMap<>();
            e.enchants.forEach((k, v) -> {
                if (k != null && k.matches("[A-Za-z_]{1,32}") && v != null && ench.size() < 40) {
                    ench.put(k, clamp(v, 1, 10));
                }
            });
            out.enchants = ench.isEmpty() ? null : ench;
        }
        if (e.gems != null) {
            List<String> gems = new ArrayList<>();
            for (String g : e.gems) {
                if (g != null && g.matches("[A-Z]{1,16}:[A-Z]{1,16}") && gems.size() < 10) {
                    gems.add(g);
                }
            }
            out.gems = gems.isEmpty() ? null : gems;
        }
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
