package com.engineerclient.practice

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimonSaysSolverTest {

    /** Lights as (cell, on tick, off tick or -1 if still on at buttons-up), then buttons up. */
    private fun show(vararg lights: Triple<String, Long, Long>): List<String> {
        val s = SimonSaysSolver<String>()
        val offs = ArrayList<Pair<Long, String>>()
        for ((c, on, off) in lights) { s.lightOn(c, on); if (off >= 0) offs += off to c }
        for ((t, c) in offs.sortedBy { it.first }) s.lightOff(c, t)
        s.buttonsUp()
        return s.answer
    }

    @Test
    fun `a normal show is all of it`() {
        assertEquals(listOf("a", "b", "c"), show(Triple("a", 0, 8), Triple("b", 8, 16), Triple("c", 16, 24)))
    }

    @Test
    fun `a skip - buttons back while the last is lit, 3 lights - drops the first`() {
        assertEquals(listOf("b", "c"), show(Triple("s", 0, 6), Triple("b", 6, 14), Triple("c", 14, -1)))
    }

    @Test
    fun `a 2-light skip is both, in order`() {
        assertEquals(listOf("a", "b"), show(Triple("a", 0, 8), Triple("b", 12, -1)))
    }

    @Test
    fun `a flash before the show is a stray`() {
        assertEquals(listOf("b"), show(Triple("x", 0, 1), Triple("b", 7, 15)))
    }

    @Test
    fun `a stray that matches the last light is still dropped, the repeat kept`() {
        assertEquals(listOf("b", "a"), show(Triple("a", 0, 6), Triple("b", 6, 14), Triple("a", 14, -1)))
    }

    @Test
    fun `presses move on, missed ones are caught up, nothing before buttons`() {
        val s = SimonSaysSolver<String>()
        s.lightOn("a", 0); s.lightOff("a", 8); s.lightOn("b", 8); s.lightOff("b", 16); s.lightOn("c", 16); s.lightOff("c", 24)
        assertEquals(emptyList(), s.answer)
        s.buttonsUp()
        s.pressed("a"); assertEquals(1, s.next)
        s.pressed("c"); assertEquals(3, s.next)
        s.buttonsGone(); assertEquals(emptyList(), s.answer)
    }

    /**
     * Every round of the recorded runs, if the research data is on this machine: the answer at
     * buttons-up against the next show (which replays it, plus one) where that show continues it.
     */
    @Test
    fun `recorded runs`() {
        val file = Path.of(System.getProperty("user.home"), "Projects", "ss-research", "ss.json")
        if (!Files.exists(file)) return
        var rounds = 0; var right = 0
        for (run in JsonParser.parseString(Files.readString(file)).asJsonArray) {
            val p3 = run.asJsonObject["p3"]?.takeIf { !it.isJsonNull }?.asLong ?: continue
            val s = SimonSaysSolver<String>()
            val buttons = HashSet<String>()
            val shows = ArrayList<Pair<List<String>, List<String>>>() // (lights, answer)
            var lights = ArrayList<String>()
            for (e in run.asJsonObject["ev"].asJsonArray) {
                val a = e.asJsonArray
                if (!a[0].isJsonPrimitive || !a[0].asJsonPrimitive.isNumber) continue
                val t = a[0].asLong
                if (t < p3 || a.size() < 4) continue
                val cell = "${a[2].asString},${a[3].asString}"
                when (a[1].asString) {
                    "on" -> { lights += cell; s.lightOn(cell, t) }
                    "off" -> s.lightOff(cell, t)
                    "up", "down" -> {
                        buttons += cell
                        // As in game: any button appearing with 8 or more up ends a show in progress.
                        if (buttons.size >= 8 && lights.isNotEmpty()) { s.buttonsUp(); shows += lights to s.answer; lights = ArrayList() }
                    }
                    "air" -> { buttons -= cell; if (buttons.size < 8) s.buttonsGone() }
                }
            }
            for (i in 0 until shows.size - 1) {
                val (lit, got) = shows[i]
                val want = shows[i + 1].first.dropLast(1)
                val k = lit.size - want.size
                if (want.isEmpty() || k < 0 || lit.drop(k) != want) continue
                rounds++; if (got == want) right++
            }
        }
        println("Simon Says solver on recorded runs: $right/$rounds")
        assertTrue(rounds > 500 && right >= rounds * 0.98, "$right/$rounds")
    }
}
