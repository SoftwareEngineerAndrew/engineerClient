package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** kit.json: what is saved reads back the same, and anything out of range is fixed or dropped. */
class KitTest {
    /** KitItems.kind without Minecraft: the item list's ids only. */
    private static String kind(String id) {
        ItemDefs.Def def = ItemDefs.get().def(id);
        if (def == null) {
            return null;
        }
        return switch (def.type) {
            case "HELMET" -> "head";
            case "CHESTPLATE" -> "chest";
            case "LEGGINGS" -> "legs";
            case "BOOTS" -> "feet";
            default -> "";
        };
    }

    private static Kit check(Kit kit, List<String> warnings) {
        return kit.sanitized(KitTest::kind, warnings::add);
    }

    @Test
    void roundTrips(@TempDir Path dir) throws Exception {
        Kit kit = new Kit();
        kit.slots.put("0", new Kit.Entry("HYPERION", 5, 5, 10, 50, ItemDefs.SCROLLS));
        kit.slots.put("6", new Kit.Entry("TERMINATOR", 5, 3, 10, 50, List.of()));
        kit.slots.put("chest", new Kit.Entry("WITHER_CHESTPLATE", 5, 5, 10, 50, List.of()));
        kit.slots.put("20", new Kit.Entry("SUPERBOOM_TNT", 0, 0, 7, 12, List.of()));
        kit.selected = 6;

        List<String> warnings = new ArrayList<>();
        Kit checked = check(kit, warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(kit, checked);

        Path file = dir.resolve("rustclear").resolve("kit.json");
        checked.write(file);
        Kit read = Kit.read(file, KitTest::kind, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(checked, read);
        assertEquals(checked, Kit.parse(checked.toJson(false)));

        String json = Files.readString(file);
        assertTrue(json.contains("\"slots\""));
        assertTrue(json.contains("\"chest\""));
        assertTrue(json.contains("\"masterStars\": 5"));
        assertTrue(json.contains("\"selected\": 6"));
    }

    @Test
    void clampsOutOfRange() {
        Kit kit = new Kit();
        kit.slots.put("0", new Kit.Entry("HYPERION", 9, 7, 99, 80, List.of("IMPLOSION_SCROLL", "FAKE_SCROLL", "IMPLOSION_SCROLL")));
        kit.slots.put("1", new Kit.Entry("TERMINATOR", 3, 5, 0, -5, List.of()));
        kit.slots.put("2", new Kit.Entry("TERMINATOR", -2, -1, -3, 1000, null));
        kit.selected = 12;
        Kit out = check(kit, new ArrayList<>());

        Kit.Entry hype = out.slots.get("0");
        assertEquals(ItemStats.MAX_STARS, hype.stars);
        assertEquals(ItemStats.MAX_MASTER_STARS, hype.masterStars);
        assertEquals(ItemStats.MAX_TIER, hype.tier);
        assertEquals(ItemStats.MAX_QUALITY, hype.quality);
        assertEquals(List.of("IMPLOSION_SCROLL"), hype.scrolls);

        Kit.Entry term = out.slots.get("1");
        assertEquals(3, term.stars);
        assertEquals(0, term.masterStars, "master stars need all five stars");
        assertEquals(1, term.tier);
        assertEquals(0, term.quality);

        Kit.Entry low = out.slots.get("2");
        assertEquals(0, low.stars);
        assertEquals(0, low.masterStars);
        assertEquals(1, low.tier);
        assertEquals(ItemStats.MAX_QUALITY, low.quality);
        assertTrue(low.scrolls.isEmpty());

        assertEquals(8, out.selected);
        kit.selected = -4;
        assertEquals(0, check(kit, new ArrayList<>()).selected);
    }

    @Test
    void dropsWhatDoesNotBelong() {
        Kit kit = new Kit();
        kit.slots.put("8", new Kit.Entry("HYPERION", 5, 5, 10, 50, List.of()));
        kit.slots.put("36", new Kit.Entry("HYPERION", 5, 5, 10, 50, List.of()));
        kit.slots.put("offhand", new Kit.Entry("HYPERION", 5, 5, 10, 50, List.of()));
        kit.slots.put("3", new Kit.Entry("NOT_A_REAL_ITEM", 5, 5, 10, 50, List.of()));
        kit.slots.put("4", new Kit.Entry(null, 0, 0, 1, 0, List.of()));
        kit.slots.put("5", new Kit.Entry("X".repeat(200), 0, 0, 1, 0, List.of()));
        kit.slots.put("head", new Kit.Entry("WITHER_CHESTPLATE", 5, 5, 10, 50, List.of()));
        kit.slots.put("feet", new Kit.Entry("HYPERION", 5, 5, 10, 50, List.of()));
        kit.slots.put("chest", new Kit.Entry("WITHER_CHESTPLATE", 5, 5, 10, 50, List.of()));
        kit.slots.put("7", new Kit.Entry("wither_chestplate", 0, 0, 1, 0, List.of()));
        List<String> warnings = new ArrayList<>();
        Kit out = check(kit, warnings);

        assertEquals(List.of("chest", "7"), new ArrayList<>(out.slots.keySet()));
        assertEquals(8, warnings.size(), warnings.toString());
    }

    @Test
    void neverCrashesOnABadFile(@TempDir Path dir) throws Exception {
        List<String> warnings = new ArrayList<>();
        assertNull(Kit.read(dir.resolve("missing.json"), KitTest::kind, warnings::add));
        assertTrue(warnings.isEmpty());

        for (String bad : List.of("", "not json", "[1, 2]", "{\"slots\": 5}", "{\"slots\": {\"0\": \"HYPERION\"}}", "{\"selected\": \"x\"}")) {
            Path file = dir.resolve("kit.json");
            Files.writeString(file, bad);
            Kit kit = Kit.read(file, KitTest::kind, warnings::add);
            if (kit != null) {
                assertTrue(kit.slots.isEmpty(), bad);
            }
        }
        assertFalse(warnings.isEmpty());

        // Missing fields read as a dungeon's best tier and quality, and no stars.
        Kit sparse = Kit.parse("{\"slots\": {\"0\": {\"id\": \"HYPERION\"}, \"chest\": null}}");
        assertNotNull(sparse);
        Kit.Entry e = sparse.sanitized(KitTest::kind, warnings::add).slots.get("0");
        assertEquals(0, e.stars);
        assertEquals(ItemStats.MAX_TIER, e.tier);
        assertEquals(ItemStats.MAX_QUALITY, e.quality);
    }

    @Test
    void keepsAKnownPower() {
        Kit kit = new Kit();
        kit.power = "Bizarre";
        assertEquals("bizarre", check(kit, new ArrayList<>()).power);
        Kit back = Kit.parse(check(kit, new ArrayList<>()).toJson(false));
        assertEquals("bizarre", back.power);

        kit.power = "not_a_power";
        List<String> warnings = new ArrayList<>();
        assertNull(check(kit, warnings).power);
        assertEquals(1, warnings.size());
        kit.power = null;
        assertFalse(kit.equals(Kit.parse("{\"power\":\"silky\"}")));
    }
}
