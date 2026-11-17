package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.adr.ADR
import com.engineerclient.terminals.sim.TermSimGUI
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * One open terminal. What the server last said ([keys], a small number per slot) plus the clicks it hasn't answered yet
 * ([pending]) make what's shown. A click is answered when its slot turns into what that click makes it; a slot that turns
 * into anything else drops the clicks waiting on it, and a click answered after one still waiting means that one was
 * dropped. Nothing answered within the timeout is undone.
 */
abstract class TerminalHandler(val type: TerminalTypes) {
    val timeOpened = System.currentTimeMillis()
    var ticksOpened = 0
    /** Clicks left, in order (a Rubix slot once per click); read by party sharing and the recorder. */
    val solution = ArrayList<Int>()
    /** Clicks sent and not yet answered: slot and container button. */
    val clickedSlots = ArrayList<Pair<Int, Int>>()
    var lastClickTime = 0L
    var ready = false
        private set

    val area = type.windowSize - 9
    protected val items: Array<ItemStack> = Array(area) { ItemStack.EMPTY }
    protected val keys = IntArray(area) { NONE }
    /** Keys with the pending clicks played on top: what's drawn and what clicks are checked against. */
    protected val shown = IntArray(area) { NONE }
    /** Slots the server took a click on, for terminals whose items don't say so. */
    protected val done = BooleanArray(area)

    private class Pending(val slot: Int, val button: Int, val at: Long, val seq: Int)
    private val pending = ArrayList<Pending>()
    private var seq = 0
    private var filling = false
    /** The slot clicked last, -1 before any. */
    protected var lastClicked = -1
    private val received = BooleanArray(area)
    private var lastSlots: List<Slot>? = null

    /** Content bounds, for the custom grid. */
    var top = type.layout[0]; var left = type.layout[1]; var rows = type.layout[2]; var cols = type.layout[3]
        private set

    /** Instant GUI: a terminal whose slots don't depend on each other is shown and clicked slot by slot as they arrive. */
    val early get() = !ready && type.orderFree && Terminals.instantGui && Terminals.style == Terminals.Style.CUSTOM

    protected abstract fun key(item: ItemStack): Int
    /** The key a slot has after a click on it. */
    protected open fun next(key: Int, button: Int): Int = NONE
    /** Select and Starts With: any update to a clicked slot answers it (their items don't always change visibly). */
    protected open val anyUpdateAnswers = false
    protected abstract fun canClickShown(slot: Int, button: Int): Boolean
    protected abstract fun buildSolution(out: ArrayList<Int>)
    protected open fun onReady() {}

    /** Server items for [only] (a single set slot) or the whole window (-1). Game thread. */
    fun update(slots: List<Slot>, only: Int) {
        if (slots.size < area) return
        val range = if (only >= 0) only..only else 0 until area
        val before = pending.size
        var answeredSeq = -1
        for (i in range) {
            if (i !in 0 until area) continue
            val item = slots[i].item
            val k = key(item)
            val changed = k != keys[i] || !ItemStack.matches(item, items[i])
            if (changed || only >= 0) answeredSeq = maxOf(answeredSeq, answer(i, keys[i], k, only >= 0 || changed))
            items[i] = item
            keys[i] = k
        }
        if (answeredSeq >= 0) pending.removeAll { it.seq < answeredSeq }
        if (!ready) {
            // Only what was sent to this terminal counts: a reused window still holds the last one's items.
            if (only < 0) received.fill(true) else if (only in 0 until area) received[only] = true
            lastSlots = slots
            if (received.all { it }) becomeReady() else filling = true
        }
        if (pending.size != before || ready || early) recompute()
    }

    /** The answer [newKey] for slot [i]: returns the latest answered click's seq, or -1. */
    private fun answer(i: Int, oldKey: Int, newKey: Int, updated: Boolean): Int {
        if (pending.isEmpty()) return -1
        val mine = pending.filter { it.slot == i }
        if (mine.isEmpty()) return -1
        if (anyUpdateAnswers) {
            if (!updated) return -1
            val p = mine.first()
            pending.remove(p)
            done[i] = true
            ClickRtt.sample(System.nanoTime() - p.at)
            return p.seq
        }
        var k = oldKey
        for ((j, p) in mine.withIndex()) {
            k = next(k, p.button)
            if (k != newKey) continue
            for (n in 0..j) pending.remove(mine[n])
            ClickRtt.sample(System.nanoTime() - p.at)
            return p.seq
        }
        if (newKey != oldKey) pending.removeAll(mine)
        return -1
    }

    private fun becomeReady() {
        ready = true
        filling = false
        var minR = 9; var maxR = -1; var minC = 9; var maxC = -1
        for (i in 0 until area) {
            if (!isContent(items[i])) continue
            minR = minOf(minR, i / 9); maxR = maxOf(maxR, i / 9); minC = minOf(minC, i % 9); maxC = maxOf(maxC, i % 9)
        }
        if (maxR >= 0) { top = minR; left = minC; rows = maxR - minR + 1; cols = maxC - minC + 1 }
        onReady()
    }

    protected open fun isContent(item: ItemStack) = !item.isEmpty && !item.`is`(BLACK_PANE)

    /** Each client tick: a window filled slot by slot is complete once a tick passes; unanswered clicks time out. */
    fun tick() {
        if (filling && !ready) lastSlots?.let { update(it, -1) }
        if (pending.isEmpty()) return
        val limit = ClickRtt.timeoutNs()
        val now = System.nanoTime()
        if (pending.removeAll { now - it.at > limit }) recompute()
    }

    protected fun recompute() {
        for (i in 0 until area) shown[i] = if (done[i]) NONE else keys[i]
        for (p in pending) shown[p.slot] = next(shown[p.slot], p.button)
        solution.clear()
        if (ready) buildSolution(solution)
        clickedSlots.clear()
        for (p in pending) clickedSlots.add(p.slot to containerButton(p.button))
    }

    fun canClick(slot: Int, button: Int) = slot in 0 until area && (ready || (early && received[slot])) && canClickShown(slot, button)

    /**
     * A click as the player made it ([LEFT] or [RIGHT]); only one the solver asks for goes out. True if it was sent.
     */
    fun click(slot: Int, button: Int): Boolean {
        if (!canClick(slot, button)) return false
        val sim = mc.gui.screen() is TermSimGUI
        if (!sim && ClickLimit.blocks()) return false
        val sent = containerButton(button)
        predict(slot, button)
        TerminalEvent.Click(this, slot, sent, solution).postAndCatch()
        send(slot, sent)
        return true
    }

    /** The click shown as done until the server answers it. */
    internal fun predict(slot: Int, button: Int) {
        lastClicked = slot
        if (type != TerminalTypes.MELODY) pending += Pending(slot, button, System.nanoTime(), seq++)
        lastClickTime = System.currentTimeMillis()
        recompute()
    }

    private fun containerButton(button: Int) = if (type == TerminalTypes.RUBIX && button == RIGHT) RIGHT else MIDDLE

    private fun send(slot: Int, button: Int) {
        val screen = mc.gui.screen()
        if (screen is TermSimGUI) return screen.clickIndex(slot, button)
        val menu = mc.player?.containerMenu ?: return
        val input = if (button == MIDDLE) ContainerInput.CLONE else ContainerInput.PICKUP
        ADR.onSlotClick(slot, button, input.name)
        // Sent as is: the vanilla path would move the pane onto the cursor first for a right click.
        mc.connection?.send(ServerboundContainerClickPacket(menu.containerId, menu.stateId, slot.toShort(), button.toByte(), input, Int2ObjectOpenHashMap(), HashedStack.EMPTY))
    }

    /** What the solver draws on [slot]: a color key and text, or null for nothing. */
    abstract fun look(slot: Int): Look?

    class Look(val kind: Int, val text: String? = null)

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val MIDDLE = 2
        const val NONE = -1
        val BLACK_PANE = net.minecraft.world.item.Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.BLACK)

        const val PANE = 0; const val SELECT = 1; const val STARTS_WITH = 2
        const val RUBIX_1 = 3; const val RUBIX_2 = 4; const val RUBIX_M1 = 5; const val RUBIX_M2 = 6
        const val NUMBER_1 = 7; const val NUMBER_2 = 8; const val NUMBER_3 = 9; const val NUMBER_4 = 10
        const val MELODY_COLUMN = 11; const val MELODY_POINTER = 12; const val MELODY_LANE = 13

        private const val KINDS = MELODY_LANE + 2
        private const val NUMBER_OFFSET = 8
        private const val NUMBERS = 80
        // Looks are asked for per slot every frame, so the few there are get shared.
        private val plain = Array(KINDS) { Look(it - 1) }
        private val numbered = arrayOfNulls<Look>(KINDS * NUMBERS)

        fun cached(kind: Int): Look = plain[kind + 1]

        fun cached(kind: Int, n: Int): Look {
            val i = (kind + 1) * NUMBERS + n + NUMBER_OFFSET
            if (kind + 1 !in 0 until KINDS || n + NUMBER_OFFSET !in 0 until NUMBERS) return Look(kind, n.toString())
            return numbered[i] ?: Look(kind, n.toString()).also { numbered[i] = it }
        }
    }
}

/** Click to answer times, for the timeout when Resync Timeout is 0. */
object ClickRtt {
    private val samples = LongArray(8)
    private var count = 0
    private var median = 0L

    fun sample(ns: Long) {
        samples[count++ % samples.size] = ns
        val n = minOf(count, samples.size)
        val sorted = samples.copyOf(n).also { it.sort() }
        median = sorted[n / 2]
    }

    fun timeoutNs(): Long {
        val fixed = Terminals.resyncTimeout
        if (fixed > 0) return fixed * 1_000_000L
        if (count == 0) return 600_000_000L
        return (median * 2 + 100_000_000L).coerceIn(250_000_000L, 1_000_000_000L)
    }
}
