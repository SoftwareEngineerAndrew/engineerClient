// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.terminals

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.terminals.TerminalSolverSettings.RenderType
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * ADS's terminal solver: Odin's Terminal Solver and the terminal tracking under it (its TerminalUtils), on vanilla
 * hooks instead of Odin's events (TerminalSolverScreenMixin and friends call in here). Only in ADS's worlds, the P3 Sim
 * and Rustclear's engine, whose terminals are real chest windows with Hypixel's titles; anywhere else it never opens
 * a terminal, so Hypixel is Odin's (if installed) alone.
 *
 * With Odin installed, Odin still tracks the same windows (its terminal times, Random Stuff and the like read that),
 * but its solver stands aside while this one has a terminal open ([odinGuard], set by the Odin addon), so a window
 * is never highlighted twice. Odin's own windows (its /termsim, ADS's /termsim inf on it) are left to Odin.
 *
 * Client thread only, but [onSetItem] (the integrated server's menus run through the same method).
 */
object AdsTerminalSolver {
    private val mc: Minecraft get() = Minecraft.getInstance()

    /** The terminal open now, solved by ADS; null in any other window, or outside ADS's worlds. */
    @JvmStatic
    var currentTerm: TerminalHandler? = null
        private set

    /** Told whether ADS has a terminal open, on every change: Odin's solver stands aside meanwhile (OdinAddon). */
    var odinGuard: ((Boolean) -> Unit)? = null

    /** More reasons not to draw the 1-10 (Odin's Random Stuff, Hide Terminal Numbers), set by the Odin addon. */
    var hideNumbers: () -> Boolean = { false }

    /** Told of every click the solver sends (slot, button, the solution left), for Odin's Terminal Sounds. */
    var onClick: ((Int, Int, List<Int>) -> Unit)? = null

    private val settings = TerminalSolverSettings

    fun init() {
        settings.init()
        ClientTickEvents.END_CLIENT_TICK.register { EngineerClient.safely("terminal solver tick") { tick() } }
    }

    /** In one of ADS's worlds with the solver on. */
    fun active(): Boolean = settings.enabled.value && (P3Sim.inSim || runCatching { net.rustclear.mod.engine.Engine.isEngineClient() }.getOrDefault(false))

    fun showsNumbers(): Boolean = settings.showNumbers.value && !hideNumbers()

    private val renderMelody: Boolean
        get() = !(settings.cancelMelodySolver.value && currentTerm?.type == TerminalType.MELODY)

    /** The custom GUI draws (and takes the clicks of) the open terminal instead of the vanilla window. */
    @JvmStatic
    fun customGuiActive(): Boolean = currentTerm != null && settings.renderType.value == RenderType.CUSTOM_GUI && renderMelody

    // ------------------------------------------------------------------ the window opening and closing

    /** Gui.setScreen just put [screen] up (null: closed). */
    @JvmStatic
    fun onScreenSet(screen: Screen?) {
        val was = currentTerm
        val now = if (screen is AbstractContainerScreen<*> && active() && !isOdinScreen(screen)) TerminalType.openHandler(screen.title.string) else null
        if (was == null && now == null) return
        currentTerm = now
        if (now != null) EngineerClient.logger.debug("[ads] terminal solver: {} opened", now.type)
        EngineerClient.safely("terminal solver odin guard") { odinGuard?.invoke(now != null) }
        // Odin's render types draw the window at their own GUI scale (Normal Term Size).
        if (settings.renderType.value != RenderType.CUSTOM_GUI) mc.resizeGui()
    }

    /** A screen of Odin's own (its terminal simulator, and ADS's screens built on it): Odin solves those. */
    private fun isOdinScreen(screen: Screen): Boolean =
        generateSequence<Class<*>>(screen.javaClass) { it.superclass }.any { it.name.startsWith("com.odtheking.") }

    // ------------------------------------------------------------------ the server's updates

    /** [menu] got [stack] in [slot] (AbstractContainerMenu.setItem: the server's per-slot updates). */
    @JvmStatic
    fun onSetItem(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
        if (!mc.isSameThread) return
        val term = currentTerm ?: return
        if (menu !== (mc.gui.screen() as? AbstractContainerScreen<*>)?.menu) return
        term.updateSlot(slot, stack, menu.slots)
    }

    private fun tick() {
        val term = currentTerm ?: return
        // A predicted click the server never answered: forget the predictions and solve from what the window shows.
        if (term.clickedSlots.isNotEmpty() && System.currentTimeMillis() - term.lastClickTime >= settings.terminalReloadThreshold.value) {
            term.clickedSlots.clear()
            val menu = (mc.gui.screen() as? AbstractContainerScreen<*>)?.menu ?: return
            term.updateSlot(0, ItemStack.EMPTY, menu.slots)
        }
    }

    // ------------------------------------------------------------------ the vanilla window (Odin and Normal)

    fun clicked(slot: Int, button: Int, solution: List<Int>) {
        EngineerClient.safely("terminal solver click hook") { onClick?.invoke(slot, button, solution) }
    }

    /** A click on a slot of the window: the solver's (sent on only if it's right), never vanilla's. True: cancel it. */
    @JvmStatic
    fun onSlotClick(slotId: Int, button: Int): Boolean {
        val term = currentTerm ?: return false
        term.click(slotId, button, settings.clickPrediction.value)
        return true
    }

    /** Before the window's slots: Odin's render type darkens the window behind them. */
    @JvmStatic
    fun renderBackground(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, left: Int, top: Int, width: Int, height: Int) {
        if (currentTerm == null || !renderMelody || settings.renderType.value != RenderType.ODIN) return
        g.fill(left + 7, top + 16, left + width - 7, top + height - 96, settings.background)
    }

    /** One slot: the solver's highlight (and number) on it. True: vanilla doesn't draw the slot. */
    @JvmStatic
    fun renderSlot(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, slot: Slot): Boolean {
        if (!renderMelody) return false
        val term = currentTerm ?: return false
        if (slot.index > term.type.windowSize - 1) return false
        val look = term.getSlotRendering(slot.index)
        if (look != null) {
            g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, look.color)
            look.text?.let {
                val color = if (term.type == TerminalType.NUMBERS) settings.numbersText.value.argb else settings.rubixText.value.argb
                g.centeredText(screen.font, it, slot.x + 8, slot.y + 4, color)
            }
            return true
        }
        // Odin's render type: only the slots to click are drawn at all.
        return settings.renderType.value == RenderType.ODIN
    }

    /** No item tooltips over a terminal. */
    @JvmStatic
    fun hidesTooltip(): Boolean = currentTerm != null

    /** Show Numbers off: the stack counts (the 1-10 themselves) aren't drawn on Click in Order's slots either. */
    @JvmStatic
    fun hidesCount(slot: Slot): Boolean {
        val term = currentTerm ?: return false
        return term.type == TerminalType.NUMBERS && slot.index < term.type.windowSize && !showsNumbers()
    }

    /** The GUI scale while a terminal is open (Normal Term Size; 6 is auto), or null to leave the option's. */
    @JvmStatic
    fun guiScale(): Int? {
        if (currentTerm == null || settings.renderType.value == RenderType.CUSTOM_GUI) return null
        val size = settings.normalTermSize.value
        return if (size == 6) Options.AUTO_GUI_SCALE else size
    }
}
