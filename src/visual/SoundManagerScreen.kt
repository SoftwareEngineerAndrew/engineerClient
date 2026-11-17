package com.engineerclient.visual

import com.adm.AdmMod.mc
import com.adm.features.ModuleManager
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import kotlin.math.max
import kotlin.math.min

class SoundManagerScreen(private val previous: Screen?) : Screen(Component.literal("Sound Manager")) {
    private enum class Tab(val label: String) {
        All("All"), Recent("Recent"), Blocks("Blocks"), HostileMobs("Hostile Mobs"), NeutralMobs("Neutral Mobs"),
        Music("Music"), Ambient("Ambient"), Items("Items"), UI("UI"), Misc("Misc");

        val heading = "§l$label"
    }

    private class Entry(val id: String, val label: String, val tab: Tab, val event: SoundEvent) {
        val haystack = "$id $label"
        var fitWidth = -1
        var fitted = label
    }

    // A header's sounds are the rows after it up to [end].
    private class Heading(val text: String) {
        var end = 0
    }

    private val rows = ArrayList<Any>()
    private var tab = Tab.All
    private var shownRecent = -1L
    private var scroll = 0
    private var dragging: Entry? = null
    private lateinit var search: EditBox

    private val winW get() = min(540, width - 20)
    private val winH get() = min(285, height - 20)
    private val winX get() = (width - winW) / 2
    private val winY get() = (height - winH) / 2
    private val listX get() = winX + SIDEBAR + 10
    private val listY get() = winY + 26
    private val listW get() = winW - SIDEBAR - 34
    private val listH get() = winH - 62
    private val playX get() = listX + listW - PLAY_W - 8
    private val sliderX get() = playX - SLIDER_W - 8
    private val muteX get() = listX + listW - MUTE_W - 8

    override fun init() {
        search = EditBox(font, listX + listW / 2 - 100, winY + winH - 28, 200, 18, Component.literal("Search"))
        search.setHint(Component.literal("Search..."))
        search.setResponder { fill(true) }
        addRenderableWidget(search)
        fill(true)
    }

    private fun fill(toTop: Boolean) {
        val query = if (::search.isInitialized) search.value.lowercase() else ""
        rows.clear()
        if (tab == Tab.Recent) {
            val heading = Heading(Tab.Recent.heading)
            rows.add(heading)
            for (id in Sounds.getRecentSoundIds()) {
                val e = byId[id] ?: continue
                if (query.isEmpty() || e.haystack.contains(query)) rows.add(e)
            }
            if (rows.size == 1) rows.clear() else heading.end = rows.size
            shownRecent = Sounds.recentSoundsVersion
        } else {
            for (group in Tab.entries) {
                if (group == Tab.All || group == Tab.Recent || tab != Tab.All && tab != group) continue
                val heading = Heading(group.heading)
                val start = rows.size
                rows.add(heading)
                for (e in catalog) if (e.tab == group && (query.isEmpty() || e.haystack.contains(query))) rows.add(e)
                if (rows.size == start + 1) rows.removeAt(start) else heading.end = rows.size
            }
        }
        if (toTop) scroll = 0
    }

    private fun muted(at: Int): Boolean {
        val end = (rows[at] as Heading).end
        if (end <= at + 1) return false
        for (i in at + 1 until end) if (Sounds.getVolumePercent((rows[i] as Entry).id) != 0) return false
        return true
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (tab == Tab.Recent && shownRecent != Sounds.recentSoundsVersion) fill(false)

        val x = winX
        val y = winY
        val w = winW
        val h = winH
        graphics.fill(x, y, x + w, y + h, 0xF0141414.toInt())
        graphics.fill(x, y, x + SIDEBAR, y + h, 0xC80F0F0F.toInt())
        graphics.fill(x, y, x + w, y + 2, ACCENT)
        graphics.centeredText(font, "§lSound Manager", x + (w + SIDEBAR) / 2, y + 8, -1)

        for (t in Tab.entries) {
            val ty = y + 30 + t.ordinal * 20
            if (t == tab) graphics.fill(x, ty, x + SIDEBAR, ty + 20, 0x2855AAFF)
            else if (mouseX in x..x + SIDEBAR && mouseY in ty..ty + 20) graphics.fill(x, ty, x + SIDEBAR, ty + 20, 0x0AFFFFFF)
            graphics.text(font, t.label, x + 10, ty + 6, if (t == tab) ACCENT else 0xFFAAAAAA.toInt())
        }

        val lx = listX
        val ly = listY
        val lw = listW
        val lh = listH
        scroll = scroll.coerceIn(0, max(0, rows.size * ROW_H - lh))
        graphics.enableScissor(lx, ly, lx + lw, ly + lh)
        val first = max(0, scroll / ROW_H)
        val last = min(rows.size, first + lh / ROW_H + 2)
        for (i in first until last) {
            val ry = ly - scroll + i * ROW_H
            when (val row = rows[i]) {
                is Heading -> drawHeading(graphics, row, i, lx, ry, lw, mouseX, mouseY)
                is Entry -> drawEntry(graphics, row, lx, ry, lw, mouseX, mouseY)
            }
        }
        graphics.disableScissor()

        val total = rows.size * ROW_H
        if (total > lh) {
            val barX = x + w - 18
            val thumbH = max(28, lh * lh / total)
            val thumbY = ly + (lh - thumbH) * scroll / (total - lh)
            graphics.fill(barX, ly, barX + 6, ly + lh, 0x18FFFFFF)
            graphics.fill(barX, thumbY, barX + 6, thumbY + thumbH, ACCENT)
        }

        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    private fun drawHeading(graphics: GuiGraphicsExtractor, row: Heading, index: Int, lx: Int, ry: Int, lw: Int, mouseX: Int, mouseY: Int) {
        graphics.fill(lx, ry, lx + lw, ry + ROW_H, 0x05FFFFFF)
        graphics.centeredText(font, row.text, lx + lw / 2, ry + 9, ACCENT)
        val bx = muteX
        val over = mouseX in bx..bx + MUTE_W && mouseY in ry + 6..ry + 21
        graphics.fill(bx, ry + 6, bx + MUTE_W, ry + 21, if (over) 0x6455AAFF else 0x12FFFFFF)
        graphics.centeredText(font, if (muted(index)) "Reset" else "Mute all", bx + MUTE_W / 2, ry + 10, if (over) -1 else 0xFFC0C0C0.toInt())
    }

    private fun drawEntry(graphics: GuiGraphicsExtractor, e: Entry, lx: Int, ry: Int, lw: Int, mouseX: Int, mouseY: Int) {
        val px = playX
        val sx = sliderX
        if (dragging === e) drag(e, mouseX, sx)

        val percent = Sounds.getVolumePercent(e.id)
        val filled = SLIDER_W * percent / 200
        if (mouseX in lx..lx + lw && mouseY in ry..ry + ROW_H) graphics.fill(lx, ry, lx + lw, ry + ROW_H, 0x0FFFFFFF)

        graphics.text(font, fitted(e, sx - lx - 12), lx + 5, ry + 9, -1)
        graphics.fill(sx, ry + 17, sx + SLIDER_W, ry + 22, 0x18FFFFFF)
        graphics.fill(sx, ry + 17, sx + filled, ry + 22, ACCENT)
        graphics.fill(sx + filled - 2, ry + 15, sx + filled + 2, ry + 24, -1)
        val over = mouseX in px..px + PLAY_W && mouseY in ry + 6..ry + 21
        graphics.fill(px, ry + 6, px + PLAY_W, ry + 21, if (over) 0x6455AAFF else 0x12FFFFFF)
        graphics.centeredText(font, "Play", px + PLAY_W / 2, ry + 10, if (over) -1 else 0xFFC0C0C0.toInt())
        val label = percentLabel(percent)
        graphics.text(font, label, sx + SLIDER_W - font.width(label), ry + 5, 0xFF808080.toInt())
    }

    private fun fitted(e: Entry, room: Int): String {
        if (e.fitWidth != room) {
            e.fitWidth = room
            e.fitted = if (font.width(e.label) <= room) e.label else font.plainSubstrByWidth(e.label, room - font.width("...")) + "..."
        }
        return e.fitted
    }

    private fun drag(e: Entry, mouseX: Int, sx: Int) {
        val progress = ((mouseX - sx).toFloat() / SLIDER_W).coerceIn(0f, 1f)
        Sounds.setVolumePercent(e.id, (progress * 200).toInt())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        val mx = event.x().toInt()
        val my = event.y().toInt()

        if (mx in winX..winX + SIDEBAR) for (t in Tab.entries) {
            val ty = winY + 30 + t.ordinal * 20
            if (my !in ty..ty + 20) continue
            if (tab != t) {
                tab = t
                fill(true)
                click()
            }
            return true
        }

        val lx = listX
        val ly = listY
        if (mx in lx until lx + listW && my in ly until ly + listH) {
            val index = (my - ly + scroll) / ROW_H
            val ry = ly - scroll + index * ROW_H
            when (val row = rows.getOrNull(index)) {
                is Heading -> if (mx in muteX..muteX + MUTE_W && my in ry + 6..ry + 21) {
                    val to = if (muted(index)) 100 else 0
                    for (i in index + 1 until row.end) Sounds.setVolumePercent((rows[i] as Entry).id, to)
                    click()
                    return true
                }
                is Entry -> {
                    val px = playX
                    val sx = sliderX
                    if (mx in px..px + PLAY_W && my in ry + 6..ry + 21) {
                        Sounds.playPreview(row.event)
                        return true
                    }
                    if (mx in sx - 5..sx + SLIDER_W + 5) {
                        dragging = row
                        drag(row, mx, sx)
                        return true
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    private fun click() = Sounds.playPreview(SoundEvents.UI_BUTTON_CLICK.value())

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        scroll = (scroll - (vertical * ROW_H * 2).toInt()).coerceIn(0, max(0, rows.size * ROW_H - listH))
        return true
    }

    override fun isPauseScreen() = false

    override fun onClose() {
        ModuleManager.saveConfigurations()
        mc.gui.setScreen(previous)
    }

    private companion object {
        const val SIDEBAR = 108
        const val ROW_H = 26
        const val SLIDER_W = 140
        const val PLAY_W = 34
        const val MUTE_W = 50
        const val ACCENT = 0xFF55AAFF.toInt()

        private val percentLabels = arrayOfNulls<String>(201)

        fun percentLabel(p: Int): String {
            if (p !in 0..200) return "$p%"
            return percentLabels[p] ?: "$p%".also { percentLabels[p] = it }
        }

        // The sound registry is fixed once the game has started.
        val catalog: List<Entry> by lazy {
            BuiltInRegistries.SOUND_EVENT.entrySet().map { (key, event) ->
                val id = key.identifier().toString()
                val path = id.removePrefix("minecraft:")
                Entry(id, labelOf(path), tabOf(path), event)
            }.sortedBy(Entry::id)
        }

        val byId: Map<String, Entry> by lazy { catalog.associateBy(Entry::id) }

        fun labelOf(path: String): String = when {
            path.startsWith("entity.hostile.") -> path.substring(15)
            path.startsWith("entity.") -> path.substring(7)
            '.' in path -> path.substringAfter('.')
            else -> path
        }.replace('.', ' ').replace('_', ' ')

        fun tabOf(path: String): Tab = when {
            path.startsWith("block") -> Tab.Blocks
            path.startsWith("entity.hostile") -> Tab.HostileMobs
            path.startsWith("entity") -> Tab.NeutralMobs
            path.startsWith("music") -> Tab.Music
            path.startsWith("ambient") -> Tab.Ambient
            path.startsWith("item") -> Tab.Items
            path.startsWith("ui") -> Tab.UI
            else -> Tab.Misc
        }
    }
}
