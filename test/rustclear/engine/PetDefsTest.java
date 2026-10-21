package net.rustclear.mod.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PetDefsTest {
    @Test
    void statsRunFromLevelOneToMax() {
        var phoenix = PetDefs.stats(PetDefs.Type.PHOENIX, PetDefs.Rarity.VERY_SPECIAL, 100);
        assertEquals(60, phoenix.get(Stat.STRENGTH), 1e-9);
        assertEquals(150, phoenix.get(Stat.INTELLIGENCE), 1e-9);
        var low = PetDefs.stats(PetDefs.Type.ENDER_DRAGON, PetDefs.Rarity.LEGENDARY, 1);
        assertEquals(0.5, low.get(Stat.STRENGTH), 1e-9);
    }

    @Test
    void goldenDragonCountsItsPerks() {
        // Level 200: 50 Strength and Shining Scales' 9 digits x 11.1.
        var gdrag = PetDefs.bonus(PetDefs.Type.GOLDEN_DRAGON, PetDefs.Rarity.LEGENDARY, 200);
        assertEquals(50 + 99.9, gdrag.flat().get(Stat.STRENGTH), 1e-9);
        assertEquals(50, gdrag.flat().get(Stat.ATTACK_SPEED), 1e-9);
        assertEquals(0.05, gdrag.percent().get(Stat.STRENGTH), 1e-9);
    }

    @Test
    void onlyALegendaryEnderDragonIsSuperior() {
        assertEquals(0.1, PetDefs.bonus(PetDefs.Type.ENDER_DRAGON, PetDefs.Rarity.LEGENDARY, 100).percent().get(Stat.CRIT_DAMAGE), 1e-9);
        assertTrue(PetDefs.bonus(PetDefs.Type.ENDER_DRAGON, PetDefs.Rarity.EPIC, 100).percent().isEmpty());
    }

    @Test
    void petPercentsShowInTheStatSheet() {
        var s = StatBuilder.build(SkyblockProfile.fixed(50, 50), null, false, List.of(), List.of(), null,
            PetDefs.bonus(PetDefs.Type.ENDER_DRAGON, PetDefs.Rarity.LEGENDARY, 100), StatBuilder.Run.NONE, StatBuilder.Buffs.NONE);
        assertTrue(s.percents(Stat.STRENGTH).stream().anyMatch(p -> p.label().equals("Superior")));
        assertTrue(s.parts(Stat.STRENGTH).stream().anyMatch(p -> p.label().equals("Ender Dragon")));
    }

    @Test
    void kitPetsAreFittedOrDropped() {
        Kit kit = new Kit();
        kit.pets.add(new Kit.Pet("NOT_A_PET", "LEGENDARY", 100));
        kit.pets.add(new Kit.Pet("GOLDEN_DRAGON", "EPIC", 500));
        kit.activePet = 1;
        List<String> warnings = new ArrayList<>();
        Kit out = kit.sanitized(id -> "", warnings::add);
        assertEquals(1, out.pets.size());
        assertEquals("LEGENDARY", out.pets.get(0).rarity);
        assertEquals(200, out.pets.get(0).level);
        assertEquals(0, out.activePet);
        assertEquals(1, warnings.size());
    }

    @Test
    void equipmentSlotsTakeOnlyTheirOwnPiece() {
        Kit kit = new Kit();
        kit.slots.put("necklace", new Kit.Entry("A_BELT", 0, 0, 7, 0, List.of()));
        kit.slots.put("belt", new Kit.Entry("A_BELT", 0, 0, 7, 0, List.of()));
        Kit out = kit.sanitized(id -> "belt", w -> { });
        assertTrue(out.slots.containsKey("belt"));
        assertTrue(!out.slots.containsKey("necklace"));
    }
}
