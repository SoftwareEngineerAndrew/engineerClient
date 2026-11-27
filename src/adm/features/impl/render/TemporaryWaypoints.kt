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
import com.adm.utils.render.drawBeaconBeam
import com.adm.utils.render.drawText
import com.adm.utils.render.drawWireFrameBox
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max

// Subscribed while Waypoints is on.
object TemporaryWaypoints {
    private val partyRegex =
        Regex("^Party > (?:\\[[^]]*?])? ?(\\w{1,16})(?: [ቾ⚒])?: x: (-?\\d+), y: (-?\\d+), z: (-?\\d+).*") // https://regex101.com/r/8K26A1/1

    private val allChatRegex =
        Regex("^(?:Co-op > )?(?:\\[\\d+] )?(?:\\S+ )?(?:\\[[^]]*?] )?(\\w{1,16})(?: [ቾ⚒])?: x: (-?\\d+), y: (-?\\d+), z: (-?\\d+).*")

    private val temporaryWaypoints = mutableListOf<Waypoint>()

    init {
        on<MessageEvent.Chat> {
            if (!DungeonWaypoints.partyWaypoints || !message.contains("x: ")) return@on
            val match = partyRegex.matchEntire(message)
                ?: (if (DungeonWaypoints.allChatWaypoints && !message.startsWith("Party > ")) allChatRegex.matchEntire(message) else null)
                ?: return@on
            val (name, x, y, z) = match.destructured
            if (name == mc.player?.name?.string && !DungeonWaypoints.personalWaypoints) return@on

            addChatWaypoint("§6$name", x.toIntOrNull() ?: return@on, y.toIntOrNull() ?: return@on, z.toIntOrNull() ?: return@on, DungeonWaypoints.chatWaypointSeconds * 1000L)
        }

        on<RenderExtractEvent> {
            if (temporaryWaypoints.isEmpty()) return@on
            val player = mc.player
            val now = System.currentTimeMillis()
            val arrival = DungeonWaypoints.removeOnArrival
            val iterator = temporaryWaypoints.iterator()
            while (iterator.hasNext()) {
                val w = iterator.next()
                if (now > w.timeAdded + w.duration) { iterator.remove(); continue }
                // Arrival only counts once you have been away from it, so one sent where you stand stays.
                if (arrival && w.chat && player != null) {
                    if (player.blockPosition().distSqr(w.blockPos) >= 25.0) w.away = true
                    else if (w.away) { iterator.remove(); continue }
                }
                if (player == null) continue
                val dist = player.blockPosition().distManhattan(w.blockPos)
                if (dist != w.labelDist) { w.labelDist = dist; w.label = "${w.name} §f(§3${dist}m§f)" }
                drawWireFrameBox(w.box, w.color, depth = false)
                drawBeaconBeam(w.blockPos, w.color)
                drawText(w.label, w.labelPos, max(1f, dist * 0.05f), false)
            }
        }

        on<LevelEvent.Load> {
            temporaryWaypoints.clear()
        }
    }

    private fun addChatWaypoint(name: String, x: Int, y: Int, z: Int, duration: Long) = add(name, x, y, z, duration, true)

    private fun add(name: String, x: Int, y: Int, z: Int, duration: Long, chat: Boolean) {
        if (listOf(x, y, z).any { abs(it) > 5000 }) return modMessage("§cWaypoint out of bounds.")
        if (temporaryWaypoints.any { it.blockPos.x == x && it.blockPos.y == y && it.blockPos.z == z }) return modMessage("§cWaypoint already exists at $x, $y, $z.")
        modMessage("§aAdded temporary waypoint at §6$x§r, §3$y§r, §d$z§r.")
        val color = if (!chat || DungeonWaypoints.randomChatColor) colors.random() else DungeonWaypoints.chatWaypointColor
        temporaryWaypoints.add(Waypoint(name, BlockPos(x, y, z), color, duration, chat))
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
        val chat: Boolean,
        val timeAdded: Long = System.currentTimeMillis()
    ) {
        var away = false
        val box = AABB(blockPos)
        val labelPos: Vec3 = Vec3.atCenterOf(blockPos).add(0.0, 1.7, 0.0)
        var labelDist = -1
        var label = ""
    }
}
