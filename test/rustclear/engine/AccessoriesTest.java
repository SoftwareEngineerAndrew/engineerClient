package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The max accessory build and the power maths against accessories.md and the recordings. */
class AccessoriesTest {
    @Test
    void maxBuildMatchesTheResearch() {
        assertEquals(2176, Accessories.maxPower());
        assertEquals(150, Accessories.maxDungeonPower());
        assertEquals(SkyblockProfile.DEFAULT_ACCESSORY_POWER, Accessories.maxPower());
        assertEquals(SkyblockProfile.DEFAULT_DUNGEON_ACCESSORY_POWER, Accessories.maxDungeonPower());
        assertEquals(152, Accessories.maxBuild().size());
        assertEquals(2176, Accessories.maxBuild().stream().mapToInt(Accessories.Member::power).sum());
        int dungeon = Accessories.maxBuild().stream().filter(m -> m.def().dungeon()).mapToInt(Accessories.Member::power).sum();
        assertEquals(150, dungeon);
        assertTrue(Accessories.all().size() >= 400);
    }

    @Test
    void powersAtRecordedAndMaxPower() {
        SkyblockProfile p = new SkyblockProfile();
        p.power = "silky";
        // Max build inside a run: 2,326 -> +1,282.57 Crit Damage, +33.75 Speed (accessories.md §3.2).
        Map<Stat, Double> silky = p.powerStats(DungeonClass.BERSERK, true);
        assertEquals(1282.57, silky.get(Stat.CRIT_DAMAGE), 0.01);
        assertEquals(33.75, silky.get(Stat.SPEED), 0.01);
        // Bizarre at 1,597 (2289aa n141865): +1,930.18 Int, -107.23 Strength and Crit Damage.
        p.power = "bizarre";
        p.accessoryPower = 1597;
        Map<Stat, Double> bizarre = p.powerStats(DungeonClass.MAGE, false);
        assertEquals(1930.18, bizarre.get(Stat.INTELLIGENCE), 0.01);
        assertEquals(-107.23, bizarre.get(Stat.STRENGTH), 0.01);
        assertEquals(-107.23, bizarre.get(Stat.CRIT_DAMAGE), 0.01);
        assertEquals(5.0, SkyblockProfile.uniqueBonus("bizarre").get(Stat.ABILITY_DAMAGE));
    }

    @Test
    void damageTakenByMobType() {
        assertEquals(Set.of("Wither", "Skeletal", "Undead"), Accessories.mobTypes("Wither Skeleton"));
        assertEquals(Set.of("Undead"), Accessories.mobTypes("Zombie Soldier"));
        assertEquals(Set.of(), Accessories.mobTypes("Shadow Assassin"));
        StatSheet s = new StatSheet();
        Accessories.addMax(s, new SkyblockProfile(), new StatBuilder.Run(true, false, Map.of()));
        assertEquals(0.75 * 0.85 * 0.95, s.takenFrom("Wither Skeleton"), 1e-9);
        assertEquals(0.75 * 0.85, s.takenFrom("Maxor"), 1e-9);
        assertEquals(1.0, s.takenFrom("Shadow Assassin"), 1e-9);
    }
}
