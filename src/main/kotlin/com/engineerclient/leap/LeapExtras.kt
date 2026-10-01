package com.engineerclient.leap

import com.engineerclient.EngineerClient
import com.engineerclient.recorder.EcRec
import com.google.gson.JsonParser
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.modMessage
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.onReceive
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import com.odtheking.odin.events.ScreenEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.features.impl.boss.termsim.NumbersSim
import com.odtheking.odin.features.impl.boss.termsim.StartGUI
import com.odtheking.odin.features.impl.boss.termsim.TermSimGUI
import net.minecraft.client.gui.screens.Screen
import com.odtheking.odin.utils.render.roundedFill
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.resources.Identifier

/**
 * Additions to Odin's Leap Menu that ship with engineerClient, so they work on anyone's Odin: they
 * patch Odin's leap menu from outside (see the mixins in `mixin/odin`) rather than living in a
 * modified copy of it. Odin's Leap Menu has to be on for any of it, as it is the menu being added to.
 *
 * Map Leap: in the clear, the leap menu is the dungeon map with everyone's head on it ([LeapMap]
 * draws it and says which head the cursor is on). Odin's leap menu calls its render handler and,
 * for both click modes, `triggerMouseQuadrant`; a mixin at the head of each hands over to
 * [renderMapLeap] / [mapLeapClick] in map mode, and the leap itself is Odin's own `mouseTrigger` ([OdinLeap]).
 */
object LeapExtras : Module(
    name = "Leap Extras",
    category = Category.custom("Engineer Client"),
    description = "Adds to Odin's Leap Menu: a click delay when it opens, an outline of where each person will be, and Map Leap (the dungeon map as the leap menu, in the clear).",
    toggled = true,
) {
    private val clickDelay by NumberSetting("Click Delay", 1, 0, 10, 1, desc = "Ticks after the leap menu opens during which mouse clicks are ignored, so letting go of the right-click that opened it can't leap you by accident.", unit = "t")

    private val showIn by SelectorSetting("Show In", "Everywhere", ShowIn.OPTIONS, desc = "Where Click Delay and Leap Outline work. Blood rush is from the dungeon starting until the blood door opens.")

    private val leapOutline by BooleanSetting("Leap Outline", false, desc = "Draws a very faint rectangle where each person in the leap menu would be, so your mouse can already be on the right one when it opens.")

    private val mapLeap by BooleanSetting("Map Leap", false, desc = "In the clear, the leap menu shows the dungeon map with everyone's head where they are. Click a head to leap to them; the one nearest the cursor is enlarged.")
    private val mapLeapSize by NumberSetting("Map Leap Size", 0.8f, 0.3f, 1f, 0.05f, desc = "How much of the screen the map takes up.").withDependency { mapLeap }
    private val mapColors by BooleanSetting("Map Leap Colors", false, desc = "Shows the map's colours, and buttons that copy them from Odin's map, DTMap or Devonian.").withDependency { mapLeap }

    /** One map colour: its setting, and where each map mod it can be imported from keeps the same colour. */
    private class MapColour(val setting: ColorSetting, val odin: (() -> Color)?, val dtmap: String?, val devonian: String?)
    private val mapColours = ArrayList<MapColour>()

    private fun mapColour(name: String, default: Color, odin: (() -> Color)?, dtmap: String?, devonian: String?): ColorSetting {
        val setting = registerSetting(ColorSetting(name, default, true, desc = "Map Leap: $name colour.").withDependency { mapLeap && mapColors })
        mapColours += MapColour(setting, odin, dtmap, devonian)
        return setting
    }

    // Odin's map has no rare-room colour and Devonian no fairy-door one; those keep their own.
    private val cBackground = mapColour("Map Background", Color(0, 0, 0, 0.7f), { DungeonMap.backgroundColor }, "backgroundColor", "backgroundColor")
    private val cNormal = mapColour("Normal Room", Color(107, 58, 17), { DungeonMap.normalRoomColor }, "normalRoomColor", "roomNormalColor")
    private val cPuzzle = mapColour("Puzzle Room", Color(117, 0, 133), { DungeonMap.puzzleRoomColor }, "puzzleRoomColor", "roomPuzzleColor")
    private val cTrap = mapColour("Trap Room", Color(216, 127, 51), { DungeonMap.trapRoomColor }, "trapRoomColor", "roomTrapColor")
    private val cBlood = mapColour("Blood Room", Color(255, 0, 0), { DungeonMap.bloodRoomColor }, "bloodRoomColor", "roomBloodColor")
    private val cEntrance = mapColour("Entrance Room", Color(20, 133, 0), { DungeonMap.entranceRoomColor }, "entranceRoomColor", "roomEntranceColor")
    private val cFairy = mapColour("Fairy Room", Color(224, 0, 255), { DungeonMap.fairyRoomColor }, "fairyRoomColor", "roomFairyColor")
    private val cChampion = mapColour("Champion Room", Color(254, 223, 0), { DungeonMap.championRoomColor }, "championRoomColor", "roomMinibossColor")
    private val cRare = mapColour("Rare Room", Color(107, 58, 17), null, "rareRoomColor", "roomRareColor")
    private val cUnknown = mapColour("Unknown Room", Color(40, 40, 40), { DungeonMap.unknownRoomColor }, "unopenedRoomColor", "roomUnknownColor")
    private val cNormalDoor = mapColour("Normal Door", Color(80, 40, 10), { DungeonMap.normalDoorColor }, "normalDoorColor", "doorNormalColor")
    private val cWitherDoor = mapColour("Wither Door", Color(0, 0, 0), { DungeonMap.witherDoorColor }, "witherDoorColor", "doorWitherColor")
    private val cBloodDoor = mapColour("Blood Door", Color(255, 0, 0), { DungeonMap.bloodDoorColor }, "bloodDoorColor", "doorBloodColor")
    private val cFairyDoor = mapColour("Fairy Door", Color(160, 0, 180), { DungeonMap.fairyDoorColor }, "fairyDoorColor", null)
    private val cUnopenedDoor = mapColour("Unopened Door", Color(30, 30, 30), { DungeonMap.unknownDoorColor }, "unopenedDoorColor", "roomUnknownColor")

    private val importOdin by ActionSetting("Import colours from Odin's map", desc = "Copies the room and door colours of Odin's Dungeon Map.") { importMapColors("Odin") }.withDependency { mapLeap && mapColors }
    private val importDtmap by ActionSetting("Import colours from DTMap", desc = "Reads config/dtmap/tabs/Map.txt.") { importMapColors("DTMap") }.withDependency { mapLeap && mapColors }
    private val importDevonian by ActionSetting("Import colours from Devonian", desc = "Reads config/devonianConfig.json.") { importMapColors("Devonian") }.withDependency { mapLeap && mapColors }


    private val OUTLINE_GREY = Color(128, 128, 128, 0.12f)

    /** The simulator on screen was opened by [openNumbersSim]: finishing it starts the next one instead of Odin's termsim menu. */
    private var simFromLeap = false
    /** Bumped by every finish and every stop, so a pending restart knows whether it's still wanted. */
    private var simRound = 0

    /**
     * SS Practice's keybind with Infinileap in your hand: Odin's numbers terminal simulator. Finishing
     * one starts another straight away, in the same menu; Escape to stop. False: not holding it.
     */
    @JvmStatic
    fun openNumbersSim(): Boolean {
        val player = EngineerClient.mc.player ?: return false
        if (EngineerClient.mc.screen != null || player.mainHandItem.itemId != "INFINITE_SPIRIT_LEAP") return false
        simFromLeap = true
        NumbersSim.open(0L)
        return true
    }

    /**
     * Every screen the game is told to open (from the mixin). True: don't. Odin's termsim menu,
     * which a finished simulator opens, is kept off when that simulator came from [openNumbersSim]: the
     * finished one is replaced by a new one on the next frame (not inside the finish, which is
     * still running), opened again rather than refilled so Odin starts a new terminal (its solver
     * and its finish) as it would for any. Anything but a simulator (Escape, say) ends it.
     */
    @JvmStatic
    fun cancelScreen(screen: Screen?): Boolean {
        if (!simFromLeap) return false
        if (screen === StartGUI && EngineerClient.mc.screen === NumbersSim) {
            val round = ++simRound
            // From another thread, so it's queued for the next frame rather than run right here.
            Thread.ofVirtual().start {
                EngineerClient.mc.execute {
                    if (simFromLeap && round == simRound && EngineerClient.mc.screen === NumbersSim) NumbersSim.open(0L)
                }
            }
            return true
        }
        if (screen !is TermSimGUI) { simFromLeap = false; simRound++ }
        return false
    }

    /** A simulator from [openNumbersSim] is on screen (no first click protection on it). */
    @JvmStatic
    fun simActive(): Boolean = simFromLeap && EngineerClient.mc.screen is TermSimGUI

    /**
     * Set by POV Previews while its previews are up: Odin's leap boxes shrink toward the middle by
     * [overlayScale] and their background fades by [overlayAlpha] (not the head or the text), so the
     * previews behind stay readable. Read by `LeapMenuRenderMixin`.
     */
    @JvmStatic var overlayScale = 1f
    @JvmStatic var overlayAlpha = 1f

    /** An ARGB colour with its alpha times [overlayAlpha]. */
    @JvmStatic
    fun fade(argb: Int): Int {
        if (overlayAlpha >= 1f) return argb
        val a = ((argb ushr 24) * overlayAlpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    private var openedAt = 0L

    /** Within Click Delay of the leap menu opening. Read by the click mixins. */
    @JvmStatic
    fun inClickDelay(): Boolean = enabled && ShowIn.allows(showIn) && System.currentTimeMillis() - openedAt < clickDelay * 50L

    init {
        on<ScreenEvent.Open> {
            if (screen is AbstractContainerScreen<*> && screen.title.string.equalsOneOf("Spirit Leap", "Teleport to Player")) {
                openedAt = System.currentTimeMillis()
                // The window clicks are ignored for, from this moment (the mixins read inClickDelay).
                EcRec.line("ec.leap") { o ->
                    o.str("title", screen.title.string).num("openedAt", openedAt).num("clickDelayTicks", clickDelay).num("clickDelayMs", clickDelay * 50L)
                        .bool("enabled", enabled).bool("leapMenu", LeapMenu.enabled).bool("outline", leapOutline)
                }
            }
        }

        // Blood rush ends at the blood door, read straight off the network ([ShowIn]).
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (!overlay) ShowIn.onChat(content.string)
        }
        on<LevelEvent.Load> { ShowIn.reset() }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "leap_outline")) { g, _ ->
            EngineerClient.safely("leap outline") { drawOutline(g) }
        }
    }

    /** Odin's Leap Menu "Render Scale", which sizes its boxes. */
    private fun menuScale(): Float = (LeapMenu.settings["Render Scale"] as? NumberSetting<*>)?.value?.toFloat() ?: 1f

    /**
     * The leap menu's four boxes, drawn very faintly while it is closed — the same places, size and
     * scale as the boxes it opens with (Odin draws each from a corner 24 pixels off the middle),
     * each in the class colour of the person the menu will put there (its own list, sorted its own
     * way). Grey where nobody is, or outside a dungeon.
     */
    private fun drawOutline(g: GuiGraphicsExtractor) {
        if (!enabled || !leapOutline || !LeapMenu.enabled || EngineerClient.mc.screen != null || !ShowIn.allows(showIn)) return
        val window = EngineerClient.mc.window
        val halfW = window.guiScaledWidth / 2
        val halfH = window.guiScaledHeight / 2
        val scale = menuScale() * overlayScale
        repeat(4) { i ->
            val clazz = DungeonUtils.leapTeammates.getOrNull(i)?.clazz
            val color = if (clazz == null || clazz == DungeonClass.EMPTY) OUTLINE_GREY else clazz.color.withAlpha(OUTLINE_GREY.alphaFloat)
            val col = i % 2
            val row = i / 2
            val localX = if (col == 0) -LeapMenu.BOX_WIDTH else 0
            val localY = if (row == 0) -LeapMenu.BOX_HEIGHT else 0
            g.pose().pushMatrix()
            g.pose().translate((if (col == 0) halfW - 24 else halfW + 24).toFloat(), (if (row == 0) halfH - 24 else halfH + 24).toFloat())
            g.pose().scale(scale, scale)
            g.roundedFill(localX, localY, localX + LeapMenu.BOX_WIDTH, localY + LeapMenu.BOX_HEIGHT, color.rgba, 9)
            g.pose().popMatrix()
        }
    }

    // --- Map Leap ----------------------------------------------------------------------------

    /** Map Leap is on and this is the clear: the leap menu is the map. */
    @JvmStatic
    fun mapMode(): Boolean = enabled && mapLeap && !mapLeapBroken && DungeonUtils.inDungeons && !DungeonUtils.inBoss

    /**
     * Set the first time drawing or picking throws (an Odin whose map works differently): the leap
     * menu goes back to Odin's own boxes for the rest of the session, rather than failing every frame.
     */
    private var mapLeapBroken = false

    private fun broke(what: String, t: Throwable) {
        mapLeapBroken = true
        EngineerClient.logger.error("[ec] map leap: $what failed - back to Odin's leap boxes for this session", t)
    }

    /** Who can be leapt to, without the placeholder Odin's leap menu fills empty corners with. */
    private fun mapTargets(): List<DungeonPlayer> =
        DungeonUtils.leapTeammates.filter { !(it.clazz == DungeonClass.EMPTY && it.name == "Empty") }

    private fun palette() = LeapMap.Palette(
        background = cBackground.value, unknownRoom = cUnknown.value,
        rooms = mapOf(
            RoomType.NORMAL to cNormal.value, RoomType.PUZZLE to cPuzzle.value, RoomType.TRAP to cTrap.value,
            RoomType.BLOOD to cBlood.value, RoomType.ENTRANCE to cEntrance.value, RoomType.FAIRY to cFairy.value,
            RoomType.CHAMPION to cChampion.value, RoomType.RARE to cRare.value,
        ),
        doors = mapOf(DoorType.Normal to cNormalDoor.value, DoorType.Wither to cWitherDoor.value, DoorType.Blood to cBloodDoor.value, DoorType.Fairy to cFairyDoor.value),
        unopenedDoor = cUnopenedDoor.value,
    )

    /** From `LeapMenuRenderMixin`, for each frame of the leap menu. True: the map is drawn, so Odin's boxes aren't. */
    @JvmStatic
    fun renderMapLeap(event: ScreenEvent.Render): Boolean {
        if (!mapMode()) return false
        return try {
            LeapMap.render(event.guiGraphics, mapTargets(), event.mouseX.toFloat(), event.mouseY.toFloat(), mapLeapSize, palette())
            true
        } catch (t: Throwable) {
            broke("drawing", t); false
        }
    }

    /**
     * From `LeapMenuQuadrantMixin`, for a click (or, in Odin's "On Key Release" mode, a release) in
     * the leap menu. In map mode it leaps to the head under the cursor - or does nothing when there
     * isn't one - through Odin's own `mouseTrigger`. True: handled, Odin's corner pick is skipped.
     */
    @JvmStatic
    fun mapLeapClick(screen: AbstractContainerScreen<*>, x: Int, y: Int): Boolean {
        if (!mapMode()) return false
        return try {
            LeapMap.targetAt(mapTargets(), x.toFloat(), y.toFloat(), mapLeapSize)
                ?.let { OdinLeap.leap(screen, it, DungeonUtils.leapTeammates.indexOf(it)) }
            true
        } catch (t: Throwable) {
            broke("picking", t); false
        }
    }

    /** Copies the map colours from another map mod's config: "Odin", "DTMap" or "Devonian". */
    private fun importMapColors(source: String) {
        try {
            val read: (MapColour) -> Color? = when (source) {
                "Odin" -> ({ c -> c.odin?.invoke() })
                "DTMap" -> {
                    val file = EngineerClient.mc.gameDirectory.resolve("config/dtmap/tabs/Map.txt")
                    if (!file.exists()) { modMessage("DTMap's config wasn't found (config/dtmap/tabs/Map.txt)."); return }
                    // "key: ARGB int" lines.
                    val values = file.readLines().mapNotNull { line ->
                        line.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                    }.toMap()
                    ({ c -> c.dtmap?.let(values::get)?.toIntOrNull()?.let { Color(it) } })
                }
                "Devonian" -> {
                    val file = EngineerClient.mc.gameDirectory.resolve("config/devonianConfig.json")
                    if (!file.exists()) { modMessage("Devonian's config wasn't found (config/devonianConfig.json)."); return }
                    val config = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("config")
                    ({ c -> c.devonian?.let { config?.get(it) }?.takeIf { it.isJsonPrimitive }?.asInt?.let { Color(it) } })
                }
                else -> return
            }
            for (c in mapColours) read(c)?.let { c.setting.value = it }
            modMessage("Map Leap: imported colours from $source.")
        } catch (t: Throwable) {
            modMessage("Map Leap: couldn't read $source's colours (${t.message}).")
        }
    }
}
