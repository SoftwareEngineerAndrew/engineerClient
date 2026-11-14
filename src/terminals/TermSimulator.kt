package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.sim.StartGUI

/** The terminal simulator: /termsim, the Simulator Key, or Open Simulator. */
object TermSimulator {
    fun open() = mc.execute { StartGUI.open(Terminals.simPing.toLong()) }

    fun openRandomTerminal(ping: Long = 0L) {
        listOf(TerminalTypes.PANES, TerminalTypes.RUBIX, TerminalTypes.NUMBERS, TerminalTypes.STARTS_WITH, TerminalTypes.SELECT).random().simulator().open(ping)
    }
}
