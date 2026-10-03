package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.engineerclient.mixin.RecContainerScreenAccessor
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack

/**
 * Screens as you saw and used them, for the Dungeon Recorder. The packets say what the server put
 * in a container; this says what was on screen: which screen opened and how it was laid out (every
 * slot's position, so a reader can redraw it), each slot's item whenever it changes (the client's
 * prediction included, which the packets never show), the item on the cursor, the slot under the
 * mouse, a drag in progress, the tooltip exactly as drawn after every mod had its say, and where the
 * mouse was every frame.
 *
 * Lines: screen (open/close), slots, carried, hover, drag, tooltip, gmouse. All on the game thread;
 * every stack is frozen with [RichJson.itemNow] before it is queued.
 *
 * Not recorded: the menu's "remote" (server-confirmed) slot copies. On the client those are never
 * filled (only the server's synchronizer writes them), so they would say nothing; what the server
 * confirmed is in the container_set_content / container_set_slot packets instead.
 */
object ScreenCapture {

    private var installed = false

    /** The screen the open/slot lines are about, and the diff state for it. */
    private var current: Screen? = null
    private var lastSession: RecorderSession? = null
    /** Copies of what each slot (and the cursor) held when last written; null = not written yet. */
    private var slotCopies: Array<ItemStack?> = emptyArray()
    private var carriedCopy: ItemStack? = null
    private var lastHover: Int? = -2
    private var lastDrag = ""

    /** Mouse positions drawn this tick: [ns, x, y] triples, written once per tick as one gmouse line. */
    private val mouse = StringBuilder()
    private var mouseCount = 0

    /** The last tooltip written (its lines' hash) and the tick it was last drawn on. */
    private var tooltipHash = 0
    private var tooltipTick = -10

    fun install() {
        if (installed) return
        installed = true
        ScreenEvents.AFTER_INIT.register { _, screen, w, h ->
            if (!Rec.active) return@register
            EngineerClient.safely("recorder screen") { opened(screen, w, h) }
            // Fabric makes a screen's own events afresh on every init (resizes too), so these never pile up.
            EngineerClient.safely("recorder screen hooks") {
                ScreenEvents.remove(screen).register { s -> if (Rec.active) EngineerClient.safely("recorder screen close") { closed(s) } }
                ScreenEvents.afterExtract(screen).register { s, _, mx, my, _ ->
                    if (Rec.active && s === current) {
                        try {
                            if (mouseCount > 0) mouse.append(',')
                            mouse.append('[').append(Rec.nowNs()).append(',').append(mx).append(',').append(my).append(']')
                            mouseCount++
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
        on<TickEvent.End> { if (Rec.active) EngineerClient.safely("recorder screens") { tick() } }
        Rec.onKeyframe("screen") { keyframe() }
        EventBus.subscribe(this)
    }

    // ------------------------------------------------------------------ open / close

    private fun opened(screen: Screen, w: Int, h: Int) {
        val reinit = screen === current
        current = screen
        if (!reinit) resetDiff(screen)
        Rec.emit("screen", openBody(screen, w, h, reinit))
        // A thumbnail of the frame that first shows it (only when Frame Thumbnails is on).
        if (!reinit) ThumbCapture.request("screen")
    }

    private fun closed(screen: Screen) {
        Rec.emit("screen", "\"open\":false,\"class\":${RecorderFiles.q(screen.javaClass.name)}")
        if (screen === current) { flushMouse(); current = null; resetDiff(null) }
    }

    private fun resetDiff(screen: Screen?) {
        val n = (screen as? AbstractContainerScreen<*>)?.menu?.slots?.size ?: 0
        slotCopies = arrayOfNulls(n)
        carriedCopy = null
        lastHover = -2
        lastDrag = ""
        tooltipHash = 0
    }

    private fun openBody(screen: Screen, w: Int, h: Int, reinit: Boolean): String {
        val mc = EngineerClient.mc
        val sb = StringBuilder(1024)
        sb.append("\"open\":true,\"class\":").append(RecorderFiles.q(screen.javaClass.name))
        if (reinit) sb.append(",\"reinit\":true")
        sb.append(",\"title\":"); RichJson.component(sb, screen.title)
        sb.append(",\"w\":").append(w).append(",\"h\":").append(h)
        val win = mc.window
        sb.append(",\"gw\":").append(win.guiScaledWidth).append(",\"gh\":").append(win.guiScaledHeight).append(",\"scale\":").append(win.guiScale)
        if (screen is AbstractContainerScreen<*>) {
            val menu = screen.menu
            sb.append(",\"menu\":").append(RecorderFiles.q(menuKey(menu)))
            sb.append(",\"cid\":").append(menu.containerId).append(",\"state\":").append(menu.stateId)
            val acc = screen as RecContainerScreenAccessor
            RichJson.member(sb, "left") { sb.append(acc.`ec$recLeftPos`()); true }
            RichJson.member(sb, "top") { sb.append(acc.`ec$recTopPos`()); true }
            RichJson.member(sb, "iw") { sb.append(acc.`ec$recImageWidth`()); true }
            RichJson.member(sb, "ih") { sb.append(acc.`ec$recImageHeight`()); true }
            sb.append(",\"slots\":[")
            menu.slots.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append('[').append(s.index).append(',').append(s.x).append(',').append(s.y).append(',')
                    .append(s.containerSlot).append(',').append(RecorderFiles.q(s.container.javaClass.name)).append(',').append(s.isActive).append(']')
            }
            sb.append(']')
        }
        return sb.toString()
    }

    /** The menu's registry id; the player's own inventory has no menu type (its getType() throws). */
    private fun menuKey(menu: AbstractContainerMenu): String =
        if (menu is InventoryMenu) "inventory"
        else runCatching { BuiltInRegistries.MENU.getKey(menu.type)?.toString() }.getOrNull() ?: "?"

    // ------------------------------------------------------------------ per tick

    private fun tick() {
        val session = Rec.session
        if (session !== lastSession) { lastSession = session; resetDiff(current) }
        val screen = EngineerClient.mc.screen
        if (screen !== current) {
            // Opened or closed while no hook saw it (before the recording started, say): catch up now.
            current?.let { closed(it) }
            if (screen != null) { current = screen; resetDiff(screen); Rec.emit("screen", openBody(screen, screen.width, screen.height, false)) }
        }
        flushMouse()
        if (Rec.tick - tooltipTick > 1) tooltipHash = 0
        val cs = current as? AbstractContainerScreen<*> ?: return
        containerState(cs, full = false)
    }

    /** Slots, carried item, hovered slot and drag of an open container screen; [full] writes them all. */
    private fun containerState(cs: AbstractContainerScreen<*>, full: Boolean) {
        val menu = cs.menu
        val slots = menu.slots
        if (slotCopies.size != slots.size) slotCopies = arrayOfNulls(slots.size)
        val sb = StringBuilder()
        var n = 0
        for (i in slots.indices) {
            val stack = slots[i].item
            val before = slotCopies[i]
            if (!full && before != null && ItemStack.matches(stack, before)) continue
            slotCopies[i] = stack.copy()
            if (n++ > 0) sb.append(',')
            sb.append('[').append(i).append(',').append(RichJson.itemNow(stack)).append(']')
        }
        if (n > 0) Rec.emit("slots", kf(full) + "\"cid\":${menu.containerId},\"state\":${menu.stateId},\"s\":[$sb]")

        val carried = menu.carried
        val carriedBefore = carriedCopy
        if (full || carriedBefore == null || !ItemStack.matches(carried, carriedBefore)) {
            carriedCopy = carried.copy()
            Rec.emit("carried", kf(full) + "\"item\":${RichJson.itemNow(carried)}")
        }

        val acc = cs as RecContainerScreenAccessor
        val hover = acc.`ec$recHoveredSlot`()?.index
        if (full || hover != lastHover) {
            lastHover = hover
            Rec.emit("hover", kf(full) + "\"slot\":${hover ?: "null"}")
        }

        val dragging = acc.`ec$recIsQuickCrafting`()
        val drag = if (!dragging) "" else acc.`ec$recQuickCraftSlots`().map { it.index }.sorted().joinToString(",")
        val dragKey = if (dragging) "on:$drag" else ""
        if (full || dragKey != lastDrag) {
            lastDrag = dragKey
            Rec.emit("drag", kf(full) + "\"on\":$dragging,\"slots\":[$drag]")
        }
    }

    private fun kf(full: Boolean) = if (full) "\"kf\":${Rec.keyframeId}," else ""

    private fun flushMouse() {
        if (mouseCount == 0) return
        val d = mouse.toString()
        mouse.setLength(0); mouseCount = 0
        Rec.emit("gmouse", "\"d\":[$d]")
    }

    private fun keyframe() {
        val screen = current ?: EngineerClient.mc.screen ?: return
        current = screen
        Rec.emit("screen", kf(true) + openBody(screen, screen.width, screen.height, false))
        (screen as? AbstractContainerScreen<*>)?.let { containerState(it, full = true) }
    }

    // ------------------------------------------------------------------ tooltip

    /**
     * A tooltip about to be drawn (TooltipTapMixin), every frame it is up. Written when its lines
     * change or it comes back after a tick without one, with the slot under the mouse.
     */
    fun tooltip(lines: List<Component>, x: Int, y: Int) = tooltip(lines, x, y, seq = false)

    /**
     * A tooltip drawn from FormattedCharSequence lines (widget and button tooltips, single-Component
     * and mods' tooltips): turned back into styled lines (runs of one style each), written as
     * [tooltip] with "seq":true.
     */
    fun tooltipSeq(lines: List<FormattedCharSequence>, x: Int, y: Int) {
        if (!Rec.active) return
        tooltip(lines.map { seqToComponent(it) }, x, y, seq = true)
    }

    fun seqToComponent(s: FormattedCharSequence): Component {
        val out = Component.empty()
        val run = StringBuilder()
        var style: Style? = null
        s.accept { _, st, cp ->
            if (st != style && run.isNotEmpty()) { out.append(Component.literal(run.toString()).withStyle(style!!)); run.setLength(0) }
            style = st
            run.appendCodePoint(cp)
            true
        }
        if (run.isNotEmpty()) out.append(Component.literal(run.toString()).withStyle(style!!))
        return out
    }

    private fun tooltip(lines: List<Component>, x: Int, y: Int, seq: Boolean) {
        if (!Rec.active) return
        val hash = 31 * lines.hashCode() + lines.size
        val again = hash == tooltipHash && Rec.tick - tooltipTick <= 1
        tooltipTick = Rec.tick
        if (again) return
        tooltipHash = hash
        val slot = (EngineerClient.mc.screen as? RecContainerScreenAccessor)?.let { runCatching { it.`ec$recHoveredSlot`()?.index }.getOrNull() }
        val sb = StringBuilder(256)
        sb.append("\"slot\":").append(slot ?: "null").append(",\"x\":").append(x).append(",\"y\":").append(y).append(",\"lines\":[")
        lines.forEachIndexed { i, c -> if (i > 0) sb.append(','); RichJson.component(sb, c) }
        sb.append(']')
        if (seq) sb.append(",\"seq\":true")
        Rec.emit("tooltip", sb.toString())
    }
}
