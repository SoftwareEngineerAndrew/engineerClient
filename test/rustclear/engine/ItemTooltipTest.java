package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Snapshot checks of ten items' tooltips against what NEU's lore says: names and stars, stat lines
 * with their Catacombs values, abilities with their click, costs and cooldowns, and the rarity line.
 */
class ItemTooltipTest {
    private static ItemDefs.Instance item(String id, int stars, int master, int tier, int quality, List<String> scrolls) {
        ItemDefs.Def def = ItemDefs.get().def(id);
        assertNotNull(def, id);
        return new ItemDefs.Instance(def, stars, master, tier, quality, scrolls);
    }

    private static List<String> lore(ItemDefs.Instance item) {
        return ItemDefs.lore(item, 50, true);
    }

    @Test
    void everyItemLoads() {
        // 391 packed + items_extra.json (the Ragnarock Axe).
        assertEquals(392, ItemDefs.get().all().size());
        for (ItemDefs.Def def : ItemDefs.get().all()) {
            assertNotNull(def.material, def.id);
            assertTrue(ItemDefs.RARITIES.contains(def.rarity), def.id);
            List<String> lines = lore(new ItemDefs.Instance(def, 5, 5, 7, 50, ItemDefs.SCROLLS));
            assertTrue(lines.get(lines.size() - 1).contains("§l"), def.id);
        }
    }

    @Test
    void hyperion() {
        ItemDefs.Instance hype = item("HYPERION", 5, 5, 7, 0, ItemDefs.SCROLLS);
        assertEquals("§6Hyperion §6✪✪✪✪✪§c➎", ItemDefs.displayName(hype));
        List<String> lines = lore(hype);
        // 260 x 1.10 outside; x (1 + (485 + 50 + 25) / 100) = 1,716 in the Catacombs.
        assertEquals("§7Damage: §c+1,729", lines.get(0));
        assertTrue(lines.contains("§6Ability: Wither Impact  §e§lRIGHT CLICK"));
        assertEquals("§6§lLEGENDARY DUNGEON SWORD", lines.get(lines.size() - 1));
    }

    @Test
    void hyperionWithoutScrolls() {
        List<String> lines = lore(item("HYPERION", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§7Right-click to use your class ability!"));
        assertEquals("§7Damage: §c+1,534", lines.get(0));
    }

    @Test
    void terminatorIsAShortbowAndGetsDungeonOnceStarred() {
        ItemDefs.Instance term = item("TERMINATOR", 5, 0, 7, 0, List.of());
        List<String> lines = lore(term);
        assertEquals("§7Shot Cooldown: §a0.5s", lines.get(0));
        assertTrue(lines.contains("§dShortbow: Instantly shoots!"));
        assertTrue(lines.contains("§6Ability: Salvation  §e§lLEFT CLICK"));
        assertTrue(lines.contains("§8Soulflow Cost: §31"));
        assertEquals("§6§lLEGENDARY DUNGEON BOW", lines.get(lines.size() - 1));
        assertEquals("§6§lLEGENDARY BOW", lore(item("TERMINATOR", 0, 0, 7, 0, List.of())).getLast());
    }

    @Test
    void bonzoStaff() {
        List<String> lines = lore(item("BONZO_STAFF", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§6Ability: Showtime  §e§lRIGHT CLICK"));
        assertTrue(lines.contains("§7Shoots balloons that create a large explosion"));
        assertTrue(lines.contains("§8Mana Cost: §390"));
        assertEquals("§9§lRARE DUNGEON SWORD", lines.getLast());
    }

    @Test
    void giantsSwordCooldown() {
        List<String> lines = lore(item("GIANTS_SWORD", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§6Ability: Giant's Slam  §e§lRIGHT CLICK"));
        assertTrue(lines.contains("§8Cooldown: §a30s"));
    }

    @Test
    void mobDropUsesItsFloorTierAndQuality() {
        // Skeleton Grunt Helmet: Defense 48 on tier 7, x1.5 at 50/50, and a perfect drop is one rarity up.
        ItemDefs.Instance helmet = item("SKELETON_GRUNT_HELMET", 0, 0, 7, 50, List.of());
        List<String> lines = lore(helmet);
        // Inside the dungeon the main number is the Catacombs value (72 x 5.9), and Hypixel shows no quality or tier lines.
        assertTrue(lines.contains("§7Defense: §a+424.8"), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("Item Quality") || l.contains("Floor Tier")));
        assertEquals("§6§lLEGENDARY DUNGEON HELMET", lines.getLast());
        assertEquals("§5§lEPIC DUNGEON HELMET", lore(item("SKELETON_GRUNT_HELMET", 0, 0, 10, 0, List.of())).getLast());
    }

    @Test
    void adaptiveSetBonusAndClassLines() {
        List<String> lines = lore(item("ADAPTIVE_CHESTPLATE", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§7Berserk: §a+20"));
        assertTrue(lines.contains("§6Full Set Bonus: Efficient training (0/4)"));
    }

    @Test
    void spiritLeapIsAnItem() {
        List<String> lines = lore(item("SPIRIT_LEAP", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§6Ability: Spirit Leap  §e§lRIGHT CLICK"));
        assertTrue(lines.contains("§8Cooldown: §a5s"));
    }

    @Test
    void iceSprayWand() {
        List<String> lines = lore(item("ICE_SPRAY_WAND", 0, 0, 7, 0, List.of()));
        assertTrue(lines.contains("§6Ability: Ice Spray  §e§lRIGHT CLICK"));
        assertTrue(lines.contains("§8Mana Cost: §350"));
        assertTrue(lines.contains("§8Cooldown: §a5s"));
    }

    @Test
    void necronsChestplate() {
        ItemDefs.Instance chest = item("POWER_WITHER_CHESTPLATE", 5, 0, 7, 0, List.of());
        assertEquals("§6Necron's Chestplate §6✪✪✪✪✪", ItemDefs.displayName(chest));
        assertTrue(lore(chest).getLast().startsWith("§6§lLEGENDARY DUNGEON CHESTPLATE"));
    }
}
