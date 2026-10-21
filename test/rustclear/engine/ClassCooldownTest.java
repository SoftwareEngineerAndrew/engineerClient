package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** ClassAbilities.cooldownTicks against "Used X!" -> "X is now available!" on Hypixel (abilities-items.md §2). */
class ClassCooldownTest {
    @Test
    void measuredCooldowns() {
        // Mage at 49% / 74% (solo) cooldown reduction: Guided Sheep 300 / 140, Thunderstorm 255 / 130 s.
        assertEquals(300, ClassAbilities.cooldownTicks(DungeonClass.MAGE, false, 50, 0.49));
        assertEquals(140, ClassAbilities.cooldownTicks(DungeonClass.MAGE, false, 50, 0.74));
        assertEquals(255 * 20, ClassAbilities.cooldownTicks(DungeonClass.MAGE, true, 50, 0.49));
        assertEquals(130 * 20, ClassAbilities.cooldownTicks(DungeonClass.MAGE, true, 50, 0.74));
        // Archer 47: Explosive Shot 440, Rapid Fire 100 s.
        assertEquals(440, ClassAbilities.cooldownTicks(DungeonClass.ARCHER, false, 47, 0));
        assertEquals(2000, ClassAbilities.cooldownTicks(DungeonClass.ARCHER, true, 47, 0));
        // Berserk: Throwing Axe 200, Ragnarok 1200. Tank: Seismic Wave 300, Castle of Stone 150 s.
        assertEquals(200, ClassAbilities.cooldownTicks(DungeonClass.BERSERK, false, 48, 0));
        assertEquals(1200, ClassAbilities.cooldownTicks(DungeonClass.BERSERK, true, 48, 0));
        assertEquals(300, ClassAbilities.cooldownTicks(DungeonClass.TANK, false, 40, 0));
        assertEquals(3000, ClassAbilities.cooldownTicks(DungeonClass.TANK, true, 40, 0));
    }
}
