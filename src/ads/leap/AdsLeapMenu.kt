// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.leap

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.dungeon.AdsClass
import com.engineerclient.ads.dungeon.AdsDungeon
import com.engineerclient.ads.dungeon.AdsTeammate
import com.engineerclient.ads.map.AdsMinimap
import com.engineerclient.ads.map.DungeonScan
import com.engineerclient.ads.map.MinimapRenderer
import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.KeybindSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Settings
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.ads.settings.withDependency
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import kotlin.math.hypot
import kotlin.math.min

/**
 * ADS's leap menu, in place of the Spirit Leap chest (and Teleport to Player), without needing Odin: Odin's Leap Menu
 * (its four corners, sorting, corner and class keybinds, On Key Release, Leap Announce) in ADS's own look, with
 * Click Delay and Map Leap (the dungeon map with everyone's head on it, in the clear) built in. Its "Leap Menu"
 * panel in `/ads` turns it on (config/engineerclient/leap-menu.json); while it's on, Odin's Leap Menu steps aside
 * (`AdsLeapOdinMixin`) so there is only ever one. The chest's mixin (`AdsLeapScreenMixin`) hands it each frame,
 * click and key.
 */
object AdsLeapMenu : Settings("Leap Menu", "leap-menu.json") {
    enum class Sorting { ODIN, CLASS, NAME, NONE }
    enum class KeyMode { CORNERS, CLASS }

    private val enabledS = +BooleanSetting("Leap Menu", false, "Shows ADS's leap menu instead of the Spirit Leap chest: everyone in a corner, click one (or press its key) to leap. Odin's Leap Menu steps aside while this is on.")
    private val sortingS = +SelectorSetting("Sorting", Sorting.ODIN, "Who goes in which corner. Odin: each class in its own corner (archer top left, berserk top right, healer bottom left, mage and tank bottom right), the rest where there's room. Class and Name: A-Z. None: the tab list's order.")
        .withDependency { enabledS.value }
    private val classNamesS = +BooleanSetting("Show Classes", false, "Writes each person's class in big instead of their name.")
        .withDependency { enabledS.value }
    private val scaleS = +NumberSetting("Size", 1.0, 0.5..2.0, 0.05, unit = "x", desc = "How big the corners are.")
        .withDependency { enabledS.value }
    private val onReleaseS = +BooleanSetting("On Key Release", false, "Leaps when you let go of the mouse button instead of when you press it.")
        .withDependency { enabledS.value }
    private val clickDelayS = +NumberSetting("Click Delay", 1, 0..10, 1, unit = "t", desc = "Ticks after the menu opens during which clicks do nothing, so letting go of the right-click that opened it can't leap you by accident.")
        .withDependency { enabledS.value }
    private val announceS = +BooleanSetting("Leap Announce", false, "Says \"Leaped to <name>!\" in party chat when you leap.")
        .withDependency { enabledS.value }
    private val mapLeapS = +BooleanSetting("Map Leap", false, "In the clear, the menu is the dungeon map with everyone's head where they are: click a head to leap to them (the one nearest the cursor is enlarged). Drawn in the Minimap's look.")
        .withDependency { enabledS.value }
    private val mapSizeS = +NumberSetting("Map Leap Size", 0.8, 0.3..1.0, 0.05, desc = "How much of the screen Map Leap's map takes up.")
        .withDependency { enabledS.value && mapLeapS.value }
    private val keyModeS = +SelectorSetting("Keys", KeyMode.CORNERS, "Corners: a key for each corner. Class: a key for each class.")
        .withDependency { enabledS.value }

    private fun key(code: Int): InputConstants.Key = InputConstants.Type.KEYBOARD.getOrCreate(code)
    private val numberKeys = listOf(InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5)
    private val cornerKeys = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right").mapIndexed { i, name ->
        +KeybindSetting("$name Key", key(numberKeys[i]), "Leaps to whoever is in the $name corner.").withDependency { enabledS.value && keyModeS.value == KeyMode.CORNERS }
    }
    private val classKeys = AdsClass.entries.filter { it != AdsClass.EMPTY }.mapIndexed { i, c ->
        c to (+KeybindSetting("${c.label} Key", key(numberKeys[i]), "Leaps to the ${c.label.lowercase()}.").withDependency { enabledS.value && keyModeS.value == KeyMode.CLASS })
    }

    private const val BOX_W = 200
    private const val BOX_H = 75
    private const val GAP = 24
    private const val GROW = 5f

    // ADS's menu colours (the /ads screen's)
    private const val SHADE = 0x80000000.toInt()
    private const val SURFACE = 0xEB1A1A1A.toInt()
    private const val HOVER = 0xEB262626.toInt()
    private const val ACCENT = 0xFF3296DC.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val MUTED = 0xFFAAAAAA.toInt()
    private const val DEAD = 0xFFFF5555.toInt()

    private val leapMenus = setOf("Spirit Leap", "Teleport to Player")
    private val leapedRegex = Regex("^You have teleported to (\\w{1,16})!$")

    private var openedScreen: AbstractContainerScreen<*>? = null
    private var openedAt = 0L
    private val hover = FloatArray(4)
    private var lastFrame = 0L
    private var mapDrawn = false

    init {
        AdsSettingsRegistry.register { listOf(SettingsPanel(this, "Leap Menu", all)) }
    }

    fun init() {
        load()
        AdsDungeon.init()
        DungeonScan.init()
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay && enabledS.value && announceS.value && AdsDungeon.inDungeon)
                leapedRegex.find(message.string)?.groupValues?.get(1)?.let { name -> mc.player?.connection?.sendCommand("pc Leaped to $name!") }
        }
    }

    /** ADS's menu is on, so Odin's Leap Menu stands aside (`AdsLeapOdinMixin`). */
    @JvmStatic
    fun replacesOdin(): Boolean = enabledS.value

    /** [screen] is a leap chest and the menu takes it over. */
    @JvmStatic
    fun active(screen: Any?): Boolean {
        if (!enabledS.value || screen !is AbstractContainerScreen<*> || screen.title.string !in leapMenus) return false
        if (AdsDungeon.teammatesNoSelf.isEmpty()) return false
        if (screen !== openedScreen) {
            openedScreen = screen
            openedAt = System.currentTimeMillis()
            hover.fill(0f)
        }
        return true
    }

    private fun inClickDelay() = System.currentTimeMillis() - openedAt < clickDelayS.value * 50L

    /** Who is in each corner, null where nobody is (Odin's sortings). */
    private fun corners(): List<AdsTeammate?> {
        val people = AdsDungeon.teammatesNoSelf
        val list: List<AdsTeammate?> = when (sortingS.value) {
            Sorting.ODIN -> odinSorting(people)
            Sorting.CLASS -> people.sortedWith(compareBy({ it.clazz.ordinal }, { it.name }))
            Sorting.NAME -> people.sortedBy { it.name }
            Sorting.NONE -> people
        }
        return List(4) { list.getOrNull(it) }
    }

    /** Odin's sorting: each class in its own corner by priority, then the rest into the empty corners. */
    private fun odinSorting(players: List<AdsTeammate>): List<AdsTeammate?> {
        val result = arrayOfNulls<AdsTeammate>(4)
        val second = ArrayDeque<AdsTeammate>()
        for (p in players.sortedBy { it.clazz.priority }) {
            if (result[p.clazz.defaultQuadrant] == null) result[p.clazz.defaultQuadrant] = p else second.add(p)
        }
        for (i in result.indices) if (result[i] == null && second.isNotEmpty()) result[i] = second.removeFirst()
        return result.toList()
    }

    private fun mapMode() = mapLeapS.value && AdsDungeon.inClear

    // ---------------------------------------------------------------------------------- drawing

    @JvmStatic
    fun render(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        g.fill(0, 0, screen.width, screen.height, SHADE)
        mapDrawn = mapMode() && runCatching { renderMap(g, mouseX.toFloat(), mouseY.toFloat()) }
            .onFailure { EngineerClient.logger.warn("[ads] map leap failed to draw", it) }.isSuccess
        if (!mapDrawn) renderCorners(screen, g, mouseX, mouseY, dt)
    }

    private fun keyLabel(i: Int, p: AdsTeammate): String {
        val k = if (keyModeS.value == KeyMode.CORNERS) cornerKeys[i].value else classKeys.firstOrNull { it.first == p.clazz }?.second?.value
        return if (k == null || k == InputConstants.UNKNOWN) "" else k.displayName.string
    }

    private fun renderCorners(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, dt: Float) {
        val halfW = screen.width / 2
        val halfH = screen.height / 2
        val scale = scaleS.value.toFloat()
        val font = mc.font
        for ((i, p) in corners().withIndex()) {
            if (p == null) continue
            val left = i % 2 == 0
            val top = i < 2
            val hovered = (if (left) mouseX < halfW else mouseX >= halfW) && (if (top) mouseY < halfH else mouseY >= halfH)
            hover[i] = (hover[i] + (if (hovered) dt else -dt) * 5f).coerceIn(0f, 1f)
            val grow = hover[i] * GROW

            val x0 = if (left) -BOX_W else 0
            val y0 = if (top) -BOX_H else 0
            g.pose().pushMatrix()
            g.pose().translate((if (left) halfW - GAP else halfW + GAP).toFloat(), (if (top) halfH - GAP else halfH + GAP).toFloat())
            g.pose().scale(scale * (BOX_W + grow * 2) / BOX_W, scale * (BOX_H + grow * 2) / BOX_H)

            g.fill(x0, y0, x0 + BOX_W, y0 + BOX_H, if (hovered) HOVER else SURFACE)
            // the class's colour along the side nearest the middle, the accent under the one the mouse is on
            val stripe = if (left) x0 + BOX_W - 3 else x0
            g.fill(stripe, y0, stripe + 3, y0 + BOX_H, p.clazz.color)
            if (hovered) g.fill(x0, y0 + BOX_H - 2, x0 + BOX_W, y0 + BOX_H, ACCENT)

            val face = 57
            val faceX = if (left) x0 + 9 else x0 + 12
            p.skin?.let { PlayerFaceExtractor.extractRenderState(g, it, faceX, y0 + 9, face) }
            if (p.isDead) g.fill(faceX, y0 + 9, faceX + face, y0 + 9 + face, 0x99000000.toInt())

            val textX = faceX + face + 10
            g.pose().pushMatrix()
            g.pose().translate(textX.toFloat(), y0 + 18f)
            g.pose().scale(1.5f)
            g.text(font, if (classNamesS.value) p.clazz.label else p.name, 0, 0, if (p.isDead) MUTED else p.clazz.color, true)
            g.pose().popMatrix()
            g.text(font, if (p.isDead) "DEAD" else if (classNamesS.value) p.name else p.clazz.label, textX, y0 + 42, if (p.isDead) DEAD else TEXT, true)

            val label = keyLabel(i, p)
            if (label.isNotEmpty()) {
                val w = font.width(label)
                val kx = if (left) x0 + BOX_W - 10 - w else x0 + BOX_W - 7 - w
                g.text(font, label, kx, y0 + 6, MUTED, false)
            }
            g.pose().popMatrix()
        }
    }

    // ---------------------------------------------------------------------------------- Map Leap

    private const val HEAD = 16f
    private const val HEAD_HOVER = 30f
    private const val PICK_RANGE = 40f

    private class Layout(val x: Float, val y: Float, val scale: Float)

    private fun lcm() = AdsMinimap.look == AdsMinimap.Look.LCM_STYLE

    private fun layout(): Layout {
        val (w, h) = if (lcm()) MinimapRenderer.Lcm.size(false) else MinimapRenderer.Odin.SIZE to MinimapRenderer.Odin.SIZE
        val sw = mc.window.guiScaledWidth.toFloat(); val sh = mc.window.guiScaledHeight.toFloat()
        val scale = min(sw, sh) * mapSizeS.value.toFloat() / maxOf(w, h)
        return Layout((sw - w * scale) / 2f, (sh - h * scale) / 2f, scale)
    }

    /** Where someone is on screen: their entity if the game has it, else their map marker. */
    private fun screenPos(p: AdsTeammate, l: Layout): Pair<Float, Float> {
        val e = p.entity
        val (mx, mz) = if (e != null) MinimapRenderer.worldToMap(e.x) to MinimapRenderer.worldToMap(e.z) else MinimapRenderer.markerToMap(p.mapX, p.mapZ)
        val (hx, hz) = if (lcm()) MinimapRenderer.Lcm.toHud(mx, mz) else MinimapRenderer.Odin.toHud(mx, mz)
        return l.x + hx * l.scale to l.y + hz * l.scale
    }

    private fun targetAt(mouseX: Float, mouseY: Float): AdsTeammate? {
        val l = layout()
        return AdsDungeon.teammatesNoSelf.filter { !it.isDead }
            .map { it to screenPos(it, l).let { (x, y) -> hypot(x - mouseX, y - mouseY) } }
            .filter { it.second <= PICK_RANGE }
            .minByOrNull { it.second }?.first
    }

    private fun renderMap(g: GuiGraphicsExtractor, mouseX: Float, mouseY: Float) {
        val l = layout()
        g.pose().pushMatrix()
        g.pose().translate(l.x, l.y)
        g.pose().scale(l.scale)
        if (lcm()) MinimapRenderer.Lcm.draw(g, false, players = false) else MinimapRenderer.Odin.draw(g, false, players = false)
        g.pose().popMatrix()

        val hovered = targetAt(mouseX, mouseY)
        mc.player?.let { self ->
            val (hx, hz) = MinimapRenderer.worldToMap(self.x).let { x -> MinimapRenderer.worldToMap(self.z).let { z -> if (lcm()) MinimapRenderer.Lcm.toHud(x, z) else MinimapRenderer.Odin.toHud(x, z) } }
            head(g, self.skin, l.x + hx * l.scale, l.y + hz * l.scale, HEAD * 0.75f, 0x99FFFFFF.toInt(), dim = true, outline = false)
        }
        for (p in AdsDungeon.teammatesNoSelf.sortedBy { it == hovered }) {
            val (x, y) = screenPos(p, l)
            val big = p == hovered
            head(g, p.skin, x, y, if (big) HEAD_HOVER else HEAD, p.clazz.color, dim = p.isDead, outline = big)
            if (big || p.isDead) {
                val label = if (p.isDead) "${p.name} (dead)" else p.name
                g.text(mc.font, label, (x - mc.font.width(label) / 2f).toInt(), (y + HEAD_HOVER / 2f + 3).toInt(), if (p.isDead) DEAD else p.clazz.color, true)
            }
        }
    }

    private fun head(g: GuiGraphicsExtractor, skin: net.minecraft.world.entity.player.PlayerSkin?, x: Float, y: Float, size: Float, border: Int, dim: Boolean, outline: Boolean) {
        val s = size.toInt(); val x0 = (x - size / 2f).toInt(); val y0 = (y - size / 2f).toInt()
        val b = if (outline) 3 else 2
        if (outline) g.fill(x0 - b - 1, y0 - b - 1, x0 + s + b + 1, y0 + s + b + 1, ACCENT)
        g.fill(x0 - b, y0 - b, x0 + s + b, y0 + s + b, border)
        skin?.let { PlayerFaceExtractor.extractRenderState(g, it, x0, y0, s) }
        if (dim) g.fill(x0, y0, x0 + s, y0 + s, 0x99000000.toInt())
    }

    // ---------------------------------------------------------------------------------- input

    /** A mouse press (or, with On Key Release, a release) in the menu. Always handled: the chest under it never gets clicks. */
    @JvmStatic
    fun click(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, release: Boolean): Boolean {
        if (release != onReleaseS.value || inClickDelay()) return true
        if (mapDrawn && mapMode()) {
            targetAt(mouseX.toFloat(), mouseY.toFloat())?.let { leap(screen, it) }
            return true
        }
        val quadrant = (if (mouseY >= screen.height / 2) 2 else 0) + (if (mouseX >= screen.width / 2) 1 else 0)
        corners()[quadrant]?.let { leap(screen, it) }
        return true
    }

    /** A key in the menu: a leap key leaps; anything else (Escape included) goes on to the chest. */
    @JvmStatic
    fun key(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        val pressed = InputConstants.getKey(event)
        val target = if (keyModeS.value == KeyMode.CORNERS) {
            val i = cornerKeys.indexOfFirst { it.value == pressed }
            if (i < 0) return false
            corners()[i]
        } else {
            val clazz = classKeys.firstOrNull { it.second.value == pressed }?.first ?: return false
            AdsDungeon.teammatesNoSelf.firstOrNull { it.clazz == clazz }
        }
        target?.let { leap(screen, it) }
        return true
    }

    /** Clicks the person's head in the leap chest (Odin's leapTo). */
    private fun leap(screen: AbstractContainerScreen<*>, p: AdsTeammate) {
        val player = mc.player ?: return
        if (p.isDead) {
            player.sendSystemMessage(Component.literal("§b[ADS] §7${p.name} is dead, can't leap."))
            return
        }
        val slot = screen.menu.slots.subList(11, minOf(16, screen.menu.slots.size)).firstOrNull {
            it.item.hoverName.string.substringAfter(' ').equals(p.name, ignoreCase = true)
        } ?: return
        mc.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
    }
}
