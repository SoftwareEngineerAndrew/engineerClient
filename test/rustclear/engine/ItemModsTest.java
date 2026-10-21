package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ItemMods against the item lore measured on Hypixel (items-stats.md). */
class ItemModsTest {
    private static ItemDefs.Instance item(String id, int level, boolean recomb, ItemMods mods) {
        ItemDefs.Def def = ItemDefs.get().def(id);
        return new ItemDefs.Instance(def, Math.min(level, 5), Math.max(0, level - 5), ItemStats.MAX_TIER, ItemStats.MAX_QUALITY,
            List.of(), null, null, recomb, mods);
    }

    @Test
    void reforgesByFinalRarity() {
        // Ancient at MYTHIC (a recombobulated LEGENDARY): 35 Strength, 15 Crit Chance, 50 Crit Damage.
        ItemMods ancient = new ItemMods("ancient", 0, false, false, Map.of(), List.of());
        assertEquals(35, ancient.reforgeStat("Strength", "MYTHIC"), 1e-9);
        assertEquals(15, ancient.reforgeStat("Crit Chance", "MYTHIC"), 1e-9);
        // SPECIAL reads MYTHIC's row.
        assertEquals(35, ancient.reforgeStat("Strength", "SPECIAL"), 1e-9);
        assertEquals(25, ancient.reforgeStat("Strength", "LEGENDARY"), 1e-9);
    }

    @Test
    void booksArtAndGems() {
        ItemMods m = new ItemMods(null, 15, true, true, Map.of(), List.of("SAPPHIRE:PERFECT", "SAPPHIRE:PERFECT"));
        assertEquals(60, m.bookStat("Health", true, false), 1e-9);
        assertEquals(30, m.bookStat("Defense", true, false), 1e-9);
        assertEquals(30, m.bookStat("Strength", false, true), 1e-9);
        assertEquals(40, m.peaceStat("Health", true), 1e-9);
        assertEquals(5, m.warStat("Strength", true), 1e-9);
        // Perfect Sapphire: 30 Intelligence each at MYTHIC.
        assertEquals(60, m.gemStat("Intelligence", "MYTHIC"), 1e-9);
    }

    @Test
    void statEnchantments() {
        ItemMods m = new ItemMods(null, 0, false, false, Map.of("growth", 5, "protection", 6, "critical", 7, "hecatomb", 10),
            List.of());
        assertEquals(75 + 8 * 5, m.enchantStat("Health", 50), 1e-9);
        assertEquals(25, m.enchantStat("Defense", 50), 1e-9);
        assertEquals(100, m.enchantStat("Crit Damage", 50), 1e-9);
        assertEquals(0.5, new ItemMods(null, 0, false, false, Map.of("ultimate_wise", 5), List.of()).manaMultiplier(), 1e-9);
    }

    @Test
    void stormsChestplateInTheDungeon() {
        // Ancient, recombobulated, 15 books, Growth V / Protection V, 10 stars, Catacombs 50, F7:
        // Health = (260 + 7 + 60 + 75) x 6.4.
        ItemDefs.Instance chest = item("WISE_WITHER_CHESTPLATE", 10, true,
            new ItemMods("ancient", 15, false, false, Map.of("growth", 5, "protection", 5), List.of()));
        assertEquals("MYTHIC", chest.rarity());
        assertEquals((260 + 7 + 60 + 75) * 6.4, chest.inDungeon("Health", 50, false), 1e-6);
        List<String> lore = ItemDefs.lore(chest, 50, false);
        assertTrue(lore.contains("§7Health: §c+" + ItemDefs.number((260 + 7 + 60 + 75) * 6.4) + " §e(+60) §9(+7)"), lore.toString());
        assertTrue(lore.contains("§9Growth V, Protection V"), lore.toString());
    }

    @Test
    void enchantBlockPutsTheUltimateFirst() {
        ItemMods m = new ItemMods(null, 0, false, false,
            Map.of("ultimate_wisdom", 5, "growth", 5, "protection", 5, "reflection", 5, "respite", 5, "telekinesis", 1), List.of());
        assertEquals(List.of("§d§lWisdom V, §9Growth V, Protection V", "§9Reflection V, Respite V"), m.enchantLines());
    }
}
