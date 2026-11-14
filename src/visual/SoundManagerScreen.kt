package com.engineerclient.visual

import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.features.ModuleManager
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
    private enum class Group(val label: String) {
        All("All"), Recent("Recent"), Blocks("Blocks"), HostileMobs("Hostile Mobs"), NeutralMobs("Neutral Mobs"),
        Music("Music"), Ambient("Ambient"), Items("Items"), UI("UI"), Misc("Misc")
    }

    private sealed interface Row
    private data class Header(val name: String) : Row
    private data class Sound(val id: String, val cleanName: String, val group: Group, val sound: SoundEvent) : Row {
        val searchText = "$id $cleanName"
    }

    private val allSounds = BuiltInRegistries.SOUND_EVENT.entrySet().map { (key, sound) ->
        val id = key.identifier().toString()
        Sound(id, cleanName(id), group(id), sound)
    }.sortedBy(Sound::id)
    private val soundsById = allSounds.associateBy(Sound::id)
    private val rows = mutableListOf<Row>()

    private var selected = Group.All
    private var recentVersion = -1L
    private var scroll = 0
    private var draggingId: String? = null
    private lateinit var search: EditBox

    private val winW get() = min(540, width - 20)
    private val winH get() = min(285, height - 20)
    private val winX get() = (width - winW) / 2
    private val winY get() = (height - winH) / 2
    private val viewX get() = winX + SIDEBAR + 10
    private val viewY get() = winY + 26
    private val viewW get() = winW - SIDEBAR - 34
    private val viewH get() = winH - 62

    override fun init() {
        search = EditBox(font, viewX + viewW / 2 - 100, winY + winH - 28, 200, 18, Component.literal("Search"))
        search.setHint(Component.literal("Search..."))
        search.setResponder { updateFilter() }
        addRenderableWidget(search)
        updateFilter()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (selected == Group.Recent && recentVersion != Sounds.recentSoundsVersion) updateFilter(resetScroll = false)

        graphics.fill(winX, winY, winX + winW, winY + winH, 0xF0141414.toInt())
        graphics.fill(winX, winY, winX + SIDEBAR, winY + winH, 0xC80F0F0F.toInt())
        graphics.fill(winX, winY, winX + winW, winY + 2, ACCENT)
        graphics.centeredText(font, "§lSound Manager", winX + (winW + SIDEBAR) / 2, winY + 8, -1)

        Group.entries.forEachIndexed { i, group ->
            val y = winY + 30 + i * 20
            val hovered = mouseX in winX..winX + SIDEBAR && mouseY in y..y + 20
            if (group == selected) graphics.fill(winX, y, winX + SIDEBAR, y + 20, 0x2855AAFF)
            else if (hovered) graphics.fill(winX, y, winX + SIDEBAR, y + 20, 0x0AFFFFFF)
            graphics.text(font, group.label, winX + 10, y + 6, if (group == selected) ACCENT else 0xFFAAAAAA.toInt())
        }

        clampScroll()
        graphics.enableScissor(viewX, viewY, viewX + viewW, viewY + viewH)
        val start = max(0, scroll / ROW_H)
        val end = min(rows.size, start + viewH / ROW_H + 2)
        for (i in start until end) {
            val y = viewY - scroll + i * ROW_H
            when (val row = rows[i]) {
                is Header -> {
                    graphics.fill(viewX, y, viewX + viewW, y + ROW_H, 0x05FFFFFF)
                    graphics.centeredText(font, "§l${row.name}", viewX + viewW / 2, y + 9, ACCENT)
                }
                is Sound -> drawSound(graphics, row, y, mouseX, mouseY)
            }
        }
        graphics.disableScissor()

        val content = rows.size * ROW_H
        if (content > viewH) {
            val barX = winX + winW - 18
            val thumbH = max(28, viewH * viewH / content)
            val thumbY = viewY + (viewH - thumbH) * scroll / (content - viewH)
            graphics.fill(barX, viewY, barX + 6, viewY + viewH, 0x18FFFFFF)
            graphics.fill(barX, thumbY, barX + 6, thumbY + thumbH, ACCENT)
        }

        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    private fun drawSound(graphics: GuiGraphicsExtractor, sound: Sound, y: Int, mouseX: Int, mouseY: Int) {
        val playX = viewX + viewW - PLAY_W - 8
        val sliderX = playX - SLIDER_W - 8
        if (draggingId == sound.id) setVolume(sound.id, mouseX, sliderX)

        val volume = Sounds.getVolumePercent(sound.id)
        val filled = SLIDER_W * volume / 200
        val playHovered = mouseX in playX..playX + PLAY_W && mouseY in y + 6..y + 21
        if (mouseX in viewX..viewX + viewW && mouseY in y..y + ROW_H) graphics.fill(viewX, y, viewX + viewW, y + ROW_H, 0x0FFFFFFF)

        val maxName = sliderX - viewX - 12
        val name = if (font.width(sound.cleanName) <= maxName) sound.cleanName else font.plainSubstrByWidth(sound.cleanName, maxName - font.width("...")) + "..."
        graphics.text(font, name, viewX + 5, y + 9, -1)
        graphics.fill(sliderX, y + 17, sliderX + SLIDER_W, y + 22, 0x18FFFFFF)
        graphics.fill(sliderX, y + 17, sliderX + filled, y + 22, ACCENT)
        graphics.fill(sliderX + filled - 2, y + 15, sliderX + filled + 2, y + 24, -1)
        graphics.fill(playX, y + 6, playX + PLAY_W, y + 21, if (playHovered) 0x6455AAFF else 0x12FFFFFF)
        graphics.centeredText(font, "Play", playX + PLAY_W / 2, y + 10, if (playHovered) -1 else 0xFFC0C0C0.toInt())
        val text = "$volume%"
        graphics.text(font, text, sliderX + SLIDER_W - font.width(text), y + 5, 0xFF808080.toInt())
    }

    private fun updateFilter(resetScroll: Boolean = true) {
        val query = if (::search.isInitialized) search.value.lowercase() else ""
        val sounds = when (selected) {
            Group.Recent -> Sounds.getRecentSoundIds().mapNotNull(soundsById::get)
            else -> allSounds.filter { selected == Group.All || it.group == selected }
        }.filter { query.isEmpty() || it.searchText.contains(query) }

        rows.clear()
        if (selected == Group.Recent) {
            if (sounds.isNotEmpty()) rows.add(Header(Group.Recent.label))
            rows.addAll(sounds)
            recentVersion = Sounds.recentSoundsVersion
        } else sounds.groupBy(Sound::group).toSortedMap().forEach { (group, list) ->
            rows.add(Header(group.label))
            rows.addAll(list)
        }
        if (resetScroll) scroll = 0
    }

    private fun clampScroll() {
        scroll = scroll.coerceIn(0, max(0, rows.size * ROW_H - viewH))
    }

    private fun setVolume(id: String, mouseX: Int, sliderX: Int) {
        val progress = ((mouseX - sliderX).toFloat() / SLIDER_W).coerceIn(0f, 1f)
        Sounds.setVolumePercent(id, (progress * 200).toInt())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick)
        val mx = event.x().toInt()
        val my = event.y().toInt()

        if (mx in winX..winX + SIDEBAR) Group.entries.forEachIndexed { i, group ->
            val y = winY + 30 + i * 20
            if (my in y..y + 20) {
                if (selected != group) {
                    selected = group
                    updateFilter()
                    Sounds.playPreview(SoundEvents.UI_BUTTON_CLICK.value())
                }
                return true
            }
        }

        if (mx in viewX until viewX + viewW && my in viewY until viewY + viewH) {
            val index = (my - viewY + scroll) / ROW_H
            val row = rows.getOrNull(index)
            if (row is Sound) {
                val y = viewY - scroll + index * ROW_H
                val playX = viewX + viewW - PLAY_W - 8
                val sliderX = playX - SLIDER_W - 8
                if (mx in playX..playX + PLAY_W && my in y + 6..y + 21) {
                    Sounds.playPreview(row.sound)
                    return true
                }
                if (mx in sliderX - 5..sliderX + SLIDER_W + 5) {
                    draggingId = row.id
                    setVolume(row.id, mx, sliderX)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        draggingId = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        scroll -= (vertical * ROW_H * 2).toInt()
        clampScroll()
        return true
    }

    override fun isPauseScreen() = false

    override fun onClose() {
        ModuleManager.saveConfigurations()
        mc.gui.setScreen(previous)
    }

    private fun cleanName(id: String): String {
        val path = id.removePrefix("minecraft:")
        return when {
            path.startsWith("entity.hostile.") -> path.removePrefix("entity.hostile.")
            path.startsWith("entity.") -> path.removePrefix("entity.")
            path.contains(".") -> path.substringAfter(".")
            else -> path
        }.replace(".", " ").replace("_", " ")
    }

    private fun group(id: String): Group {
        val path = id.removePrefix("minecraft:")
        return when {
            path.startsWith("block") -> Group.Blocks
            path.startsWith("entity.hostile") -> Group.HostileMobs
            path.startsWith("entity") -> Group.NeutralMobs
            path.startsWith("music") -> Group.Music
            path.startsWith("ambient") -> Group.Ambient
            path.startsWith("item") -> Group.Items
            path.startsWith("ui") -> Group.UI
            else -> Group.Misc
        }
    }

    private companion object {
        const val SIDEBAR = 108
        const val ROW_H = 26
        const val SLIDER_W = 140
        const val PLAY_W = 34
        const val ACCENT = 0xFF55AAFF.toInt()
    }
}
