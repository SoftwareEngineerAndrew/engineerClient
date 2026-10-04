package com.engineerclient.p3sim

import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.core.component.DataComponents
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The sim's data and puzzles, headless: the arena and its animations parse, every terminal is solvable as Hypixel's is. */
class P3SimDataTest {
    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            // Items get their components (names, counts) as a server binds them.
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach { it.apply() }
        }
    }

    private fun parses(s: String) = try { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false); true } catch (_: Throwable) { false }

    @Test
    fun `every arena block state parses`() {
        val stream = javaClass.getResourceAsStream("/assets/engineerclient/p3sim/arena.bin") ?: fail("arena.bin missing")
        val bad = ArrayList<String>()
        DataInputStream(GZIPInputStream(stream)).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            assertEquals("P3A1", String(magic))
            repeat(6) { inp.readInt() }
            repeat(inp.readInt()) {
                val s = ByteArray(inp.readUnsignedShort()).also { b -> inp.readFully(b) }.toString(Charsets.UTF_8)
                if (!parses(s)) bad += s
            }
        }
        assertTrue(bad.isEmpty(), "unparsable: $bad")
    }

    @Test
    fun `every animation frame parses and the fight's animations exist`() {
        val names = HashSet<String>()
        val bad = HashSet<String>()
        for (file in listOf("anims-p3.json", "anims-p124.json")) {
            val text = javaClass.getResourceAsStream("/assets/engineerclient/p3sim/$file")!!.readBytes().toString(Charsets.UTF_8)
            for ((name, v) in JsonParser.parseString(text).asJsonObject.entrySet()) {
                if (!v.isJsonObject) continue
                val fr = v.asJsonObject.getAsJsonArray("frames") ?: continue
                names += name
                fr.forEach { val s = it.asJsonArray[4].asString; if (!parses(if (':' in s) s else "minecraft:$s")) bad += s }
            }
        }
        assertTrue(bad.isEmpty(), "unparsable: $bad")
        for (n in listOf("p3start", "gate12", "gate23", "gate34", "door1", "door2", "door3", "core", "p3end", "ss_s1done", "p1strip", "p1end", "p4"))
            assertTrue(n in names, "missing animation $n")
    }

    @Test
    fun `order terminal - 1 to 14 solves it, a wrong number does nothing`() {
        val t = Terminals.Order()
        assertEquals("Click in order!", t.title)
        val slot = { n: Int -> t.items.indices.first { t.items[it].item == Items.RED_STAINED_GLASS_PANE && t.items[it].count == n } }
        assertFalse(t.click(slot(2), 0, ContainerInput.PICKUP))
        for (n in 1..14) assertTrue(t.click(slot(n), 0, ContainerInput.PICKUP))
        assertTrue(t.solved())
    }

    @Test
    fun `panes terminal - clicking every red one solves it`() {
        val t = Terminals.Panes()
        assertFalse(t.solved())
        t.items.indices.filter { t.items[it].item == Items.RED_STAINED_GLASS_PANE }.forEach { assertTrue(t.click(it, 0, ContainerInput.PICKUP)) }
        assertTrue(t.solved())
    }

    @Test
    fun `rubix terminal - left steps forward, right steps back, all one colour solves it`() {
        repeat(20) {
            val t = Terminals.Rubix()
            assertFalse(t.solved())
            val order = listOf(Items.RED_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.YELLOW_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE)
            val slots = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)
            for (s in slots) {
                while (t.items[s].item != Items.RED_STAINED_GLASS_PANE) {
                    val i = order.indexOf(t.items[s].item)
                    // Right click (back) when red is nearer that way.
                    if (i <= 2) t.click(s, 1, ContainerInput.PICKUP) else t.click(s, 0, ContainerInput.PICKUP)
                }
            }
            assertTrue(t.solved())
        }
    }

    @Test
    fun `starts with terminal - every item with the letter, once`() {
        repeat(20) {
            val t = Terminals.Starts()
            val letter = t.title.substringAfter("'")[0]
            val want = t.items.indices.filter { t.items[it].hoverName.string.startsWith(letter) }
            assertTrue(want.size >= 2)
            val wrong = t.items.indices.firstOrNull { it in (10..34) && it !in want && t.items[it].item != Items.BLACK_STAINED_GLASS_PANE }
            if (wrong != null) assertFalse(t.click(wrong, 0, ContainerInput.PICKUP))
            want.forEach { assertTrue(t.click(it, 0, ContainerInput.PICKUP)); assertFalse(t.click(it, 0, ContainerInput.PICKUP)) }
            assertTrue(t.solved())
        }
    }

    @Test
    fun `select terminal - 5 or 6 of the colour, all of them solves it`() {
        repeat(20) {
            val t = Terminals.Select()
            val colour = Terminals.COLOURS.first { t.title == "Select all the ${it.title} items!" }
            val ids = colour.items.map { BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(it.first)) }.toSet()
            val want = t.items.indices.filter { t.items[it].item in ids }
            assertTrue(want.size in 5..6, "${want.size} of ${colour.title}")
            want.forEach { assertTrue(t.click(it, 0, ContainerInput.PICKUP)) }
            assertTrue(t.solved())
            assertTrue(want.all { t.items[it].get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true })
        }
    }

    @Test
    fun `melody terminal - locking on the magenta column four times solves it`() {
        val t = Terminals.Melody()
        var tick = 0
        var locks = 0
        while (!t.solved() && tick < 2000) {
            tick++
            t.tick(tick)
            val row = (0 until 4).firstOrNull { t.items[(it + 1) * 9 + 7].item == Items.LIME_TERRACOTTA } ?: continue
            val target = (1..5).first { t.items[it].item == Items.MAGENTA_STAINED_GLASS_PANE }
            if (t.items[(row + 1) * 9 + target].item == Items.LIME_STAINED_GLASS_PANE && t.click((row + 1) * 9 + 7, 0, ContainerInput.PICKUP)) locks++
        }
        assertTrue(t.solved(), "not solved after $tick ticks")
        assertEquals(4, locks)
    }

    @Test
    fun `melody terminal - a wrong lock freezes the lime for two steps`() {
        val t = Terminals.Melody()
        val lime = { (1..5).first { t.items[9 + it].item == Items.LIME_STAINED_GLASS_PANE } }
        val target = (1..5).first { t.items[it].item == Items.MAGENTA_STAINED_GLASS_PANE }
        // Step until the lime is off target, then lock: refused, and the next two steps keep it still.
        var tick = 0
        do { tick += 10; t.tick(tick) } while (lime() == target)
        val at = lime()
        assertFalse(t.click(16, 0, ContainerInput.PICKUP))
        t.tick(tick + 10); assertEquals(at, lime())
        t.tick(tick + 20); assertEquals(at, lime())
        t.tick(tick + 30); assertTrue(lime() != at)
    }

    @Test
    fun `stations - 29, counted 7 8 7 7, labels unique per section`() {
        val all = Station.all()
        assertEquals(29, all.size)
        for (s in 1..4) {
            val here = all.filter { it.section == s }
            assertEquals(Station.total(s), here.size, "S$s")
            assertEquals(here.size, here.map { it.label }.toSet().size)
        }
    }

    @Test
    fun `every station a bot does has a stand-here spot`() {
        for (st in Station.all()) assertTrue(Party.STANDS.containsKey("S${st.section} ${st.label}"), "no spot for S${st.section} ${st.label}")
    }

    @Test
    fun `goldor's track - a closed loop through his start`() {
        val start = GoldorPhase.Goldor.trackPos(GoldorPhase.Goldor.START_S)
        assertTrue(Math.abs(start.x - 80.0) < 0.5 && Math.abs(start.z - 40.3) < 0.5, "start $start")
        val a = GoldorPhase.Goldor.trackPos(0.0)
        val b = GoldorPhase.Goldor.trackPos(GoldorPhase.Goldor.LOOP - 1e-6)
        assertTrue(a.distanceTo(b) < 0.1, "$a vs $b")
        // Unequal segments (goldor.md, the track): S1 0-90.7, S2 -182.1, S3 -272.8, S4 -364.2.
        val g = GoldorPhase.Goldor
        assertTrue(g.segment(g.START_S) == 3 && g.segment(90.6) == 0 && g.segment(90.8) == 1 && g.segment(272.9) == 3, "segments")
        // Each sprint ends ~1.6 past its corner, on the next section's line.
        for ((i, to) in g.SPRINT_TO.withIndex()) {
            assertTrue(g.segment(to) == i + 1, "sprint $i ends in ${g.segment(to)}")
            val corner = g.trackPos(g.BOUNDS[i + 1])
            val d = g.trackPos(to).distanceTo(corner)
            assertTrue(d in 1.0..2.5, "sprint $i ends $d past its corner")
        }
    }
}
