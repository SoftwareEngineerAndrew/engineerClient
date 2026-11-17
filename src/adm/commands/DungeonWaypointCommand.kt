package com.adm.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import com.github.stivais.commodore.utils.SyntaxException
import com.adm.AdmMod.mc
import com.adm.AdmMod.scope
import com.adm.clickgui.settings.impl.label
import com.adm.config.DungeonWaypointConfig
import com.adm.config.WaypointPackFileUtils
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints
import com.adm.features.impl.dungeon.dungeonwaypoints.SecretWaypoints
import com.adm.features.impl.dungeon.dungeonwaypoints.WaypointPackSelectorScreen
import com.adm.features.impl.dungeon.dungeonwaypoints.WaypointStore
import com.adm.utils.Color
import com.adm.utils.modMessage
import com.adm.utils.setClipboardContent
import kotlinx.coroutines.launch

private val hexColor = Regex("[0-9A-Fa-f]{8}")

val dungeonWaypointsCommand = Commodore("dwp", "dungeonwaypoints") {
    runs {
        mc.schedule { mc.setScreenAndShow(WaypointPackSelectorScreen(mc.gui.screen())) }
    }

    literal("fill").runs {
        DungeonWaypoints.filled = !DungeonWaypoints.filled
        modMessage("Fill status changed to: ${DungeonWaypoints.filled}")
    }

    literal("size").runs { sizeX: Double, sizeY: Double, sizeZ: Double ->
        if (sizeX !in 0.1..1.0 || sizeY !in 0.1..1.0 || sizeZ !in 0.1..1.0) return@runs modMessage("§cSize must be between 0.1 and 1.0!")
        DungeonWaypoints.sizeX = sizeX
        DungeonWaypoints.sizeY = sizeY
        DungeonWaypoints.sizeZ = sizeZ
        modMessage("Size changed to: ${sizeX}, ${sizeY}, $sizeZ")
    }

    literal("resetsecrets").runs {
        SecretWaypoints.resetSecrets()
        modMessage("§aSecrets have been reset!")
    }

    literal("type").runs { type: DungeonWaypoints.WaypointType ->
        DungeonWaypoints.waypointType = type
        modMessage("Waypoint type changed to: ${type.label}")
    }

    literal("useblocksize").runs {
        DungeonWaypoints.useBlockSize = !DungeonWaypoints.useBlockSize
        modMessage("Use block size status changed to: ${DungeonWaypoints.useBlockSize}")
    }

    literal("depth").runs {
        DungeonWaypoints.depthCheck = !DungeonWaypoints.depthCheck
        modMessage("Next waypoint will be added with depth check: ${DungeonWaypoints.depthCheck}")
    }

    literal("color").executable {
        param("hex").parser { hex: String ->
            if (!hex.matches(hexColor)) throw SyntaxException("Color hex not properly formatted! Use format RRGGBBAA")
            Color(hex)
        }

        runs { hex: Color ->
            DungeonWaypoints.color = hex
            modMessage("Color changed to: ${hex.hex()}")
        }
    }

    literal("export").runs {
        val pack = WaypointStore.editPackCopy()
        scope.launch {
            val encoded = DungeonWaypointConfig.encodeWaypoints(pack)
            mc.execute {
                if (encoded == null) return@execute modMessage("Failed to write waypoint config to clipboard.")
                setClipboardContent(encoded)
                modMessage("Wrote waypoint config to clipboard.")
            }
        }
    }

    literal("import").runs { importString: GreedyString? ->
        val clipboard = mc.keyboardHandler.clipboard
        scope.launch {
            val waypoints = DungeonWaypointConfig.decodeWaypoints(importString?.string, clipboard)
                ?: return@launch modMessage("§cFailed to decode waypoints from clipboard. §fIs the data valid?")
            val taken = WaypointPackFileUtils.packNames()
            var n = 1
            while ((if (n == 1) "Imported" else "Imported $n") in taken) n++
            val name = if (n == 1) "Imported" else "Imported $n"
            if (!WaypointStore.importPack(name, waypoints)) return@launch modMessage("§cFailed to create pack '$name'.")
            modMessage("Imported waypoints as new pack '$name'!${if (!DungeonWaypoints.enabled) " §7(Make sure to enable the Waypoints module)" else ""}")
        }
    }
}
