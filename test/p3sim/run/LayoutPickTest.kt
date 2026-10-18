package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutPickTest {
    private fun layout(id: String, playable: Boolean = true, floor: String = "F7", dup: String? = null) =
        """{"id":"$id","floor":"$floor","playable":$playable,${if (dup != null) """"dupOf":"$dup",""" else ""}"rooms":[],"doors":[],"keys":[],"puzzles":[]}"""

    private val pack = LayoutPack.fromJson(Fixtures.json("""{"format":"ads-layouts","version":1,"layouts":[
        ${layout("a")},${layout("b")},${layout("c", playable = false)},${layout("d", dup = "a")},${layout("m", floor = "M7")},${layout("e")}]}"""))!!

    private fun ok(r: LayoutPick.Result) = assertIs<LayoutPick.Result.Ok>(r).picked

    @Test fun randomAndSeedPickFromThePlayableF7PoolOnly() {
        val seen = HashSet<String>()
        for (s in 0L..200L) seen += ok(LayoutPick.pick(RunConfig(seed = s), pack)).layout.id
        assertEquals(setOf("a", "b", "e"), seen, "not the unplayable, duplicate or M7 layouts")
    }

    @Test fun theSameSeedGivesTheSameLayoutAndRandomUsesTheRunSeed() {
        val a = ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Seed(42), seed = 1), pack))
        val b = ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Seed(42), seed = 2), pack))
        assertEquals(a.layout.id, b.layout.id); assertEquals(42L, a.seed)
        assertEquals(ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Seed(7)), pack)).layout.id, ok(LayoutPick.pick(RunConfig(seed = 7), pack)).layout.id)
    }

    @Test fun aRecordedIdBypassesThePoolButWarns() {
        assertNull(ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Recorded("a")), pack)).warning)
        val c = ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Recorded("c")), pack))
        assertEquals("c", c.layout.id); assertNotNull(c.warning); assertNull(c.seed)
        assertTrue(ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Recorded("m")), pack)).warning!!.contains("M7"))
        assertTrue(ok(LayoutPick.pick(RunConfig(layout = LayoutSource.Recorded("d")), pack)).warning!!.contains("repeats a"))
    }

    @Test fun anUnknownIdOrAnEmptyPoolIsAMessage() {
        assertTrue(assertIs<LayoutPick.Result.Failed>(LayoutPick.pick(RunConfig(layout = LayoutSource.Recorded("zz")), pack)).message.contains("zz"))
        val empty = LayoutPack.fromJson(Fixtures.json("""{"format":"ads-layouts","version":1,"layouts":[${layout("c", playable = false)}]}"""))!!
        assertTrue(assertIs<LayoutPick.Result.Failed>(LayoutPick.pick(RunConfig(), empty)).message.contains("no playable"))
    }
}
