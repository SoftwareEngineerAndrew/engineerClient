package com.adm.features.impl.render

import com.adm.AdmMod.mc
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.modMessage
import com.adm.utils.render.drawCustomBeacon
import net.minecraft.core.BlockPos
import kotlin.math.abs

// Subscribed while Waypoints is on.
object TemporaryWaypoints {
    private val partyRegex =
        Regex("^Party > (?:\\[[^]]*?])? ?(\\w{1,16})(?: [ቾ⚒])?: x: (-?\\d+), y: (-?\\d+), z: (-?\\d+).*") // https://regex101.com/r/8K26A1/1

    private val temporaryWaypoints = mutableListOf<Waypoint>()

    init {
        on<MessageEvent.Chat> {
            if (!DungeonWaypoints.partyWaypoints) return@on
            val (name, x, y, z) = partyRegex.matchEntire(message)?.destructured ?: return@on
            if (name == mc.player?.name?.string) return@on

            addTempWaypoint("§6$name", x.toIntOrNull() ?: return@on, y.toIntOrNull() ?: return@on, z.toIntOrNull() ?: return@on)
        }

        on<RenderExtractEvent> {
            temporaryWaypoints.removeAll {
                drawCustomBeacon(it.name, it.blockPos, it.color)
                System.currentTimeMillis() > it.timeAdded + it.duration
            }
        }

        on<LevelEvent.Load> {
            temporaryWaypoints.clear()
        }
    }

    fun addTempWaypoint(name: String = "Waypoint", x: Int, y: Int, z: Int, duration: Long = 60_000) {
        if (listOf(x, y, z).any { abs(it) > 5000 }) return modMessage("§cWaypoint out of bounds.")
        if (temporaryWaypoints.any { it.blockPos.x == x && it.blockPos.y == y && it.blockPos.z == z }) return modMessage("§cWaypoint already exists at $x, $y, $z.")
        modMessage("§aAdded temporary waypoint at §6$x§r, §3$y§r, §d$z§r.")
        temporaryWaypoints.add(Waypoint(name, BlockPos(x, y, z), colors.random(), duration))
    }

    private val colors = listOf(
        Colors.MINECRAFT_GOLD, Colors.MINECRAFT_GREEN,
        Colors.MINECRAFT_LIGHT_PURPLE, Colors.MINECRAFT_DARK_AQUA,
        Colors.MINECRAFT_YELLOW, Colors.MINECRAFT_DARK_RED,
        Colors.WHITE, Colors.MINECRAFT_DARK_PURPLE,  Colors.MINECRAFT_BLUE,
        Colors.MINECRAFT_YELLOW, Colors.MINECRAFT_RED,
        Colors.MINECRAFT_LIGHT_PURPLE, Colors.MINECRAFT_DARK_GREEN,
    )

    private data class Waypoint(
        val name: String,
        val blockPos: BlockPos,
        val color: Color,
        val duration: Long,
        val timeAdded: Long = System.currentTimeMillis()
    )
}
