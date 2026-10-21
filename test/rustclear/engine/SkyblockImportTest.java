package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** A Hypixel profile's inventory, as the lobby passes it on, becomes a kit and storage. */
class SkyblockImportTest {
    private static String kind(String id) {
        if (id.equals("ASPECT_OF_THE_VOID")) {
            return "";
        }
        ItemDefs.Def def = ItemDefs.get().def(id);
        if (def == null) {
            return null;
        }
        String armor = KitItems.armorKey(def.type);
        return armor.isEmpty() ? KitItems.equipmentKey(def.type) : armor;
    }

    // ---------------------------------------------------------------- writing NBT as Hypixel does

    private static void write(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.writeByte(type(e.getValue()));
                out.writeUTF((String) e.getKey());
                write(out, e.getValue());
            }
            out.writeByte(0);
        } else if (v instanceof List<?> l) {
            out.writeByte(l.isEmpty() ? 0 : type(l.get(0)));
            out.writeInt(l.size());
            for (Object o : l) {
                write(out, o);
            }
        } else if (v instanceof String s) {
            out.writeUTF(s);
        } else if (v instanceof Integer i) {
            out.writeInt(i);
        } else if (v instanceof Short s) {
            out.writeShort(s);
        }
    }

    private static int type(Object v) {
        return v instanceof Map ? 10 : v instanceof List ? 9 : v instanceof String ? 8 : v instanceof Integer ? 3 : 2;
    }

    /** A gzipped, base64 {i: [items]} as Hypixel stores an inventory (an empty map: an empty slot). */
    private static String blob(List<Map<String, Object>> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            write(out, Map.of("i", items));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static Map<String, Object> item(String id, String name, List<String> lore, Map<String, Object> extra) {
        Map<String, Object> attributes = new LinkedHashMap<>(extra);
        attributes.put("id", id);
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("Name", name);
        display.put("Lore", lore);
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("display", display);
        tag.put("ExtraAttributes", attributes);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", (short) 1);
        item.put("tag", tag);
        return item;
    }

    private static List<Map<String, Object>> slots(int size, Map<Integer, Map<String, Object>> filled) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            out.add(filled.getOrDefault(i, Map.of()));
        }
        return out;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void readsKitStorageAndPets() throws IOException {
        // Real lore (Oct 2026): 40 x 1.1 + 35 reforge + 24 gems = 103 Strength; 15 + 75 Growth + 7 + 60 potato books on Health.
        Map<String, Object> chest = item("POWER_WITHER_CHESTPLATE", "§dAncient Necron's Chestplate §6✪✪✪✪✪",
            List.of("§7Gear Score: §d1111 §8(5000)", "§7Health: §c+428 §e(+60) §9(+7) §8(+2,552.7)",
                "§7Strength: §c+103 §9(+35) §d(+24) §8(+628.65)", "§7Crit Chance: §9+15% §9(+15%) §8(+22.5%)"),
            Map.of("upgrade_level", 5, "modifier", "ancient", "rarity_upgrades", 1));
        // Hyperion's lore counts its +1 Damage and +2 Intelligence a Catacombs level (50 here).
        Map<String, Object> hyperion = item("HYPERION", "§dHeroic Hyperion §6✪✪✪✪✪§c➎",
            List.of("§7Damage: §c+366 §e(+30) §8(+2,159)", "§7Intelligence: §b+670 §9(+125) §d(+60) §8(+4,032.25)"),
            Map.of("upgrade_level", 10, "ability_scroll", List.of("IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL")));
        Map<String, Object> dirt = item("ENCHANTED_DIRT", "§aEnchanted Dirt", List.of(), Map.of());
        Map<String, Object> menu = item("SKYBLOCK_MENU", "§aSkyBlock Menu", List.of(), Map.of());
        Map<String, Object> aotv = item("ASPECT_OF_THE_VOID", "§6Aspect of the Void", List.of(), Map.of());

        JsonObject inv = new JsonObject();
        inv.addProperty("inv", blob(slots(36, Map.of(0, hyperion, 1, aotv, 2, dirt, 8, menu))));
        inv.addProperty("armor", blob(slots(4, Map.of(2, chest))));
        inv.addProperty("equipment", blob(slots(4, Map.of())));
        inv.addProperty("enderChest", blob(slots(90, Map.of(3, aotv, 50, hyperion))));
        JsonObject packs = new JsonObject();
        packs.addProperty("10", blob(slots(27, Map.of(0, dirt))));
        packs.addProperty("2", blob(slots(9, Map.of(4, aotv))));
        inv.add("backpacks", packs);
        JsonObject body = new JsonObject();
        body.add("inventory", inv);
        JsonArray pets = new JsonArray();
        pets.add(pet("ENDER_DRAGON", "EPIC", 90, false));
        pets.add(pet("GOLDEN_DRAGON", "LEGENDARY", 200, true));
        pets.add(pet("PARROT", "LEGENDARY", 100, false));
        body.add("pets", pets);

        SkyblockImport.Result r = SkyblockImport.read(body, SkyblockImportTest::kind, 50);
        Kit kit = r.kit();
        assertNotNull(kit);
        assertEquals("HYPERION", kit.slots.get("0").id);
        assertEquals(5, kit.slots.get("0").stars);
        assertEquals(5, kit.slots.get("0").masterStars);
        assertEquals(3, kit.slots.get("0").scrolls.size());
        // 366 - 260 x 1.1 - 50 (per level, which StatBuilder adds) = 30; 670 - 385 - 100 = 185.
        assertEquals(30, kit.slots.get("0").bonus.get("Damage"), 1e-9);
        assertEquals(185, kit.slots.get("0").bonus.get("Intelligence"), 1e-9);
        assertEquals("Heroic", kit.slots.get("0").reforge);
        assertEquals("ASPECT_OF_THE_VOID", kit.slots.get("1").id);
        assertFalse(kit.slots.containsKey("2"), "Rustclear has no Enchanted Dirt");
        assertFalse(kit.slots.containsKey("8"), "the 9th slot stays the map's");
        Kit.Entry worn = kit.slots.get("chest");
        assertEquals("POWER_WITHER_CHESTPLATE", worn.id);
        assertEquals("Ancient", worn.reforge);
        assertTrue(worn.recomb);
        // The lore's 59 Strength and 15 Crit Chance on top of the base: Ancient at MYTHIC is 35 and 15 (items-stats.md),
        // so what's left over is 24 Strength and no Crit Chance.
        assertEquals("ancient", worn.modifier);
        assertEquals(24, worn.bonus.get("Strength"), 1e-9);
        assertFalse(worn.bonus.containsKey("Crit Chance"));
        assertEquals(Map.of("Enchanted Dirt", 2), r.missing());

        // In a Catacombs 50 dungeon the chestplate's Strength is Hypixel's grey number.
        ItemDefs.Instance instance = new ItemDefs.Instance(ItemDefs.get().def(worn.id), worn.stars, worn.masterStars, worn.tier,
            worn.quality, worn.scrolls, worn.bonus, worn.reforge, worn.recomb, worn.mods());
        assertEquals(633.6, instance.inDungeon("Strength", 50, false), 1e-6);
        assertEquals(23.25, instance.inDungeon("Crit Chance", 50, false), 1e-6);
        assertTrue(ItemDefs.displayName(instance).contains("Ancient Necron's Chestplate"));

        assertEquals(List.of("Ender Chest Page 1", "Ender Chest Page 2", "Backpack Slot 3", "Backpack Slot 11"),
            r.storage().stream().map(p -> p.title).toList());
        assertEquals("ASPECT_OF_THE_VOID", r.storage().get(0).slots.get("3").id);
        assertEquals("HYPERION", r.storage().get(1).slots.get("5").id);
        assertEquals(9, r.storage().get(2).size);

        assertEquals(2, kit.pets.size());
        assertEquals("GOLDEN_DRAGON", kit.pets.get(0).type);
        assertEquals(0, kit.activePet);
        assertEquals(200, kit.pets.get(0).level);

        // What the host keeps after checking it is the same kit.
        assertEquals(kit, kit.sanitized(SkyblockImportTest::kind, w -> { }));
    }

    @Test
    void inventoryApiOff() {
        JsonObject body = new JsonObject();
        body.add("pets", new JsonArray());
        SkyblockImport.Result r = SkyblockImport.read(body, SkyblockImportTest::kind, 50);
        assertNull(r.kit());
        assertTrue(r.storage().isEmpty());
    }

    @Test
    void reforgeAfterIcon() {
        assertEquals("Heroic", SkyblockImport.reforge(" Heroic Bonzo's Staff ✪✪✪✪✪", " Bonzo's Staff"));
        assertNull(SkyblockImport.reforge("Bonzo's Staff", "Bonzo's Staff"));
    }

    private static JsonObject pet(String type, String tier, int level, boolean active) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("tier", tier);
        p.addProperty("level", level);
        p.addProperty("active", active);
        return p;
    }
}
