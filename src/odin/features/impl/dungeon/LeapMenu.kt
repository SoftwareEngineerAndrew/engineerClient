package com.odtheking.odin.features.impl.dungeon

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.*
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.SetSlotEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.*
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils.leapTeammates
import com.odtheking.odin.utils.ui.compose.Animatable
import com.odtheking.odin.utils.ui.compose.Motion
import com.odtheking.odin.utils.ui.widget.CustomGUIImpl
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * The leap menu, opened the moment you use a Spirit Leap instead of when the server's menu comes. A pick before then
 * waits and is clicked as soon as the server's menu has its heads; meanwhile only the view goes to the player you
 * picked, you stay where you are until the server moves you.
 */
object LeapMenu : Module(
    name = "Leap Menu",
    description = "Renders a custom leap menu when in the Spirit Leap gui.",
    key = null
) {
    val type by SelectorSetting("Sorting", Sorting.ODIN, desc = "How to sort the leap menu. /od leaporder to configure custom sorting.")
    private val onlyClass by BooleanSetting("Only Classes", false, desc = "Renders classes instead of names.")
    private val colorStyle by BooleanSetting("Color Style", false, desc = "Which color style to use.")
    private val backgroundColor by ColorSetting("Background Color", Colors.gray38.withAlpha(0.75f), true, desc = "Color of the background of the leap menu.").withDependency { !colorStyle }
    private val scale by NumberSetting("Render Scale", 1f, 0.1..2.0, 0.1f, desc = "Scale of the leap menu.", unit = "x")
    private val onRelease by BooleanSetting("On Key Release", false, desc = "Whether to trigger the leap on key release instead of key press.")
    private val instant by BooleanSetting("Instant Leap", true, desc = "Opens as you use the leap and takes your pick before the server's menu is there.")
    private val moveView by BooleanSetting("Move View Early", true, desc = "Your view goes to the player you picked while the leap is on its way.").withDependency { instant }
    private val followLook by BooleanSetting("Follow Their Look", true, desc = "Looks where they look until you land; off, you aim the view yourself.").withDependency { instant && moveView }
    private val vignette by BooleanSetting("Leap Vignette", false, desc = "The screen's edges glow until the leap lands.").withDependency { instant }
    private val vignetteColor by ColorSetting("Leap Vignette Color", Color(170, 85, 255, 0.55f), true, desc = "The leap vignette's colour; its alpha is how strong.").withDependency { instant && vignette }

    private val keybindDropdown by DropdownSetting("Keybinds", desc = "Keybinds for the leap menu.").withDependency { type != Sorting.NONE }
    val keybindType by SelectorSetting("Mode", KeybindMode.CORNERS, desc = "How the keybinds should function.").withDependency { keybindDropdown }

    private val topLeftKeybind by KeybindSetting("Top Left", InputConstants.KEY_1, "Used to click on the first person in the leap menu.").withDependency { keybindType == KeybindMode.CORNERS && keybindDropdown }
    private val topRightKeybind by KeybindSetting("Top Right", InputConstants.KEY_2, "Used to click on the second person in the leap menu.").withDependency { keybindType == KeybindMode.CORNERS && keybindDropdown }
    private val bottomLeftKeybind by KeybindSetting("Bottom Left", InputConstants.KEY_3, "Used to click on the third person in the leap menu.").withDependency { keybindType == KeybindMode.CORNERS && keybindDropdown }
    private val bottomRightKeybind by KeybindSetting("Bottom Right", InputConstants.KEY_4, "Used to click on the fourth person in the leap menu.").withDependency { keybindType == KeybindMode.CORNERS && keybindDropdown }

    private val archerKeybind by KeybindSetting("Archer", InputConstants.KEY_1, "Used to leap to the Archer in the leap menu.").withDependency { keybindType == KeybindMode.CLASS && keybindDropdown }
    private val berserkerKeybind by KeybindSetting("Berserker", InputConstants.KEY_2, "Used to leap to the Berserker in the leap menu.").withDependency { keybindType == KeybindMode.CLASS && keybindDropdown }
    private val healerKeybind by KeybindSetting("Healer", InputConstants.KEY_3, "Used to leap to the Healer in the leap menu.").withDependency { keybindType == KeybindMode.CLASS && keybindDropdown }
    private val mageKeybind by KeybindSetting("Mage", InputConstants.KEY_4, "Used to leap to the Mage in the leap menu.").withDependency { keybindType == KeybindMode.CLASS && keybindDropdown }
    private val tankKeybind by KeybindSetting("Tank", InputConstants.KEY_5, "Used to leap to the Tank in the leap menu.").withDependency { keybindType == KeybindMode.CLASS && keybindDropdown }

    private val leapAnnounce by BooleanSetting("Leap Announce", false, desc = "Announces when you leap to a player.")
    private val hoverProgress = List(4) { Animatable(0f) }

    private val EMPTY = DungeonPlayer("Empty", DungeonClass.EMPTY, 0, null)
    private val leapedRegex = Regex("^You have teleported to (\\w{1,16})!$")
    private val TITLES = setOf("Spirit Leap", "Teleport to Player")
    private val LEAPS = setOf("SPIRIT_LEAP", "INFINITE_SPIRIT_LEAP")

    const val BOX_WIDTH = 200
    const val BOX_HEIGHT = 75

    private const val GROW = 5f
    private const val HOVER_DURATION = 200
    /** The server's menu not here this long after the use: it isn't coming (cooldown, no one to leap to). */
    private const val MENU_WAIT_MS = 1500L
    /** After the click, how long the view waits for the server to bring you over. */
    private const val ARRIVE_WAIT_MS = 1200L

    /** The player picked, waiting for the server's menu, then for the leap. */
    private var pending: DungeonPlayer? = null
    private var pickedAt = 0L
    private var clickedAt = 0L
    /** When the leap was used: our menu is up, the server's is on its way. */
    private var usedAt = 0L
    /** Closed before the server's menu came: it closes again as it opens. */
    private var dismissed = false
    @Volatile private var moved = false

    fun isLeapScreen(screen: Screen?): Boolean = screen != null && screen.title.string in TITLES

    private fun currentLeapScreen(): Screen? {
        if (!enabled) return null
        val screen = mc.gui.screen() ?: return null
        if (!isLeapScreen(screen)) return null
        if (leapTeammates.isEmpty() || leapTeammates.all { it == EMPTY }) return null
        return screen
    }

    /** Where the picked player is and looks: your game's copy of them, else the pose a teammate with ADM shares. */
    private class View(val pos: Vec3, val yaw: Float, val pitch: Float)

    private fun view(partial: Float): View? {
        if (!enabled || !instant || !moveView) return null
        val p = pending ?: return null
        val e = p.entity?.takeIf { it.isAlive } ?: mc.level?.players()?.firstOrNull { it.name.string == p.name }
        if (e != null) return View(e.getPosition(partial), e.getViewYRot(partial), e.getViewXRot(partial))
        val s = com.engineerclient.pov.PoseShare.pose(p.name, System.nanoTime()) ?: return null
        return View(s.pos, s.yaw, s.pitch)
    }

    /** The view's own look while it isn't following theirs. */
    private var freeYaw = 0f
    private var freePitch = 0f

    /** A leap is being shown before it lands: you can't move, turn or change slot until it does. */
    @JvmStatic
    fun locked(): Boolean = enabled && instant && moveView && pending != null

    /** The mouse while [locked]: it turns the view only. */
    @JvmStatic
    fun turn(xo: Double, yo: Double) {
        freeYaw += (xo * 0.15).toFloat()
        freePitch = (freePitch + (yo * 0.15).toFloat()).coerceIn(-90f, 90f)
    }

    /** Where the view is while a picked leap is on its way: with the player picked. */
    @JvmStatic
    fun visualPos(): Vec3? = view(mc.deltaTracker.getGameTimeDeltaPartialTick(false))?.pos

    /** Its look: theirs, or yours turned freely: yaw, pitch. */
    @JvmStatic
    fun visualLook(partialTicks: Float): FloatArray? {
        val v = view(partialTicks) ?: return null
        return if (followLook) floatArrayOf(v.yaw, v.pitch) else floatArrayOf(freeYaw, freePitch)
    }

    /** Arrived: you go on looking where the view was, not back where you were before the leap. */
    private fun takeLook() {
        val look = visualLook(1f) ?: return
        val me = mc.player ?: return
        me.yRot = look[0]; me.xRot = look[1]
        me.yRotO = me.yRot; me.xRotO = me.xRot
    }

    init {
        fun triggerMouseQuadrant(screen: Screen, mouseX: Int, mouseY: Int) {
            val quadrant = (if (mouseY >= screen.height / 2) 2 else 0) + (if (mouseX >= screen.width / 2) 1 else 0)
            mouseTrigger(leapTeammates.getOrNull(quadrant) ?: EMPTY, quadrant)
        }

        CustomGUIImpl.register(
            CustomGUIImpl.HandlerSet(enabled = { currentLeapScreen() != null || (pending != null && isLeapScreen(mc.gui.screen())) },
            click = fun ScreenEvent.MouseClick.(): Any {
                val screen = currentLeapScreen() ?: return false
                if (onRelease || busy()) return true
                triggerMouseQuadrant(screen, click.x().toInt(), click.y().toInt())
                return true
            },
            release = fun ScreenEvent.MouseRelease.(): Any {
                val screen = currentLeapScreen() ?: return false
                if (!onRelease || busy()) return true
                triggerMouseQuadrant(screen, click.x().toInt(), click.y().toInt())
                return true
            },
            key = fun ScreenEvent.KeyPress.(): Any {
                if (leapTeammates.isEmpty()) return false
                val keybindList = if (keybindType == KeybindMode.CORNERS) listOf(topLeftKeybind, topRightKeybind, bottomLeftKeybind, bottomRightKeybind)
                else listOf(archerKeybind, berserkerKeybind, healerKeybind, mageKeybind, tankKeybind)
                val pressed = InputConstants.getKey(input)
                if (pressed !in keybindList) return false

                currentLeapScreen() ?: return false
                if (busy()) return true

                val index = if (keybindType == KeybindMode.CORNERS) keybindList.indexOf(pressed)
                else DungeonClass.entries.find { clazz -> clazz.ordinal == keybindList.indexOf(pressed) }
                    ?.let { clazz -> leapTeammates.indexOfFirst { it.clazz == clazz } } ?: return false

                if (index < 0) return false

                val playerToLeap = leapTeammates.getOrNull(index) ?: return false
                if (playerToLeap == EMPTY) return false
                if (playerToLeap.isDead) return modMessage("This player is dead, can't leap.")
                pick(playerToLeap)
                return true
            },
            render = fun ScreenEvent.Render.(): Any {
                // the server's menu for a leap already picked: clicked as soon as its heads are in, never shown;
                // one more after the click, from a use before it, is closed
                if (pending != null && screen !is LeapScreen && (clickedAt == 0L || usedAt == 0L)) {
                    if (clickedAt != 0L) mc.player?.closeContainer() else tryClick()
                    return true
                }
                if (dismissed && screen is AbstractContainerScreen<*>) { dismissed = false; mc.player?.closeContainer(); return true }
                drawMenu(guiGraphics, mouseX, mouseY)
                return true
            })
        )

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "leap_vignette")) { gfx, _ ->
            if (enabled && instant && vignette && pending != null && !moved)
                com.engineerclient.render.Vignette.draw(gfx, vignetteColor.rgba, (vignetteColor.alphaFloat * 255).toInt())
        }

        UseItemCallback.EVENT.register { player, level, hand ->
            if (hand == InteractionHand.MAIN_HAND && level.isClientSide && player === mc.player) open(player.getItemInHand(hand).itemId)
            InteractionResult.PASS
        }

        on<SetSlotEvent> { if (pending != null) tryClick() }

        on<TickEvent.End> {
            val now = System.currentTimeMillis()
            if (pending == null && usedAt != 0L && now - usedAt > MENU_WAIT_MS) {
                usedAt = 0L
                if (mc.gui.screen() is LeapScreen) mc.gui.setScreen(null)
                dismissed = false
            }
            val p = pending ?: return@on
            if (clickedAt == 0L) {
                tryClick()
                if (pending != null && now - pickedAt > MENU_WAIT_MS) clear()
                return@on
            }
            val me = mc.player ?: return@on clear()
            val there = visualPos()
            if (moved && (there == null || me.position().distanceToSqr(there) < 9.0) || now - clickedAt > ARRIVE_WAIT_MS) { if (moved) takeLook(); clear() }
            if (p.isDead) clear()
        }

        onReceive<ClientboundPlayerPositionPacket> { if (clickedAt != 0L) moved = true }

        on<MessageEvent.Chat> {
            if (leapAnnounce && DungeonUtils.inDungeons)
                leapedRegex.find(message)?.groupValues?.get(1)?.let { sendCommand("pc Leaped to ${it}!") }
        }

        on<LevelEvent.Load> { clear(); usedAt = 0L; dismissed = false }
    }

    /** The server's menu that's open is for a leap already clicked: nothing in it to pick. */
    private fun busy() = pending != null && clickedAt != 0L && usedAt == 0L && mc.gui.screen() !is LeapScreen

    /** The leap used: our menu up at once, unless something else is open or there's no one to leap to. */
    private fun open(itemId: String) {
        if (!enabled || !instant || itemId !in LEAPS || !DungeonUtils.inDungeons) return
        if (mc.gui.screen() != null) return
        if (leapTeammates.isEmpty() || leapTeammates.all { it == EMPTY }) return
        usedAt = System.currentTimeMillis()
        dismissed = false
        mc.gui.setScreen(LeapScreen())
    }

    private fun mouseTrigger(player: DungeonPlayer, quadrant: Int) {
        if (type != Sorting.ODIN && type != Sorting.NONE && leapTeammates.size < quadrant) return
        if (player == EMPTY) return
        if (player.isDead) return modMessage("This player is dead, can't leap.")
        pick(player)
    }

    /** [player] picked: the view goes to them; the click waits for the server's menu if it isn't here yet. */
    private fun pick(player: DungeonPlayer) {
        pending = player
        mc.player?.let { freeYaw = it.yRot; freePitch = it.xRot }
        pickedAt = System.currentTimeMillis()
        clickedAt = 0L
        moved = false
        usedAt = 0L
        if (mc.gui.screen() is LeapScreen) mc.gui.setScreen(null)
        tryClick()
    }

    /** The server's menu is open with the picked player's head in it: clicked, and closed. */
    private fun tryClick() {
        val p = pending ?: return
        if (clickedAt != 0L) return
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!isLeapScreen(screen)) return
        val slots = screen.menu.slots
        if (slots.size < 16) return
        val index = slots.subList(11, 16).firstOrNull {
            it.item.hoverName.string.substringAfter(' ').equals(p.name.noControlCodes, true)
        }?.index ?: return
        mc.player?.clickSlot(index)
        clickedAt = System.currentTimeMillis()
        modMessage("Teleporting to ${p.name}.")
        mc.player?.closeContainer()
    }

    private fun clear() {
        pending = null
        clickedAt = 0L
        moved = false
    }

    private fun drawMenu(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        Motion.frameNanos = System.nanoTime()

        val halfW = mc.window.guiScaledWidth / 2
        val halfH = mc.window.guiScaledHeight / 2

        repeat(4) { i ->
            val player = leapTeammates.getOrNull(i) ?: return@repeat
            if (player == EMPTY) return@repeat

            val col = i % 2
            val row = i / 2
            val nearX = if (col == 0) halfW - 24 else halfW + 24
            val nearY = if (row == 0) halfH - 24 else halfH + 24
            val localX = if (col == 0) -BOX_WIDTH else 0
            val localY = if (row == 0) -BOX_HEIGHT else 0

            val hovered = (if (col == 0) mouseX < halfW else mouseX >= halfW) && (if (row == 0) mouseY < halfH else mouseY >= halfH)
            hoverProgress[i].animateTo(if (hovered) 1f else 0f, HOVER_DURATION)
            val grow = hoverProgress[i].value * GROW

            g.pose().pushMatrix()
            g.pose().translate(nearX.toFloat(), nearY.toFloat())
            g.pose().scale(scale * (BOX_WIDTH + grow * 2f) / BOX_WIDTH, scale * (BOX_HEIGHT + grow * 2f) / BOX_HEIGHT)

            g.roundedRect(localX, localY, localX + BOX_WIDTH, localY + BOX_HEIGHT, (if (colorStyle) player.clazz.color else backgroundColor).rgba, 9f)

            val face = (BOX_HEIGHT * 0.76).toInt()
            (player.playerSkin ?: mc.player?.skin)?.let { PlayerFaceExtractor.extractRenderState(g, it, localX + 9, localY + 9, face) }

            g.text(
                if (!onlyClass) player.name else player.clazz.name,
                localX + 15 + face,
                localY + (BOX_HEIGHT / 2.5).toInt(),
                if (!colorStyle) player.clazz.color else backgroundColor
            )

            if (!onlyClass || player.isDead) {
                g.text(
                    if (player.isDead) "DEAD" else player.clazz.name.lowercase(),
                    localX + 15 + face,
                    localY + (BOX_HEIGHT / 1.7).toInt(),
                    if (player.isDead) Colors.MINECRAFT_RED else Colors.WHITE
                )
            }

            g.pose().popMatrix()
        }
    }

    /** Ours until the server's menu replaces it; titled as the server's so everything that looks for the leap menu finds it. */
    private class LeapScreen : Screen(Component.literal("Spirit Leap")) {
        override fun isPauseScreen() = false

        override fun onClose() {
            // closed without a pick while the server's menu is still coming
            if (pending == null && usedAt != 0L) dismissed = true
            super.onClose()
        }
    }

    /**
     * Sorts the list of players based on their default quadrant and class priority.
     * The function first tries to place each player in their default quadrant. If the quadrant is already occupied,
     * the player is added to a second round list. After all players have been processed, the function fills the remaining
     * empty quadrants with the players from the second round list.
     */
    fun odinSorting(players: List<DungeonPlayer>): Array<DungeonPlayer> {
        val result = Array(4) { EMPTY }
        val secondRound = mutableListOf<DungeonPlayer>()

        for (player in players.sortedBy { it.clazz.priority }) {
            when {
                result[player.clazz.defaultQuadrant] == EMPTY -> result[player.clazz.defaultQuadrant] = player
                else -> secondRound.add(player)
            }
        }

        if (secondRound.isEmpty()) return result

        result.forEachIndexed { index, _ ->
            when {
                result[index] == EMPTY -> {
                    result[index] = secondRound.removeAt(0)
                    if (secondRound.isEmpty()) return result
                }
            }
        }
        return result
    }

    enum class Sorting(private val label: String) {
        ODIN("Odin Sorting"),
        CLASS("A-Z Class"),
        NAME("A-Z Name"),
        CUSTOM("Custom sorting"),
        NONE("No Sorting");

        override fun toString(): String = label
    }
    enum class KeybindMode { CORNERS, CLASS }
}
