package com.engineerclient.p3sim.blood.camp

import com.engineerclient.p3sim.clear.blood.HeadTextures
import com.engineerclient.p3sim.clear.blood.camp.CampLines
import com.engineerclient.p3sim.clear.blood.camp.CampMech
import com.engineerclient.p3sim.clear.blood.camp.CampMobs
import com.engineerclient.p3sim.clear.blood.camp.Vec3
import com.engineerclient.p3sim.clear.blood.camp.WallClass
import com.engineerclient.p3sim.clear.blood.camp.WallLook
import com.engineerclient.p3sim.clear.blood.camp.WallPlan
import com.engineerclient.p3sim.clear.blood.camp.WallStation
import com.engineerclient.p3sim.pack.rooms.SkullTexture
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the camp's mobs, lines and wall heads are made of. */
class CampLooksTest {
    private val here = Vec3(0.0, 0.0, 0.0)

    @Test
    fun campMobsFollowTheMeasuredHp() {
        val rng = Random(1)
        repeat(400) {
            val std = CampMobs.roll(WallStation(0, here, WallClass.STANDARD, "Mute"), rng)
            val base = if (std.modifier == "Healthy") 1.3 else 1.0
            // Whole thousands, so a Healthy one can round half a thousand past 1.3 times the range.
            assertTrue(std.hp / base in 247_500.0..256_500.0, "hp ${std.hp} ${std.modifier}")
            assertEquals(0.0, std.hp % 1000.0, "hp ${std.hp} is a whole thousand")
            assertEquals("player", std.entity)
            val giant = CampMobs.roll(WallStation(1, here, WallClass.GIANT, "Giant"), rng)
            assertEquals("giant", giant.entity)
            assertEquals(CampMobs.ICON_GIANT, giant.tagHead)
            assertTrue(giant.hp >= CampMobs.GIANT_HP)
        }
        val ooze = CampMobs.roll(WallStation(2, here, WallClass.STANDARD, "Ooze"), Random(2))
        assertTrue(ooze.hp > 300_000)
        val ws = CampMobs.roll(WallStation(3, here, WallClass.WANDERING_SOUL, "Wandering Soul"), rng)
        assertEquals("Boomer", ws.modifier); assertEquals(false, ws.counted)
        val x = CampMobs.extras(CampMobs.roll(WallStation(0, here, WallClass.STANDARD, "Freak"), rng), "initial")
        assertEquals("Undead", x["profile"]); assertEquals("21", x["deathDelay"]); assertEquals("2", x["uuidVersion"])
    }

    @Test
    fun bossesAndGiantsClimbTheLadderOfTheirBaseHp() {
        val rng = Random(4)
        val rungs = (0..4).map { k -> CampMobs.BOSS_HP * Math.pow(CampMobs.LADDER, k.toDouble()) }
        repeat(300) {
            val b = CampMobs.roll(WallStation(0, here, WallClass.BOSS, "Bonzo"), rng)
            val base = b.hp / (if (b.modifier == "Healthy") CampMobs.HEALTHY else 1.0)
            assertTrue(rungs.any { Math.abs(it - base) < 2.0 }, "boss hp ${b.hp} ${b.modifier}")
        }
    }

    @Test
    fun modifiersFollowTheMeasuredShares() {
        val rng = Random(3)
        val c = (1..20000).groupingBy { CampMobs.modifier(rng) }.eachCount()
        assertEquals(setOf("Speedy", "Healthy", "Stormy", "Stealth", "Golden", "Boomer"), c.keys)
        assertTrue(c.getValue("Speedy") > c.getValue("Stormy") * 1.4)
    }

    @Test
    fun aCampModifierIsEveryMobsAndARecordedHpIsKept() {
        val rng = Random(4)
        val here = Vec3(0.0, 0.0, 0.0)
        repeat(50) { assertEquals("Golden", CampMobs.roll(WallStation(0, here, WallClass.STANDARD, "Mute"), rng, "Golden").modifier) }
        assertEquals(13_000_000.0, CampMobs.roll(WallStation(0, here, WallClass.BOSS, "Spirit Bear"), rng, "Stealth", 13_000_000.0).hp)
    }

    @Test
    fun theOpeningLineIsOneOfTheTwoAndTheOthersAreDrawnByClass() {
        val rng = Random(1)
        val firsts = (1..2000).map { CampLines.opening(rng) }
        assertEquals(setOf(CampLines.INTRO, CampLines.INTRO_OTHER), firsts.toSet())
        assertEquals(CampLines.INTRO_SHARE, firsts.count { it == CampLines.INTRO } / 2000.0, 0.04)
        assertNull(CampLines.pick(CampMech.Line.INTRO, rng))
        for (line in listOf(CampMech.Line.SPAWN, CampMech.Line.REACT, CampMech.Line.HANDLE, CampMech.Line.ENOUGH)) assertTrue(CampLines.pick(line, rng) != null)
    }

    private fun table(vararg roles: String) = roles.map { SkullTexture(it.hashCode().toString(16).padStart(64, '0'), "v", null, it) }

    @Test
    fun aHeadWearsItsRoleAndAPackWithoutTheRolesGivesPlainHeads() {
        val wall = WallPlan.assign((0 until 30).map { Vec3(it.toDouble(), 0.0, 0.0) }, Random(2))
        val rng = Random(1)
        val none = WallLook.of(emptyList())
        assertTrue(wall.all { none.keyFor(it, rng) == HeadTextures.PLAIN })

        val roles = listOf(WallLook.GIANT, WallLook.SOUL, WallLook.boss(0), WallLook.boss(1), WallLook.boss(2), WallLook.boss(3), "blood_decor_1", "blood_decor_2") +
            WallPlan.STANDARD.map { WallLook.mob(it) }
        val look = WallLook.of(table(*roles.toTypedArray()))
        val keys = wall.associateWith { look.keyFor(it, rng) }
        assertTrue(keys.none { it.value == HeadTextures.PLAIN })
        // every non-decor head has its own key; the boss keys follow the boss's place in the list
        val own = keys.filter { it.key.cls != WallClass.DECOR }.values
        assertEquals(own.size, own.toSet().size)
        for ((st, key) in keys) if (st.cls == WallClass.BOSS) assertEquals(WallLook.boss(WallPlan.WALL_BOSSES.indexOf(st.mobName)), key)
        assertTrue(keys.filter { it.key.cls == WallClass.DECOR }.values.all { it.startsWith(WallLook.DECOR_PREFIX) })
        // a pack that has only some roles dresses only those heads
        val some = WallLook.of(table(WallLook.GIANT))
        val giant = wall.first { it.cls == WallClass.GIANT }
        assertEquals(WallLook.GIANT, some.keyFor(giant, rng))
        assertEquals(HeadTextures.PLAIN, some.keyFor(wall.first { it.cls == WallClass.STANDARD }, rng))
    }

    @Test
    fun aRoleKeyResolvesToThePacksTextureThroughTheHostsOwnResolver() {
        val t = table(WallLook.GIANT)
        val r = HeadTextures.resolve(WallLook.GIANT, t)
        assertEquals("v", r!!.value)
    }
}
