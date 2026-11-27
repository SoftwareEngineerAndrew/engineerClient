package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.TerminalHandler.Companion.RIGHT
import com.mojang.blaze3d.platform.InputConstants
import com.adm.events.ScreenEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Terminal keys: E as a right click, and a held key counting once. */
object TermKeys {
    private val held = HashSet<Int>()

    private fun dropKey(): Int {
        val k = InputConstants.getKey(mc.options.keyDrop.saveString())
        return if (k.type == InputConstants.Type.KEYBOARD) k.value else InputConstants.UNKNOWN.value
    }

    fun install() {
        on<ScreenEvent.KeyPress>(priority = Int.MAX_VALUE) {
            val t = TerminalUtils.currentTerm ?: return@on
            val key = input.key()
            val e = Terminals.eRightClick && key == InputConstants.KEY_E
            if (Terminals.oneClickPerPress && (e || key == dropKey()) && !held.add(key)) return@on cancel()
            if (!e) return@on
            val screen = screen as? AbstractContainerScreen<*> ?: return@on cancel()
            val slot = TermSolver.slotUnderMouse(screen, t)
            if (slot >= 0) t.click(slot, RIGHT)
            cancel()
        }

        // Held keys repeat their press: one counts until the key is let go.
        on<ScreenEvent.KeyRelease> { held.remove(input.key()) }
        // A key let go with no screen open sends no release.
        on<TickEvent.End> { if (held.isNotEmpty()) held.removeIf { !InputConstants.isKeyDown(it) } }

        SubscribeWhile.add(this) { Terminals.enabled && (Terminals.eRightClick || Terminals.oneClickPerPress) }
    }
}
