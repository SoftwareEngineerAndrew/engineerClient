package com.coffeeclient.misc

import com.engineerclient.practice.TerminalsStatusLook
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import com.odtheking.odin.utils.skyblock.dungeon.terminals.TerminalUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.lwjgl.sdl.SDLMouse

/**
 * Random Stuff's Force Cursor Reset, and Term Info's (Odin's Terminals Status) for terminals only. Opening a menu
 * warps the cursor to the middle of the window while it is still captured, then lets it go
 * (InputConstants.releaseMouse); under Xwayland that warp is sometimes lost and the cursor shows up wherever it was.
 * Xwayland always honours a warp made while the cursor is hidden, so the warp is redone that way straight after the
 * release and once more on the next tick, unless a menu is no longer open by then. Term Info's only warps while a
 * terminal is open (Odin's open terminal, or its term sim); the next-tick warp also covers a terminal Odin only
 * picks up after the screen is set.
 */
object CursorReset {
    private var retry = false

    /** Called by CursorResetMixin after MouseHandler.releaseMouse let a captured cursor go. */
    @JvmStatic
    fun released() {
        if (!RandomStuff.forcesCursorReset() && !TerminalsStatusLook.forcesCursorReset()) return
        if (wanted()) warp()
        retry = true
    }

    private fun wanted(): Boolean =
        RandomStuff.forcesCursorReset() || (TerminalsStatusLook.forcesCursorReset() && inTerminal())

    private fun inTerminal(): Boolean =
        TerminalUtils.currentTerm != null || Minecraft.getInstance().gui.screen() is TermSimGUI

    private fun warp() {
        val mc = Minecraft.getInstance()
        if (mc.mouseHandler.isMouseGrabbed) return
        val window = mc.window
        SDLMouse.SDL_HideCursor()
        SDLMouse.SDL_WarpMouseInWindow(window.handle(), window.screenWidth / 2f, window.screenHeight / 2f)
        SDLMouse.SDL_ShowCursor()
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!retry) return@register
            retry = false
            if (wanted()) warp()
        }
    }
}
