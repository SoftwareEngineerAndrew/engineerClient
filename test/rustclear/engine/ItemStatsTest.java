package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ItemStats against the item list's precomputed stats (item_stat_fixtures.json: Catacombs 0/24/36/50
 * x 0-5 stars x 0-5 master stars in dungeons, and 0-5 stars outside), which are fixtures here, not tables
 * the mod reads.
 */
class ItemStatsTest {
    private static final Pattern KEY = Pattern.compile("cata(\\d+)_(\\d)star(?:_(\\d)master)?");
    private static final String[] CLASSES = {"Berserk", "Healer", "Mage", "Tank", "Archer"};

    @Test
    void cata27FiveStarsTwoMasterStarsAndAFourDigitMedallionIs324Percent() {
        // 255 + 50 + 10 + the flat 5 + 4.
        assertEquals(324.0, ItemStats.dungeonPercent("Damage", 27, 5, 2, true, 4), 1e-9);
    }

    @Test
    void cataBonusTable() {
        assertEquals(10, ItemStats.cataBonus(0));
        assertEquals(75, ItemStats.cataBonus(9));
        assertEquals(255, ItemStats.cataBonus(27));
        assertEquals(485, ItemStats.cataBonus(50));
        assertEquals(485, ItemStats.cataBonus(80));
    }

    @Test
    void cataBonusMatchesTheDataFile() {
        int[] table = ItemDefs.get().cataBonusTable();
        for (int level = 0; level <= ItemStats.MAX_CATA; level++) {
            assertEquals(table[level], ItemStats.cataBonus(level), "level " + level);
        }
    }

    @Test
    void masterStarsNeedFiveStarsAndMasterMode() {
        assertEquals(10 + 40 + 5, ItemStats.dungeonPercent("Damage", 0, 4, 5, true, 0), 1e-9);
        assertEquals(10 + 50 + 5, ItemStats.dungeonPercent("Damage", 0, 5, 5, false, 0), 1e-9);
        assertEquals(10 + 50 + 25 + 5, ItemStats.dungeonPercent("Damage", 0, 5, 5, true, 0), 1e-9);
    }

    @Test
    void skippedAndUnscaledStats() {
        assertEquals(55, ItemStats.dungeonPercent("Crit Chance", 50, 5, 0, true, 0), 1e-9);
        assertEquals(0, ItemStats.dungeonPercent("Shot Cooldown", 50, 5, 5, true, 0), 1e-9);
        assertEquals(0.5, ItemStats.outside("Shot Cooldown", 0.5, 5), 1e-9);
    }

    @Test
    void floorTiersAndQuality() {
        assertEquals(1, ItemStats.floorTier("E"));
        assertEquals(7, ItemStats.floorTier("F7"));
        assertEquals(4, ItemStats.floorTier("M1"));
        assertEquals(10, ItemStats.floorTier("M7"));
        double[] tiers = {33, 35, 37, 40, 42, 45, 48, 51, 55, 59};
        assertEquals(48 * 1.5, ItemStats.tieredBase(tiers, 7, 50), 1e-9);
        assertEquals(59 * 1.5, ItemStats.tieredBase(tiers, 10, 50), 1e-9);
        assertEquals(33, ItemStats.tieredBase(tiers, 1, 0), 1e-9);
    }

    @Test
    void everyPrecomputedStatMatches() throws Exception {
        JsonObject root;
        try (var in = ItemStatsTest.class.getResourceAsStream("/item_stat_fixtures.json")) {
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int checked = 0;
        for (JsonElement e : root.getAsJsonArray("items")) {
            JsonObject item = e.getAsJsonObject();
            String id = item.get("id").getAsString();
            JsonObject base = item.getAsJsonObject("stats");
            for (Map.Entry<String, JsonElement> row : item.getAsJsonObject("table").entrySet()) {
                Matcher m = KEY.matcher(row.getKey());
                assertEquals(true, m.matches(), row.getKey());
                int cata = Integer.parseInt(m.group(1));
                int stars = Integer.parseInt(m.group(2));
                int master = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                for (Map.Entry<String, JsonElement> stat : row.getValue().getAsJsonObject().entrySet()) {
                    if (!known(stat.getKey())) {
                        continue;
                    }
                    double value = base.get(stat.getKey()).getAsDouble();
                    double expected = stat.getValue().getAsDouble();
                    // The wiki's numbers leave out the flat +5% measured on Hypixel (stats.md §4).
                    double actual = isClass(stat.getKey()) ? value
                        : ItemStats.inDungeon(stat.getKey(), value, cata, stars, master, true, -ItemStats.FLAT_PERCENT);
                    assertEquals(expected, actual, 0.051, id + " " + row.getKey() + " " + stat.getKey());
                    checked++;
                }
            }
            for (Map.Entry<String, JsonElement> row : item.getAsJsonObject("outside").entrySet()) {
                int stars = Integer.parseInt(row.getKey().replaceAll("\\D", ""));
                for (Map.Entry<String, JsonElement> stat : row.getValue().getAsJsonObject().entrySet()) {
                    if (!known(stat.getKey())) {
                        continue;
                    }
                    double value = base.get(stat.getKey()).getAsDouble();
                    double actual = isClass(stat.getKey()) ? value : ItemStats.outside(stat.getKey(), value, stars);
                    assertEquals(stat.getValue().getAsDouble(), actual, 0.051, id + " " + row.getKey() + " " + stat.getKey());
                    checked++;
                }
            }
        }
        assertFalse(checked < 1000, "only " + checked + " values checked");
    }

    /**
     * The fixtures (wiki numbers) scale these with the cata bonus; Hypixel's own lore doesn't
     * (correctedFromLore), so they aren't compared.
     */
    private static final List<String> CORRECTED = List.of("Ferocity", "True Defense", "Mending", "Health Regen", "Vitality");

    private static boolean known(String stat) {
        return (ItemDefs.STAT_ORDER.contains(stat) && !CORRECTED.contains(stat)) || isClass(stat);
    }

    /** Grey dungeon numbers in recorded Hypixel lore (stats.md §4), Catacombs 50, five stars: x(1 + (485 + 50 + 5) / 100). */
    @Test
    void correctedFromLore() {
        // Strength 67 from a reforge -> (+428.8).
        assertEquals(428.8, ItemStats.inDungeon("Strength", 67, 50, 5, 0, false, 0), 1e-9);
        // Crit Chance skips the cata bonus: 15 -> 23.25.
        assertEquals(23.25, ItemStats.inDungeon("Crit Chance", 15, 50, 5, 0, false, 0), 1e-9);
        // Hyperion's Ferocity: 30 -> 46.5.
        assertEquals(46.5, ItemStats.inDungeon("Ferocity", 30, 50, 5, 0, false, 0), 1e-9);
        // Health Regen and Vitality only get the flat 5%: 15 -> 15.75, 3 -> 3.15; outside no stars.
        assertEquals(15.75, ItemStats.inDungeon("Health Regen", 15, 50, 5, 0, false, 0), 1e-9);
        assertEquals(3.15, ItemStats.inDungeon("Vitality", 3, 50, 5, 0, false, 0), 1e-9);
        assertEquals(15, ItemStats.outside("Health Regen", 15, 5), 1e-9);
    }

    private static boolean isClass(String stat) {
        for (String c : CLASSES) {
            if (c.equals(stat)) {
                return true;
            }
        }
        return false;
    }
}
