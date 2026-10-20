package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.Blessing
import com.engineerclient.p3sim.clear.secrets.Blessings
import com.engineerclient.p3sim.clear.secrets.Drop
import com.engineerclient.p3sim.clear.secrets.ESSENCE_TYPES
import com.engineerclient.p3sim.clear.secrets.LootItem
import com.engineerclient.p3sim.clear.secrets.LootTables
import com.engineerclient.p3sim.clear.secrets.SecretLines
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LootBlessingTest {
    @Test fun chestsHoldWhatTheRecordingsShow() {
        // 649 first opens: nothing 44.1 percent, a blessing 42.1, x10 essence 13.6, an item line 1.4 (here: 46 / 40 / 13 / 1 by the table).
        val r = Random(7)
        val n = 60000
        var nothing = 0; var essence = 0; var blessing = 0; var item = 0
        val types = HashMap<String, Int>()
        repeat(n) {
            when (val d = LootTables.chest(r)) {
                is Drop.Item -> { item++; assertTrue(d.item == LootItem.SUPERBOOM || d.item == LootItem.REVIVE_STONE) }
                is Drop.Essence -> { essence++; assertTrue(d.type in ESSENCE_TYPES); assertEquals(10, d.amount); types.merge(d.type, 1, Int::plus) }
                is Drop.Blessing -> blessing++
                Drop.Nothing -> nothing++
            }
        }
        fun share(c: Int) = c.toDouble() / n
        assertEquals(0.46, share(nothing), 0.01)
        assertEquals(0.40, share(blessing), 0.01)
        assertEquals(0.13, share(essence), 0.01)
        assertEquals(0.01, share(item), 0.004)
        assertEquals(setOf("Ice", "Gold", "Spider", "Diamond"), types.keys)
        assertTrue(types.values.all { it > essence / 4 * 0.85 }, "every essence type about equally often: $types")
    }

    @Test fun starredKillsDropWhatARunRecords() {
        // About 170 starred kills a run: 4.7 blessings, and with the room clears about 4.1 Superboom TNT and 1.05 Revive Stones.
        val r = Random(11)
        val runs = 2000
        var bless = 0; var boom = 0; var revive = 0
        val types = HashMap<Blessing, Int>()
        repeat(runs * 170) {
            when (val d = LootTables.mobKill(r)) {
                is Drop.Blessing -> { bless++; types.merge(d.blessing, 1, Int::plus) }
                is Drop.Item -> if (d.item == LootItem.SUPERBOOM) boom++ else revive++
                else -> {}
            }
        }
        assertEquals(4.7, bless.toDouble() / runs, 0.25)
        assertEquals(3.2, boom.toDouble() / runs, 0.25)
        assertEquals(0.85, revive.toDouble() / runs, 0.1)
        assertEquals(0.34, types.getValue(Blessing.POWER).toDouble() / bless, 0.03)
        assertEquals(0.13, types.getValue(Blessing.STONE).toDouble() / bless, 0.03)
        assertTrue(Blessing.TIME !in types, "a mob never drops Time")
    }

    @Test fun aDroppedBlessingReadsLikeHypixels() {
        assertEquals("A Blessing of Wisdom was picked up!", Blessings.pickedUp(Blessing.WISDOM).replace(Regex("§."), ""))
        assertEquals("DUNGEON BUFF! A Blessing of Wisdom V was found! (13s)", Blessings.announceOwn(Blessing.WISDOM, 5, 277).replace(Regex("§."), ""))
        assertEquals("DUNGEON BUFF! You found a Blessing of Life V! (26s)", Blessings.announceYouFound(Blessing.LIFE, 5, 534).replace(Regex("§."), ""))
        assertEquals("[MVP+] p3wr has obtained Blessing of Life!", Blessings.obtained("§b[MVP§r§c+§r§b] p3wr", Blessing.LIFE).replace(Regex("§."), ""))
    }

    @Test fun aRewardChestIsOneThatHoldsSomething() {
        // The harp roll belongs to the reward chests: just over half of the first opens.
        val r = Random(2)
        val reward = (1..20000).count { LootTables.chest(r) !is Drop.Nothing }
        assertEquals(0.54, reward / 20000.0, 0.012)
    }

    @Test fun superboomAndReviveStonesComeFromRoomClearsNotChests() {
        val r = Random(11)
        val n = 100000
        var boom = 0; var boomPieces = 0; var revive = 0; var none = 0
        repeat(n) {
            val d = LootTables.roomClear(r)
            when {
                d == null -> none++
                d is Drop.Item && d.item == LootItem.SUPERBOOM -> { boom++; boomPieces += d.count }
                d is Drop.Item && d.item == LootItem.REVIVE_STONE -> revive++
                else -> error("a room clear drops only those two: $d")
            }
        }
        assertEquals(0.04, boom / n.toDouble(), 0.004)
        assertEquals(0.01, revive / n.toDouble(), 0.002)
        assertEquals(1.1, boomPieces.toDouble() / boom, 0.05, "a tenth of the Superboom drops are two")
        assertTrue(none > n * 0.9)
        // Over a 28-room run that is about one Superboom for whoever clears them all (own lines: 1.25 per session).
        assertEquals(1.1, 28 * 0.04, 0.1)
        val items = (1..5000).map { LootTables.rewardItem(r).item }.groupingBy { it }.eachCount()
        assertEquals(setOf(LootItem.SUPERBOOM, LootItem.REVIVE_STONE), items.keys)
        assertEquals(0.8, items[LootItem.SUPERBOOM]!! / 5000.0, 0.03, "four Superboom to one Revive Stone")
    }

    @Test fun floorStacksHaveTheRecordedCounts() {
        assertEquals(5, LootItem.SPIRIT_LEAP.floorCount); assertEquals(2, LootItem.INFLATABLE_JERRY.floorCount)
        assertTrue(LootItem.entries.filter { it != LootItem.SPIRIT_LEAP && it != LootItem.INFLATABLE_JERRY }.all { it.floorCount == 1 })
        assertEquals(LootItem.entries.size, LootItem.entries.map { it.id }.toSet().size, "ids are unique")
        assertEquals("§6", LootItem.REVIVE_STONE.colour); assertEquals("legendary", LootItem.REVIVE_STONE.style)
        assertEquals("§a", LootItem.DECOY.colour); assertEquals("uncommon", LootItem.DECOY.style)
        assertEquals("§f", LootItem.INFLATABLE_JERRY.colour); assertEquals("§5", LootItem.HEALING_POTION.colour)
        assertEquals("§9", LootItem.SPIRIT_LEAP.colour); assertEquals("rare", LootItem.CANDYCOMB.style)
    }

    @Test fun itemSecretsAreFromTheRecordedList() {
        val r = Random(3)
        val seen = (1..2000).map { LootTables.itemSecret(r) }.toSet()
        assertEquals(LootTables.ITEM_SECRET_ITEMS.toSet(), seen)
        assertTrue(LootItem.SUPERBOOM !in seen, "Superboom comes from chests")
    }

    @Test fun theSameSeedGivesTheSameLoot() {
        val a = (1..50).map { LootTables.chest(Random(it)).toString().substringBefore('@') }
        val b = (1..50).map { LootTables.chest(Random(it)).toString().substringBefore('@') }
        assertEquals(a, b)
    }

    @Test fun powerFiveReadsAsRecorded() {
        // Hypixel: two indented lines, green numbers, a glyph before each stat name.
        val lines = Blessings.grantLines(Blessing.POWER, 5)
        assertEquals(listOf(
            "     §7Granted you §r§a+29§r§7 & §r§a+1.15x §r§c\ue00d Strength§r§7.",
            "     §7Also granted you §r§a+29§r§7 & §r§a+1.15x §r§9\ue007 Crit Damage§r§7.",
        ), lines)
        assertEquals(1.15, Blessings.multiplier(Blessing.POWER, 5), 1e-9)
        assertEquals(1.0 + 0.0436 * 5, Blessings.multiplier(Blessing.LIFE, 5), 1e-9)
    }

    @Test fun otherBlessingsAreOneLineEach() {
        assertEquals(listOf("     §7Granted you §r§a+1.22x HP §r§7and §r§a+1.22x §r§c\ue011 Health Regen§r§7."), Blessings.grantLines(Blessing.LIFE, 5))
        assertEquals(listOf("     §7Granted you §r§a+29§r§7 & §r§a+1.15x §r§b\ue003 Intelligence §r§7and §r§a+24 §r§f\ue022 Speed§r§7."), Blessings.grantLines(Blessing.WISDOM, 5))
        assertEquals(listOf("     §7Granted you §r§a+29§r§7 & §r§a+1.15x \ue008 Defense §r§7and §r§a+36 §r§c\ue050 Damage§r§7."), Blessings.grantLines(Blessing.STONE, 5))
        assertEquals(
            listOf("     §7Granted you §r§a+29§r§7 & §r§a+1.15x HP§r§7, §r§a+29§r§7 & §r§a+1.15x \ue008 Defense§r§7, §r§a+29§r§7 & §r§a+1.15x §r§b\ue003 Intelligence§r§7, and §r§a+29§r§7 & §r§a+1.15x §r§c\ue00d Strength§r§7."),
            Blessings.grantLines(Blessing.TIME, 5),
        )
        // Level I and II scale linearly.
        assertTrue(Blessings.grantLines(Blessing.STONE, 2)[0].contains("+11.6§r§7 & §r§a+1.06x"))
        assertTrue(Blessings.grantLines(Blessing.POWER, 1)[0].contains("+5.8§r§7 & §r§a+1.03x"))
    }

    @Test fun aBoostedDayReadsAsRecorded() {
        // Every boosted line the recordings have (Oct 12-13), § codes off.
        fun b(x: Blessing, l: Int) = Blessings.grantLines(x, l, Blessings.BOOST).map { it.replace(Regex("§."), "").replace(Regex("[\ue000-\uf8ff]"), "").trim() }
        assertEquals(listOf("Granted you +36.3 & +1.18x  Strength.", "Also granted you +36.3 & +1.18x  Crit Damage."), b(Blessing.POWER, 5))
        assertEquals(listOf("Granted you +7.3 & +1.04x  Strength.", "Also granted you +7.3 & +1.04x  Crit Damage."), b(Blessing.POWER, 1))
        assertEquals(listOf("Granted you +14.5 & +1.07x  Strength.", "Also granted you +14.5 & +1.07x  Crit Damage."), b(Blessing.POWER, 2))
        assertEquals(listOf("Granted you +36.3 & +1.18x  Intelligence and +24  Speed."), b(Blessing.WISDOM, 5))
        assertEquals(listOf("Granted you +14.5 & +1.07x  Intelligence and +9.6  Speed."), b(Blessing.WISDOM, 2))
        assertEquals(listOf("Granted you +7.3 & +1.04x  Defense and +7.2  Damage."), b(Blessing.STONE, 1))
        assertEquals(listOf("Granted you +36.3 & +1.18x  Defense and +36  Damage."), b(Blessing.STONE, 5))
        assertEquals(listOf("Granted you +1.27x HP and +1.27x  Health Regen."), b(Blessing.LIFE, 5))
        assertEquals(listOf("Granted you +1.11x HP and +1.11x  Health Regen."), b(Blessing.LIFE, 2))
        assertEquals(listOf("Granted you +1.05x HP and +1.05x  Health Regen."), b(Blessing.LIFE, 1))
        assertEquals(listOf("Granted you +36.3 & +1.18x HP, +36.3 & +1.18x  Defense, +36.3 & +1.18x  Intelligence, and +36.3 & +1.18x  Strength."), b(Blessing.TIME, 5))
    }

    @Test fun theAnnouncementLinesMatchTheRecordedForms() {
        assertEquals("§6§lDUNGEON BUFF! §r§fA §r§dBlessing of Wisdom V§r§f was found! (§r§a16s§r§f)", Blessings.announceOwn(Blessing.WISDOM, 5, 16 * 20))
        assertEquals("DUNGEON BUFF! A Blessing of Life II was found! (01m 16s)", Blessings.announceOwn(Blessing.LIFE, 2, 76 * 20).replace(Regex("§."), ""))
        assertEquals("DUNGEON BUFF! You found a Blessing of Life II!", Blessings.announceYouFound(Blessing.LIFE, 2, null).replace(Regex("§."), ""))
        assertEquals("DUNGEON BUFF! Someone found a Blessing of Life II!", Blessings.announceOther("Someone", Blessing.LIFE, 2).replace(Regex("§."), ""))
        assertEquals("§6§lDUNGEON BUFF! §r§bSomeone §r§ffound a §r§dBlessing of Life II§r§f!", Blessings.announceOther("Someone", Blessing.LIFE, 2))
        assertEquals("§eA §r§dBlessing of Power§r§e was picked up!", Blessings.pickedUp(Blessing.POWER))
    }

    @Test fun puzzleRewardsKeepTheirOldLevelMixForTeammates() {
        val r = Random(5)
        val n = (1..20000).map { Blessings.observerLevel(r) }.groupingBy { it }.eachCount()
        assertEquals(0.375, n[1]!! / 20000.0, 0.02); assertEquals(0.271, n[2]!! / 20000.0, 0.02); assertEquals(0.354, n[5]!! / 20000.0, 0.02)
        assertTrue(n.keys == setOf(1, 2, 5))
        assertTrue((1..200).none { Blessings.observerTimed(r, 2) })
    }

    @Test fun chestBlessingsAreLevelOneTwoOrFiveByTheRecordedShare() {
        val r = Random(9)
        val n = 30000
        val levels = (1..n).map { Blessings.chestLevel(Blessing.POWER, r) }.groupingBy { it }.eachCount()
        assertEquals(setOf(1, 2, 5), levels.keys)
        assertEquals(0.46, levels[1]!! / n.toDouble(), 0.015); assertEquals(0.30, levels[2]!! / n.toDouble(), 0.015); assertEquals(0.24, levels[5]!! / n.toDouble(), 0.015)
        assertTrue((1..300).all { Blessings.chestLevel(Blessing.TIME, r) == 5 }, "Time exists at level V only")
    }

    @Test fun chestBlessingTypesFollowTheUntimedFlow() {
        val r = Random(13)
        val n = 40000
        val t = (1..n).map { Blessings.pickChest(r) }.groupingBy { it }.eachCount()
        assertEquals(0.355, t[Blessing.LIFE]!! / n.toDouble(), 0.012)
        assertEquals(0.277, t[Blessing.POWER]!! / n.toDouble(), 0.012)
        assertEquals(0.204, t[Blessing.STONE]!! / n.toDouble(), 0.012)
        assertEquals(0.145, t[Blessing.WISDOM]!! / n.toDouble(), 0.012)
        assertEquals(0.019, t[Blessing.TIME]!! / n.toDouble(), 0.006)
        // The puzzle flow has its own mix and no Time.
        val p = (1..n).map { Blessings.pick(r) }.groupingBy { it }.eachCount()
        assertEquals(0.31, p[Blessing.POWER]!! / n.toDouble(), 0.012); assertEquals(0.27, p[Blessing.WISDOM]!! / n.toDouble(), 0.012)
        assertEquals(0.26, p[Blessing.LIFE]!! / n.toDouble(), 0.012); assertEquals(0.16, p[Blessing.STONE]!! / n.toDouble(), 0.012)
        assertNull(p[Blessing.TIME])
    }

    @Test fun loreItemLinesUseTheirColoursAndQuantity() {
        // The recorded lines: rank and name, then a reset and a white space, then the rest (the item in its colour, ` x2` dark grey).
        val who = "§b[MVP§r§c+§r§b] Someone"
        assertEquals("§b[MVP§r§c+§r§b] Someone§r§f §r§ehas obtained §r§9Superboom TNT §r§8x2§r§e!", com.engineerclient.p3sim.clear.secrets.SecretsModule.obtainedLine(who, LootItem.SUPERBOOM, 2))
        assertEquals("§b[MVP§r§c+§r§b] Someone§r§f §r§ehas obtained §r§6Revive Stone§r§e!", com.engineerclient.p3sim.clear.secrets.SecretsModule.obtainedLine(who, LootItem.REVIVE_STONE, 1))
        assertEquals("§6", com.engineerclient.p3sim.clear.secrets.essenceColour("Gold")); assertEquals("§5", com.engineerclient.p3sim.clear.secrets.essenceColour("Spider"))
    }

    @Test fun timeIsAlwaysLevelFive() {
        assertEquals(5, Blessings.levelFor(Blessing.TIME, 2))
        assertEquals(2, Blessings.levelFor(Blessing.POWER, 2))
        val picks = (1..4000).map { Blessings.pickChest(Random(it)) }.groupingBy { it }.eachCount()
        assertTrue(picks[Blessing.LIFE]!! > picks[Blessing.STONE]!! && picks[Blessing.STONE]!! > picks[Blessing.TIME]!!)
    }

    @Test fun theChatLinesAreTheRecordedTemplates() {
        // Each of these is a line template read out of real Dungeon Recorder sessions (names changed).
        assertEquals("§fYou found a §r§dWither Essence§r§f! Everyone gains an extra essence!", SecretLines.WITHER_ESSENCE_SELF)
        assertEquals("§bSomeone §r§ffound a §r§dWither Essence§r§f! Everyone gains an extra essence!", SecretLines.witherEssenceOther("§b", "Someone"))
        assertEquals("§6Someone §r§ffound a §r§dWither Essence§r§f! Everyone gains an extra essence!", SecretLines.witherEssenceOther("§6", "Someone"))
        assertEquals("§6§lESSENCE! §r§fYou found §r§bx10 Ice Essence§r§f!", SecretLines.chestEssenceSelf(Drop.Essence("Ice")))
        assertEquals("§6§lESSENCE! §r§bSomeone §r§ffound §r§bx10 Diamond Essence§r§f!", SecretLines.chestEssenceOther("§b", "Someone", Drop.Essence("Diamond")))
        assertEquals("§6§lESSENCE! §r§bSomeone §r§ffound §r§6x10 Gold Essence§r§f!", SecretLines.chestEssenceOther("§b", "Someone", Drop.Essence("Gold")))
        assertEquals("§6§lESSENCE! §r§aSomeone §r§ffound §r§5x10 Spider Essence§r§f!", SecretLines.chestEssenceOther("§a", "Someone", Drop.Essence("Spider")))
    }
}
