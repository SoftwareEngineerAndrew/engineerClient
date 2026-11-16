package com.engineerclient.terminals

import com.adm.events.core.Event
import net.minecraft.world.inventory.AbstractContainerMenu

abstract class TerminalEvent(val terminal: TerminalHandler) : Event {
    class Open(terminal: TerminalHandler) : TerminalEvent(terminal)
    class Close(terminal: TerminalHandler) : TerminalEvent(terminal)
    class Solve(terminal: TerminalHandler) : TerminalEvent(terminal)
    class Click(terminal: TerminalHandler, val slotIndex: Int, val button: Int, val solution: List<Int>) : TerminalEvent(terminal)
}

/** A whole window's items arrived at once (vanilla sets them without setItem, so SetSlotEvent never sees them). */
class ContainerContentsEvent(val menu: AbstractContainerMenu) : Event
