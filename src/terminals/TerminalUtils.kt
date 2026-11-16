package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.sim.TermSimGUI
import com.adm.events.*
import com.adm.events.core.EventBus
import com.adm.events.core.EventPriority
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.events.core.onSend
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.inventory.ContainerInput

/** The terminal you have open, from its window's title to its close. */
object TerminalUtils {
    private val activated = Regex("^(.{1,16}) activated a terminal! \\((\\d)/(\\d)\\)$")

    @JvmStatic var currentTerm: TerminalHandler? = null
        private set
    var lastTermOpened: TerminalHandler? = null
        private set

    private fun screenMenu() = (mc.gui.screen() as? AbstractContainerScreen<*>)?.menu

    fun install() {
        on<ScreenEvent.Open>(EventPriority.HIGHEST) {
            val handler = TerminalTypes.handlerFor(screen.title.string) ?: return@on
            currentTerm?.let { TerminalEvent.Close(it).postAndCatch() }
            currentTerm = handler
            lastTermOpened = handler
            TerminalEvent.Open(handler).postAndCatch()
        }

        on<ScreenCloseEvent> {
            val term = currentTerm ?: return@on
            currentTerm = null
            TerminalEvent.Close(term).postAndCatch()
        }

        on<SetSlotEvent> {
            val term = currentTerm ?: return@on
            if (menu === screenMenu()) term.update(slots, slotIndex)
        }

        on<ContainerContentsEvent> {
            val term = currentTerm ?: return@on
            if (menu === screenMenu()) term.update(menu.slots, -1)
        }

        on<TickEvent.End> { currentTerm?.tick() }
        on<TickEvent.Server> { currentTerm?.let { it.ticksOpened++ } }

        on<MessageEvent.Chat> {
            val m = activated.find(message) ?: return@on
            if (m.groupValues[1] == mc.player?.name?.string) lastTermOpened?.let { TerminalEvent.Solve(it).postAndCatch() }
        }

        // The simulator is a local screen: clicks on it never go to the server, and server slot updates belong to the inventory.
        onSend<ServerboundContainerClickPacket>(EventPriority.LOW) {
            val sim = mc.gui.screen() as? TermSimGUI ?: return@onSend
            if (containerInput != ContainerInput.PICKUP_ALL) sim.clickIndex(slotNum.toInt(), buttonNum.toInt())
            it.cancel()
        }
        onReceive<ClientboundContainerSetSlotPacket>(EventPriority.HIGH) {
            val sim = mc.gui.screen() as? TermSimGUI ?: return@onReceive
            if (slot !in 0 until sim.size) return@onReceive
            mc.player?.inventoryMenu?.setItem(slot, stateId, item)
            it.cancel()
        }

        EventBus.subscribe(this)
    }
}
