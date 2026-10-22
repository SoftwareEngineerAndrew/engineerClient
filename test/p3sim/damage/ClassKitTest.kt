package com.engineerclient.p3sim.damage

import com.engineerclient.p3sim.clear.damage.ClassKit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The class Ability and Ultimate rules against the recorded lines and cooldowns. */
class ClassKitTest {
    @Test fun everyClassHasItsRecordedKit() {
        assertEquals("Guided Sheep", assertNotNull(ClassKit.of("MAGE")).ability)
        assertEquals("Ragnarok", assertNotNull(ClassKit.of("Berserker")).ultimate)
        assertEquals("Wish", assertNotNull(ClassKit.of("healer")).ultimate)
        assertNull(ClassKit.of(null)); assertNull(ClassKit.of("Necromancer"))
        // Measured: Castle of Stone 150 s, Ragnarok 60 s, Rapid Fire 100 s, Explosive Shot 22.3 s.
        assertEquals(3000, ClassKit.of("Tank")!!.ultimateCd)
        assertEquals(1200, ClassKit.of("Berserk")!!.ultimateCd)
        assertEquals(2000, ClassKit.of("Archer")!!.ultimateCd)
        assertEquals(446, ClassKit.of("Archer")!!.abilityCd)
        assertEquals(2600, ClassKit.ultimateCd(ClassKit.of("Mage")!!, doubled = true))
        assertEquals(5100, ClassKit.ultimateCd(ClassKit.of("Mage")!!, doubled = false))
        // Guided Sheep: 7 s for the only Mage, 15 s with another.
        assertEquals(140, ClassKit.abilityCd(ClassKit.of("Mage")!!, doubled = true))
        assertEquals(300, ClassKit.abilityCd(ClassKit.of("Mage")!!, doubled = false))
        assertEquals(446, ClassKit.abilityCd(ClassKit.of("Archer")!!, doubled = true))
    }

    @Test fun linesAreHypixels() {
        assertEquals("§6Thunderstorm§r§a is ready to use! Press §r§6§lDROP§r§a to activate it!", ClassKit.ultimateReady("Thunderstorm"))
        assertEquals("§aUsed §r§6Explosive Shot§r§a!", ClassKit.used("Explosive Shot"))
        assertEquals("§6Guided Sheep §r§ais now available!", ClassKit.abilityAvailable("Guided Sheep"))
        assertEquals("§aYour Berserk §r§e§lULTIMATE§r§a §r§6Ragnarok §r§ais now available!", ClassKit.ultimateRecharged("Berserk", "Ragnarok"))
        assertEquals("§cYour Ultimate is currently on cooldown for 100 more seconds.", ClassKit.ultimateCooling(2000))
        assertEquals("§cYour Regular Ability is currently on cooldown for 1 more seconds.", ClassKit.abilityCooling(3))
        assertEquals("§7Your Thunderstorm hit §r§c13 §r§7enemies for §r§c1,349,128.3 §r§7damage.", ClassKit.hitLine("Thunderstorm", 13, 1349128.3))
        assertEquals("§7Your Seismic Wave hit §r§c1 §r§7enemy for §r§c104,409,564.7 §r§7damage.", ClassKit.hitLine("Seismic Wave", 1, 104409564.7))
    }
}
