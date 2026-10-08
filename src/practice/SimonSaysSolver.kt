package com.engineerclient.practice

/**
 * Simon Says' answer, worked out the way 208 recorded F7 runs say the device behaves (research in
 * ss-research/HANDOFF.md; replayed against every round there: 99.7% right, Odin's own 94%).
 * Pure: fed the device's lamps, buttons and presses, with server ticks; [C] is whatever names a
 * cell (a BlockPos in game).
 *
 * A show is the lights between the buttons going and coming back. Lights are taken as they come on,
 * repeats kept. When the buttons come back the answer is settled:
 *  - lights at the front that were on for 2 ticks or less are strays (a flash before the show);
 *  - then, if the buttons came back while the last light was still on - the timing of a skip
 *    (several start presses) - and there are 3 or more lights, the first is a stray too.
 * Nothing can be pressed before that, so there is no guessing. While the show is on, [answer] is
 * the lights so far (front flashes dropped) to draw the in-between solution.
 */
class SimonSaysSolver<C> {

    private class Light<C>(val cell: C, val on: Long) { var off = -1L }

    private val shown = ArrayList<Light<C>>()
    private var showing = false

    /** The answer, in order: while a show is on, the lights so far; empty between rounds. */
    var answer: List<C> = emptyList()
        private set

    /** How many of [answer] have been pressed. */
    var next = 0
        private set

    fun lightOn(cell: C, tick: Long) {
        if (!showing) { showing = true; shown.clear(); answer = emptyList(); next = 0 }
        shown += Light(cell, tick)
        answer = soFar()
    }

    fun lightOff(cell: C, tick: Long) {
        shown.lastOrNull { it.cell == cell && it.off < 0 }?.off = tick
        if (showing) answer = soFar()
    }

    /** The show so far, flashes at the front dropped (the skip rule needs the show's end). */
    private fun soFar(): List<C> {
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        return a.map { it.cell }
    }

    /** The grid's buttons are back: the show is over, and its answer settled. */
    fun buttonsUp() {
        if (!showing) return
        showing = false
        val a = shown.toMutableList()
        while (a.size > 1 && a[0].off >= 0 && a[0].off - a[0].on <= 2) a.removeAt(0)
        if (shown.any { it.off < 0 } && a.size >= 3) a.removeAt(0)
        answer = a.map { it.cell }
        next = 0
    }

    /** The buttons went away (a round done or failed): no answer until the next show. */
    fun buttonsGone() {
        if (showing) return
        answer = emptyList(); next = 0
    }

    /**
     * A button pressed (its lamp [cell]). The next one moves on; one further along means presses
     * were missed, so it catches up; anything else is left to the device (a wrong press ends the round).
     */
    fun pressed(cell: C) {
        if (showing) buttonsUp() // the buttons came back without it being seen
        if (answer.getOrNull(next) == cell) { next++; return }
        val i = answer.indexOf(cell)
        if (i >= next) next = i + 1
    }

    fun reset() { shown.clear(); showing = false; answer = emptyList(); next = 0 }
}
