package com.ascent.waypoints

import com.ascent.AscentMod
import com.odtheking.odin.OdinMod.scope
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.*
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.InputEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.SecretPickupEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import org.lwjgl.glfw.GLFW

/**
 * Ascent's own dungeon-waypoint module — a sibling of Odin's DungeonWaypoints,
 * registered into Odin's module system (own ClickGUI panel "Ascent", own config,
 * own pack folder, own edit mode). Odin's module is never touched: both systems
 * store, select, and render waypoints independently and can run side by side.
 *
 * VENDORED from Odin `dungeonwaypoints/DungeonWaypoints.kt` (upstream 0.3.1 — see
 * VENDORED.md). Reuses Odin's public DungeonWaypoint/WaypointType types so the
 * renderer helpers and pack file format stay shared. The one structural change:
 * upstream stores the current room's world-space waypoints on Odin's DungeonRoom
 * (`room.waypoints`), which Odin's own renderer draws — writing it would merge us
 * into Odin's rendering, so Ascent keeps its own [roomWaypoints] instead.
 */
object AscentWaypoints : Module(
    name = "Ascent Waypoints",
    category = Category.custom("Ascent"),
    description = "Ascent's profile-driven blood-rush waypoints. Separate from Odin's Dungeon Waypoints."
) {
    var allowEdits by BooleanSetting("Allow Edits", false, desc = "Allows you to edit Ascent waypoints.")
    val allowTextEdit by BooleanSetting("Allow Text Edit", false, desc = "Allows you to set the text of a waypoint while sneaking.").withDependency { allowEdits }

    val titleScale by NumberSetting("Title Scale", 1f, 0.1f, 4f, increment = 0.1f, desc = "The scale of the titles of waypoints.")
    val disableDepth by BooleanSetting("Global Depth", false, desc = "Disables depth testing for all waypoints.")

    private val editorHud by HUD("Editor HUD", "Shows information about the Ascent waypoint you're placing or looking at.", false) {
        drawAscentWaypointEditorHud(it)
    }

    private val settingsDropDown by DropdownSetting("Next Waypoint Settings")
    var waypointType by SelectorSetting("Waypoint Type", WaypointType.NONE.displayName, WaypointType.entries.map { it.displayName }, desc = "The type of waypoint you want to place.").withDependency { settingsDropDown }
    var color by ColorSetting("Color", Colors.MINECRAFT_GREEN, true, desc = "The color of the next waypoint you place.").withDependency { settingsDropDown }
    var filled by BooleanSetting("Filled", false, desc = "If the next waypoint you place should be 'filled'.").withDependency { settingsDropDown }
    var depthCheck by BooleanSetting("Depth check", false, desc = "Whether the next waypoint you place should have a depth check.").withDependency { settingsDropDown }
    var useBlockSize by BooleanSetting("Use block size", true, desc = "Use the size of the block you click for waypoint size.").withDependency { settingsDropDown }
    var sizeX by NumberSetting("Size X", 1.0, .1, 5.0, 0.01, desc = "The X size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }
    var sizeY by NumberSetting("Size Y", 1.0, .1, 5.0, 0.01, desc = "The Y size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }
    var sizeZ by NumberSetting("Size Z", 1.0, .1, 5.0, 0.01, desc = "The Z size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }

    private val editModeSettings by DropdownSetting("Edit Mode Settings")
    private var presetNone by ColorSetting("None Color", Colors.MINECRAFT_GREEN, true, "Color for \"None\" Waypoints").withDependency { editModeSettings }
    private var presetNormal by ColorSetting("Normal Color", Colors.MINECRAFT_RED, true, "Color for Normal Waypoints").withDependency { editModeSettings }
    private var presetSecret by ColorSetting("Secret Color", Colors.MINECRAFT_BLUE, true, "Color for cyclable preset 3.").withDependency { editModeSettings }
    private var presetEtherwarp by ColorSetting("Etherwarp Color", Colors.MINECRAFT_GOLD, true, "Color for cyclable preset 4.").withDependency { editModeSettings }
    private var cycleWaypointType by KeybindSetting("Cycle Waypoint", GLFW.GLFW_KEY_UNKNOWN, "Keybind to cycle the waypoint type.").withDependency { editModeSettings }
        .onPress {
            if (!allowEdits) return@onPress
            when (waypointType) {
                0 -> { color = presetNormal; AscentMod.chat("§8[§6Ascent§8]§a waypoint type changed to §cNormal§a."); waypointType++ }
                1 -> { color = presetSecret; AscentMod.chat("§8[§6Ascent§8]§a waypoint type changed to §cSecret§a."); waypointType++ }
                2 -> { color = presetEtherwarp; AscentMod.chat("§8[§6Ascent§8]§a waypoint type changed to §cEtherwarp§a."); waypointType++ }
                3 -> { color = presetNone; AscentMod.chat("§8[§6Ascent§8]§a waypoint type changed to §cNone§a."); waypointType = 0 }
            }
        }

    var selectedPackIds by ListSetting("Selected Waypoint Packs", mutableListOf<String>()).hide()
    var editPackId by StringSetting("Edit Waypoint Pack", "", length = 256, desc = "").hide()
    var loadedPacks: MutableMap<String, MutableMap<String, MutableList<DungeonWaypoint>>> = mutableMapOf()
    var allActiveWaypoints: MutableMap<String, MutableList<DungeonWaypoint>> = mutableMapOf()

    /**
     * The current room's waypoints in WORLD coordinates — Ascent's replacement for
     * upstream's `room.waypoints` field on Odin's DungeonRoom. Rebuilt by
     * [applyCurrentRoom] on room entry and after every edit/pack change.
     */
    @Volatile
    var roomWaypoints: MutableSet<DungeonWaypoint> = mutableSetOf()

    private val resetButton by ActionSetting("Reset Current Room", desc = "Resets the Ascent waypoints for the current room.") {
        val room = DungeonUtils.currentRoom ?: return@ActionSetting AscentMod.chat("§8[§6Ascent§8]§c room not found!")
        val waypoints = getEditableWaypoints(room)
        if (waypoints.isEmpty()) return@ActionSetting AscentMod.chat("§8[§6Ascent§8]§c current room has no editable Ascent waypoints!")
        waypoints.clear()
        syncRoomToActive(room)
        scope.launch { saveWaypoints() }
        AscentMod.chat("§8[§6Ascent§8]§a reset current room.")
    }

    var lastEtherPos: BlockPos? = null
    var lastEtherTime = 0L

    init {
        onReceive<ClientboundPlayerPositionPacket> {
            AscentSecretWaypoints.onEtherwarp(this)
        }

        on<SecretPickupEvent.Bat> { AscentSecretWaypoints.onSecret(this) }
        on<SecretPickupEvent.Item> { AscentSecretWaypoints.onSecret(this) }
        on<SecretPickupEvent.Interact> { AscentSecretWaypoints.onSecret(this) }

        on<RoomEnterEvent> {
            room?.let { applyRoom(it) } ?: run { roomWaypoints = mutableSetOf() }
        }

        on<LevelEvent.Load> {
            scope.launch(Dispatchers.IO) { loadWaypoints() }
            resetClickedWaypoints()
            lastEtherPos = null
            lastEtherTime = 0L
        }

        on<RenderEvent.Extract> {
            renderAscentWaypoints(this)
        }

        on<InputEvent> {
            handleAscentEditorInput(this)
        }
    }

    override fun onKeybind() {
        allowEdits = !allowEdits
        AscentMod.chat("§8[§6Ascent§8]§r waypoint editing ${if (allowEdits) "§aenabled" else "§cdisabled"}§r!")
    }
}
