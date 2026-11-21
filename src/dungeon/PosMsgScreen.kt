package com.engineerclient.dungeon

import com.adm.AdmMod.mc
import com.engineerclient.dungeon.PositionalMessages.PosMsg
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class PosMsgScreen(private val previous: Screen?) : Screen(Component.literal("Positional Messages")) {
    private val list get() = PositionalMessages.messages
    private var scroll = 0
    private var scrollLeft = 0.0

    private val winW get() = min(620, width - 20)
    private val winH get() = min(300, height - 20)
    private val winX get() = (width - winW) / 2
    private val winY get() = (height - winH) / 2
    private val listX get() = winX + 10
    private val listY get() = winY + 38
    private val listW get() = winW - 30
    private val listH get() = winH - 74
    private val visible get() = max(1, listH / ROW_H)
    private val msgW get() = listW - POS_W - DELAY_W - RAD_W - COLOR_W - HERE_W - DEL_W - GAP * 7

    override fun init() {
        scroll = scroll.coerceIn(0, max(0, list.size - visible))
        for (i in scroll until min(list.size, scroll + visible)) {
            val m = list[i]
            val y = listY + (i - scroll) * ROW_H + 4
            var x = listX + GAP
            fun box(w: Int, value: String, hint: String, max: Int, valid: (String) -> Boolean, set: (String) -> Unit) {
                val b = EditBox(font, x, y, w, 16, Component.literal(hint))
                b.setMaxLength(max)
                b.value = value
                b.setHint(Component.literal("§8$hint"))
                b.setTextColor(if (valid(value)) -1 else BAD)
                b.setResponder { t -> val ok = valid(t); b.setTextColor(if (ok) -1 else BAD); if (ok) set(t) }
                addRenderableWidget(b)
                x += w + GAP
            }
            box(msgW, m.message, "Message", 256, { true }) { m.message = it }
            box(POS_W, posText(m), "x y z or x y z x2 y2 z2", 120, { parsePos(it) != null }) { t ->
                val p = parsePos(t)!!
                m.x = p[0]; m.y = p[1]; m.z = p[2]
                if (p.size == 6) { m.x2 = p[3]; m.y2 = p[4]; m.z2 = p[5] } else { m.x2 = null; m.y2 = null; m.z2 = null }
            }
            box(DELAY_W, m.delay.toString(), "ms", 6, { it.toLongOrNull()?.let { d -> d >= 0 } == true }) { m.delay = it.toLong() }
            box(RAD_W, m.distance?.let(::num) ?: "", "radius", 6, { it.isEmpty() || it.toDoubleOrNull()?.let { d -> d > 0 } == true }) { m.distance = it.toDoubleOrNull() }
            box(COLOR_W, m.color, "color", 16, { PositionalMessages.parseColor(it) != null }) { m.color = it }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val x = winX
        val y = winY
        val w = winW
        val h = winH
        graphics.fill(x, y, x + w, y + h, 0xF0141414.toInt())
        graphics.fill(x, y, x + w, y + 2, ACCENT)
        graphics.centeredText(font, "§lPositional Messages", x + w / 2, y + 8, -1)

        var hx = listX + GAP
        for ((label, cw) in listOf("Message" to msgW, "Position" to POS_W, "Delay" to DELAY_W, "Radius" to RAD_W, "Color" to COLOR_W)) {
            graphics.text(font, label, hx + 2, listY - 11, 0xFF808080.toInt())
            hx += cw + GAP
        }

        if (list.isEmpty()) graphics.centeredText(font, "§7No messages yet. Add one at where you stand.", x + w / 2, listY + listH / 2 - 4, -1)
        for (i in scroll until min(list.size, scroll + visible)) {
            val m = list[i]
            val ry = listY + (i - scroll) * ROW_H
            if (mouseX in listX..listX + listW && mouseY in ry until ry + ROW_H) graphics.fill(listX, ry, listX + listW, ry + ROW_H, 0x0FFFFFFF)
            val cx = colorX - 1
            PositionalMessages.parseColor(m.color)?.let { graphics.fill(cx - 7, ry + 8, cx - 1, ry + 14, it) }
            button(graphics, "Here", hereX, ry + 4, HERE_W, mouseX, mouseY)
            button(graphics, "§cX", delX, ry + 4, DEL_W, mouseX, mouseY)
        }

        if (list.size > visible) {
            val barX = x + w - 14
            val thumbH = max(20, listH * visible / list.size)
            val thumbY = listY + (listH - thumbH) * scroll / (list.size - visible)
            graphics.fill(barX, listY, barX + 5, listY + listH, 0x18FFFFFF)
            graphics.fill(barX, thumbY, barX + 5, thumbY + thumbH, ACCENT)
        }

        button(graphics, "+ Add Here", addX, bottomY, ADD_W, mouseX, mouseY)
        button(graphics, "+ Add Box Here", addBoxX, bottomY, ADD_W, mouseX, mouseY)
        graphics.text(font, "§8/posmsg add, list, remove, clear, colors", listX, bottomY + 4, -1)

        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    private val colorX get() = listX + GAP + (msgW + GAP) + (POS_W + GAP) + (DELAY_W + GAP) + (RAD_W + GAP)
    private val hereX get() = colorX + COLOR_W + GAP
    private val delX get() = hereX + HERE_W + GAP
    private val bottomY get() = winY + winH - 26
    private val addBoxX get() = winX + winW - 14 - ADD_W
    private val addX get() = addBoxX - GAP - ADD_W

    private fun button(graphics: GuiGraphicsExtractor, label: String, bx: Int, by: Int, bw: Int, mouseX: Int, mouseY: Int) {
        val over = mouseX in bx..bx + bw && mouseY in by..by + 16
        graphics.fill(bx, by, bx + bw, by + 16, if (over) 0x6455AAFF else 0x12FFFFFF)
        graphics.centeredText(font, label, bx + bw / 2, by + 4, if (over) -1 else 0xFFC0C0C0.toInt())
    }

    private fun hit(mx: Int, my: Int, bx: Int, by: Int, bw: Int) = mx in bx..bx + bw && my in by..by + 16

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val player = mc.player
        if (player != null && (hit(mx, my, addX, bottomY, ADD_W) || hit(mx, my, addBoxX, bottomY, ADD_W))) {
            val x = round(player.x); val y = round(player.y); val z = round(player.z)
            list.add(if (hit(mx, my, addBoxX, bottomY, ADD_W)) PosMsg(x - 1, y, z - 1, x + 1, y + 2, z + 1, distance = null) else PosMsg(x, y, z))
            scroll = max(0, list.size - visible)
            PositionalMessages.changed(); click(); rebuildWidgets()
            return true
        }
        for (i in scroll until min(list.size, scroll + visible)) {
            val ry = listY + (i - scroll) * ROW_H + 4
            if (hit(mx, my, delX, ry, DEL_W)) {
                list.removeAt(i)
                PositionalMessages.changed(); click(); rebuildWidgets()
                return true
            }
            if (player != null && hit(mx, my, hereX, ry, HERE_W)) {
                val m = list[i]
                val x = round(player.x); val y = round(player.y); val z = round(player.z)
                if (m.isBox) {
                    val dx = m.x2!! - m.x; val dy = m.y2!! - m.y; val dz = m.z2!! - m.z
                    m.x = x - dx / 2; m.y = y; m.z = z - dz / 2; m.x2 = m.x + dx; m.y2 = y + dy; m.z2 = m.z + dz
                } else { m.x = x; m.y = y; m.z = z }
                PositionalMessages.changed(); click(); rebuildWidgets()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        // Touchpads and smooth wheels send fractions of a row: they add up.
        scrollLeft += vertical
        val steps = scrollLeft.toInt()
        scrollLeft -= steps
        val to = (scroll - steps).coerceIn(0, max(0, list.size - visible))
        if (to != scroll) { scroll = to; rebuildWidgets() }
        return true
    }

    private fun click() = mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))

    override fun isPauseScreen() = false

    override fun onClose() {
        PositionalMessages.changed()
        mc.gui.setScreen(previous)
    }

    companion object {
        private const val ROW_H = 24
        private const val GAP = 4
        private const val POS_W = 150
        private const val DELAY_W = 44
        private const val RAD_W = 40
        private const val COLOR_W = 70
        private const val HERE_W = 32
        private const val DEL_W = 16
        private const val ADD_W = 90
        private const val ACCENT = 0xFF55AAFF.toInt()
        private const val BAD = 0xFFFF5555.toInt()

        fun round(v: Double) = Math.round(v * 100) / 100.0
        fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

        fun posText(m: PosMsg) =
            if (m.isBox) listOf(m.x, m.y, m.z, m.x2!!, m.y2!!, m.z2!!).joinToString(" ", transform = ::num)
            else listOf(m.x, m.y, m.z).joinToString(" ", transform = ::num)

        fun parsePos(text: String): List<Double>? {
            val parts = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (parts.size != 3 && parts.size != 6) return null
            return parts.map { it.toDoubleOrNull() ?: return null }
        }
    }
}
