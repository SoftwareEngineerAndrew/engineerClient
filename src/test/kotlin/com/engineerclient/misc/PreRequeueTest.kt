package com.engineerclient.misc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pre-Requeue's decision: armed by Necron's end line, sent once on the TNT or the chest room. */
class PreRequeueTest {

    /** Runs client ticks with a server tick each (unless [frozen] says not), returns the tick it fired on and why. */
    private fun PreRequeue.Plan.run(from: Int, to: Int, guard: Boolean = true, frozen: (Int) -> Boolean = { false }): Pair<Int, String>? {
        for (t in from..to) {
            if (!frozen(t)) onServerTick()
            onClientTick(t, guard)?.let { return t to it }
        }
        return null
    }

    @Test
    fun `nothing before the end line`() {
        val p = PreRequeue.Plan()
        p.onTnt(100, 0)
        p.onChestRoom(110)
        assertNull(p.run(100, 200))
    }

    @Test
    fun `sent on the TNT tick`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        assertEquals(140 to "Necron's TNT", p.run(100, 200))
    }

    @Test
    fun `extra ticks after the TNT`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 10) }
        assertEquals(150 to "Necron's TNT +10 ticks", p.run(100, 200))
    }

    @Test
    fun `only the first TNT counts`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0); onTnt(145, 0) }
        assertEquals(140, p.run(100, 200)?.first)
    }

    @Test
    fun `sent once`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        p.run(100, 200)
        p.onTnt(300, 0); p.onChestRoom(310); p.onEndLine()
        assertNull(p.run(200, 400))
    }

    @Test
    fun `held while the server is frozen, sent when ticks come back`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        // No server ticks from 137 to 160.
        assertEquals(161, p.run(100, 200) { it in 137..160 }?.first)
    }

    @Test
    fun `a short hiccup under 3 ticks doesn't hold it`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        assertEquals(140, p.run(100, 200) { it in 138..139 }?.first)
    }

    @Test
    fun `no freeze guard - sent even when frozen`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        assertEquals(140, p.run(100, 200, guard = false) { it in 120..160 }?.first)
    }

    @Test
    fun `no TNT - the chest room`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onChestRoom(190) }
        assertEquals(190 to "the chest room (no TNT seen)", p.run(100, 200))
    }

    @Test
    fun `the chest room before a delayed TNT send wins`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 40); onChestRoom(170) }
        assertEquals(170, p.run(100, 200)?.first)
    }

    @Test
    fun `the join command's floor names`() {
        assertEquals("catacombs_floor_seven", PreRequeue.Plan.instance("F7"))
        assertEquals("master_catacombs_floor_seven", PreRequeue.Plan.instance("M7"))
    }

    @Test
    fun `!dt holds every requeue, with its reason`() {
        val d = PreRequeue.Downtime()
        assertEquals(false, d.active)
        assertEquals("§c!dt §7from §fp3wr§7: no requeue at the end of this run (food).", d.onParty("p3wr", "!dt food"))
        assertEquals(true, d.active)
        d.onParty("Skyyqt", "!DOWNTIME")
        assertEquals("p3wr (food), Skyyqt (no reason given)", d.who())
    }

    @Test
    fun `!undt takes only your own back`() {
        val d = PreRequeue.Downtime()
        d.onParty("p3wr", "!dt")
        d.onParty("Skyyqt", "!dt brb")
        assertNull(d.onParty("owoskilly", "!undt"))
        assertEquals("§7!undt from §fp3wr§7, still waiting on Skyyqt (brb).", d.onParty("p3wr", "!undt"))
        assertEquals("§a!undt §7from §fSkyyqt§7: requeue back on.", d.onParty("Skyyqt", "!undowntime"))
        assertEquals(false, d.active)
    }

    @Test
    fun `other party chat is not downtime`() {
        val d = PreRequeue.Downtime()
        assertNull(d.onParty("p3wr", "dt please"))
        assertNull(d.onParty("p3wr", "!dtx"))
        assertNull(d.onParty("p3wr", "Leaped to Skyyqt!"))
        assertEquals(false, d.active)
    }

    @Test
    fun `the run's end clears it`() {
        val d = PreRequeue.Downtime()
        d.onParty("p3wr", "!dt")
        d.clear()
        assertEquals(false, d.active)
    }

    @Test
    fun `a world change resets it`() {
        val p = PreRequeue.Plan().apply { onEndLine(); onTnt(140, 0) }
        p.reset()
        assertNull(p.run(100, 200))
    }
}
