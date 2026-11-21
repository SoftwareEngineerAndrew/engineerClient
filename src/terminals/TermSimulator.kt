package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.sim.StartGUI

/** The terminal simulator: /termsim, the Simulator Key, or Open Simulator. */
object TermSimulator {
    private val randomPool = arrayOf(TerminalTypes.PANES, TerminalTypes.RUBIX, TerminalTypes.NUMBERS, TerminalTypes.STARTS_WITH, TerminalTypes.SELECT)

    fun open() = mc.execute { if (mc.player != null) StartGUI.open(Terminals.simPing.toLong()) }

    fun openRandomTerminal(ping: Long = 0L) = randomPool.random().simulator().open(ping)
}
