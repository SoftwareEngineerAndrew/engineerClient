package com.adm.features.impl.dungeon.dungeonwaypoints

import com.engineerclient.dungeon.BossWaypoints
import com.github.stivais.commodore.parsers.CommandParsable
import com.mojang.blaze3d.platform.InputConstants
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.*
import com.adm.events.*
import com.adm.events.core.EventBus
import com.adm.events.core.EventPriority
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.features.impl.render.TemporaryWaypoints
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.modMessage
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.phys.AABB

object DungeonWaypoints : Module(
    name = "Waypoints",
    description = "Room, boss and party waypoints."
) {
    var allowEdits by BooleanSetting("Allow Edits", false, desc = "Allows you to edit waypoints.")
    val allowTextEdit by BooleanSetting("Allow Text Edit", false, desc = "Allows you to set the text of a waypoint while sneaking.").withDependency { allowEdits }

    val titleScale by NumberSetting("Title Scale", 1f, 0.1..4.0, increment = 0.1f, desc = "The scale of the titles of waypoints.")
    val disableDepth by BooleanSetting("Global Depth", false, desc = "Disables depth testing for all waypoints.")
    val hideWhenDone by BooleanSetting("Hide In Green Rooms", false, desc = "Hides a room's waypoints once its checkmark is green.")
    val numberEtherwarps by BooleanSetting("Number Etherwarps", false, desc = "Labels etherwarp waypoints with their order in the room.")

    private val editorHud by HUD("Editor HUD", "Shows information about the waypoint you're placing or looking at.", false) {
        EditorHud.draw(this, it)
    }

    private val settingsDropDown by DropdownSetting("Next Waypoint Settings", desc = "Shows settings for the next waypoint you place.")
    var waypointType by SelectorSetting("Waypoint Type", WaypointType.NONE, desc = "The type of waypoint you want to place.").withDependency { settingsDropDown }
    var color by ColorSetting("Color", Colors.MINECRAFT_GREEN, true, desc = "The color of the next waypoint you place.").withDependency { settingsDropDown }
    var filled by BooleanSetting("Filled", false, desc = "If the next waypoint you place should be 'filled'.").withDependency { settingsDropDown }
    var depthCheck by BooleanSetting("Depth check", false, desc = "Whether the next waypoint you place should have a depth check.").withDependency { settingsDropDown }
    var useBlockSize by BooleanSetting("Use block size", true, desc = "Use the size of the block you click for waypoint size.").withDependency { settingsDropDown }
    var sizeX by NumberSetting("Size X", 1.0, .1..5.0, 0.01, desc = "The X size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }
    var sizeY by NumberSetting("Size Y", 1.0, .1..5.0, 0.01, desc = "The Y size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }
    var sizeZ by NumberSetting("Size Z", 1.0, .1..5.0, 0.01, desc = "The Z size of the next waypoint you place.").withDependency { !useBlockSize && settingsDropDown }

    private val editModeSettings by DropdownSetting("Edit Mode Settings", desc = "Shows color settings for the cyclable edit-mode presets.")
    private var presetNone by ColorSetting("None Color", Colors.MINECRAFT_GREEN, true, "Color for \"None\" Waypoints").withDependency { editModeSettings }
    private var presetNormal by ColorSetting("Normal Color", Colors.MINECRAFT_RED, true, "Color for Normal Waypoints").withDependency { editModeSettings }
    private var presetSecret by ColorSetting("Secret Color", Colors.MINECRAFT_BLUE, true, "Color for cyclable preset 3.").withDependency { editModeSettings }
    private var presetEtherwarp by ColorSetting("Etherwarp Color", Colors.MINECRAFT_GOLD, true, "Color for cyclable preset 4.").withDependency { editModeSettings }
    private var cycleWaypointType by KeybindSetting("Cycle Waypoint", InputConstants.UNKNOWN, "Keybind to cycle the waypoint type.").withDependency { editModeSettings }
        .onPress { if (allowEdits) cycleType() }

    var selectedPackIds by ListSetting("Selected Waypoint Packs", mutableListOf<String>())
    var editPackId by StringSetting("Edit Waypoint Pack", "", length = 256, desc = "", placeholder = "").hide()

    private val resetButton by ActionSetting("Reset Current Room", desc = "Resets the waypoints for the current room.") {
        val room = DungeonUtils.currentRoom ?: return@ActionSetting modMessage("§cRoom not found!")
        val own = WaypointStore.editable(room)
        if (own.isNullOrEmpty()) return@ActionSetting modMessage("§cCurrent room does not have any editable waypoints!")
        own.clear()
        WaypointStore.roomEdited(room)
        modMessage("§aSuccessfully reset current room!")
    }

    val partyWaypoints by BooleanSetting("Party Waypoints", true, desc = "Adds waypoints from coordinates party members send.")
    val allChatWaypoints by BooleanSetting("From All Chat", false, desc = "Also from coordinates sent in all and co-op chat.").withDependency { partyWaypoints }
    val personalWaypoints by BooleanSetting("Personal Waypoints", false, desc = "Also from coordinates you send.").withDependency { partyWaypoints }
    val chatWaypointSeconds by NumberSetting("Chat Waypoint Duration", 60, 5..600, 5, desc = "How long a chat waypoint stays.", unit = "s").withDependency { partyWaypoints }
    val removeOnArrival by BooleanSetting("Remove On Arrival", true, desc = "A chat waypoint goes once you are within 5 blocks.").withDependency { partyWaypoints }
    val randomChatColor by BooleanSetting("Random Waypoint Color", true, desc = "Each chat waypoint gets its own color.").withDependency { partyWaypoints }
    val chatWaypointColor by ColorSetting("Chat Waypoint Color", Colors.MINECRAFT_GOLD, true, desc = "Color of chat waypoints.").withDependency { partyWaypoints && !randomChatColor }

    init {
        // Boss waypoint commands register on load, whether or not this is on.
        BossWaypoints
        TemporaryWaypoints

        onReceive<ClientboundPlayerPositionPacket> { SecretWaypoints.onTeleport(this) }
        on<SecretPickupEvent.Interact> { SecretWaypoints.onPickup(blockPos, 0) }
        on<SecretPickupEvent.Item> { SecretWaypoints.onPickup(entity.blockPosition(), 3) }
        on<SecretPickupEvent.Bat> { SecretWaypoints.onPickup(BlockPos.containing(packet.x, packet.y, packet.z), 5) }

        on<LevelEvent.Load> {
            if (!WaypointStore.loaded) WaypointStore.reloadIfChanged()
            WaypointStore.clearClicks()
            SecretWaypoints.forgetEtherwarp()
        }

        on<LocationChangeEvent> { if (DungeonUtils.inDungeons) WaypointStore.reloadIfChanged() }

        on<RenderExtractEvent>(EventPriority.HIGHEST) { WaypointView.render(this) }
        on<InputEvent> { WaypointEditor.onInput(this) }
    }

    private fun cycleType() {
        val next = WaypointType.entries[(waypointType.ordinal + 1) % WaypointType.entries.size]
        waypointType = next
        color = when (next) {
            WaypointType.NONE -> presetNone
            WaypointType.NORMAL -> presetNormal
            WaypointType.SECRET -> presetSecret
            WaypointType.ETHERWARP -> presetEtherwarp
        }.copy()
        modMessage("§aWaypoint type changed to §c${next.label}§a.")
    }

    @CommandParsable
    enum class WaypointType {
        NONE, NORMAL, SECRET, ETHERWARP
    }

    // Field names and order are the saved pack format.
    data class DungeonWaypoint(
        val blockPos: BlockPos, val color: Color, val filled: Boolean,
        val depth: Boolean, val aabb: AABB, val title: String? = null,
        var type: WaypointType? = null, @Transient var isClicked: Boolean = false,
    ) {
        inline val isSecret: Boolean get() = type == WaypointType.SECRET
    }

    override fun onEnable() {
        super.onEnable()
        EventBus.subscribe(BossWaypoints)
        EventBus.subscribe(TemporaryWaypoints)
    }

    override fun onDisable() {
        super.onDisable()
        EventBus.unsubscribe(BossWaypoints)
        EventBus.unsubscribe(TemporaryWaypoints)
    }

    override fun onKeybind() {
        allowEdits = !allowEdits
        modMessage("Waypoint editing ${if (allowEdits) "§aenabled" else "§cdisabled"}§r!")
    }
}
