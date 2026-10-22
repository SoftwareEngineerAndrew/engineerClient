package com.engineerclient.terminals

import com.odtheking.odin.events.TerminalEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.features.impl.boss.TerminalSolver
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils

/**
 * Keeps Odin's Terminal Solver off the terminals ADS's own solver has open (in ADS's worlds), so no window is solved
 * and highlighted twice. Only with Odin: [install] is the Odin addon's.
 *
 * Odin's solver draws, clicks and hides tooltips from listeners on its event bus, so while ADS has a terminal open it
 * is taken off the bus, and put back (if it is still on) as soon as that terminal closes. Its GUI scale and custom GUI
 * are read straight from it rather than through the bus; OdinTerminalSolverMixin answers those for it meanwhile.
 * Odin's terminal tracking itself stays on, for its terminal times and everything else that reads it.
 */
object OdinTerminalGuard {
    /** Odin's solver is off the bus because of ADS. */
    private var held = false

    fun install() {
        AdsTerminalSolver.odinGuard = ::set
        // Odin's Terminal Sounds plays its click sound on its terminal's click event; ADS's clicks post it too.
        AdsTerminalSolver.onClick = { slot, button, solution ->
            TerminalUtils.currentTerm?.let { TerminalEvent.Click(it, slot, button, ArrayList(solution)).postAndCatch() }
        }
        AdsTerminalSolver.hideNumbers = { com.engineerclient.misc.RandomStuff.hidesTerminalNumbers() }
    }

    private fun set(adsOwns: Boolean) {
        if (adsOwns && !held) {
            held = true
            EventBus.unsubscribe(TerminalSolver)
        } else if (!adsOwns && held) {
            held = false
            if (TerminalSolver.enabled) EventBus.subscribe(TerminalSolver)
        }
    }

    /** Odin's solver is standing aside for ADS's (OdinTerminalSolverMixin). */
    @JvmStatic
    fun standsAside(): Boolean = AdsTerminalSolver.currentTerm != null
}
