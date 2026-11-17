package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.renderX
import com.adm.utils.renderY
import com.adm.utils.renderZ
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.skyblock.KuudraUtils
import com.adm.utils.skyblock.Supply
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import kotlin.math.*

object PearlWaypoints : Module(
    name = "Pearl Waypoints",
    description = "Renders waypoints for pearls in Kuudra."
) {
    private val dynamicWaypoints by BooleanSetting("Dynamic Waypoints", false, desc = "Renders waypoints dynamically based on your position.")
    private val dynamicWaypointsColor by ColorSetting("Dynamic Color", Colors.MINECRAFT_DARK_PURPLE, true, desc = "Color of the dynamic waypoints.").withDependency { dynamicWaypoints }
    private val presetWaypoints by BooleanSetting("Preset Waypoints", true, desc = "Renders preset waypoints for pearls.")
    private val hideFarWaypoints by BooleanSetting("Hide Far Waypoints", true, desc = "Hides the waypoints that are not the closest to you.").withDependency { presetWaypoints }

    private class Route(val supply: Supply, val color: Color, starts: List<BlockPos>, aims: List<BlockPos>) {
        val starts = starts.toTypedArray()
        val startBoxes = starts.map { AABB(it) }.toTypedArray()
        val aims = aims.toTypedArray()
        val aimBoxes = aims.map { AABB(it) }.toTypedArray()
        val dropMarker = AABB(supply.dropOffSpot.above())
    }

    private val cannonAim = BlockPos(-59, 106, -59)
    private val xAim = BlockPos(-58, 127, -148)
    private val shopAim = BlockPos(-146, 107, -60)
    private val triangleAim = BlockPos(-149, 104, -70)
    private val equalsAim = BlockPos(-168, 124, -118)
    private val slashAim = BlockPos(-65, 109, -162)

    private val routes = arrayOf(
        Route(Supply.Shop, Colors.MINECRAFT_RED, listOf(BlockPos(-71, 79, -135), BlockPos(-86, 78, -129)), listOf(shopAim, BlockPos(-147, 111, -69))),
        Route(Supply.Triangle, Colors.MINECRAFT_LIGHT_PURPLE, listOf(BlockPos(-68, 77, -123)), listOf(triangleAim)),
        Route(Supply.X, Colors.MINECRAFT_YELLOW, listOf(BlockPos(-135, 77, -139)), listOf(BlockPos(-59, 115, -71))),
        Route(Supply.xCannon, Colors.WHITE, listOf(BlockPos(-131, 79, -114)), listOf(cannonAim, BlockPos(-51, 108, -67), BlockPos(-39, 93, -76))),
        Route(Supply.Square, Colors.MINECRAFT_AQUA, listOf(BlockPos(-141, 78, -91)), listOf(cannonAim, xAim, shopAim, triangleAim, equalsAim, slashAim)),
        Route(Supply.Equals, Colors.MINECRAFT_GREEN, listOf(BlockPos(-66, 76, -88)), listOf(equalsAim)),
        Route(Supply.Slash, Colors.MINECRAFT_BLUE, listOf(BlockPos(-115, 77, -69)), listOf(slashAim)),
    )

    private fun squareAimFor(missing: Supply): BlockPos? = when (missing) {
        Supply.xCannon -> cannonAim
        Supply.X -> xAim
        Supply.Shop -> shopAim
        Supply.Triangle -> triangleAim
        Supply.Equals -> equalsAim
        Supply.Slash -> slashAim
        else -> null
    }

    private val order = IntArray(routes.size)
    private val distances = DoubleArray(routes.size)
    private val boxes = BoxBatch()

    init {
        on<RenderExtractEvent> {
            if (KuudraUtils.phase != 1 || !KuudraUtils.inKuudra) return@on
            val player = mc.player ?: return@on
            sortByDistance(player.blockPosition())

            val missing = NoPre.missing
            val anySquareAim = missing == Supply.None || missing == Supply.Square
            val squareAim = squareAimFor(missing)
            boxes.clear()

            for (rank in order.indices) {
                val route = routes[order[rank]]
                if (presetWaypoints) for (box in route.startBoxes) boxes.outline(box, route.color)
                if (hideFarWaypoints && rank > 0) continue

                var shown = false
                for (i in route.aims.indices) {
                    if (!anySquareAim && route.supply == Supply.Square && route.aims[i] != squareAim) continue
                    if (presetWaypoints) boxes.filled(route.aimBoxes[i], route.color)
                    shown = true
                }
                if (shown && dynamicWaypoints) {
                    val target = if (route.supply == Supply.Square) missing else route.supply
                    aimMarkers(player.renderX, player.renderY, player.renderZ, target.dropOffSpot, dynamicWaypointsColor)
                    boxes.outline(route.dropMarker, dynamicWaypointsColor)
                }
            }
            drawBoxes(boxes)
        }
    }

    private fun sortByDistance(from: BlockPos) {
        for (i in routes.indices) {
            var best = Double.MAX_VALUE
            for (start in routes[i].starts) best = min(best, start.distSqr(from))
            distances[i] = best
            var j = i
            while (j > 0 && distances[order[j - 1]] > best) {
                order[j] = order[j - 1]
                j--
            }
            order[j] = i
        }
    }

    private const val GRAVITY = 0.05
    private const val SPEED = 1.67

    private fun aimMarkers(px: Double, py: Double, pz: Double, target: BlockPos, color: Color) {
        val dx = target.x - px
        val dy = target.y - (py + 1.62)
        val dz = target.z - pz
        val flat = hypot(dx, dz)

        val v2 = SPEED * SPEED
        val g = GRAVITY * (1.0 + flat * 0.0012)
        val disc = v2 * v2 - g * (g * flat * flat + 2 * dy * v2)
        if (disc < 0) {
            marker(0.0, 9.0, 0.0, color)
            marker(0.0, 9.0, 0.0, color)
            return
        }

        val root = sqrt(disc)
        val a = Math.toDegrees(atan((v2 + root) / (g * flat)))
        val b = Math.toDegrees(atan((v2 - root) / (g * flat)))
        val yaw = -atan2(dx, dz)
        aimPoint(max(a, b), yaw, px, py, pz, 0.0, color)
        aimPoint(min(a, b), yaw, px, py, pz, 1.2, color)
    }

    private fun aimPoint(angle: Double, yaw: Double, px: Double, py: Double, pz: Double, lift: Double, color: Color) {
        if (!(angle > 0.0)) return marker(0.0, 10.0, 0.0, color)
        val pitch = Math.toRadians(-angle)
        val horizontal = cos(pitch)
        marker(px - horizontal * sin(yaw) * 10, py + lift - sin(pitch) * 10, pz + horizontal * cos(yaw) * 10, color)
    }

    private fun marker(x: Double, y: Double, z: Double, color: Color) {
        boxes.filled(AABB(x - 0.06, y - 0.06, z - 0.06, x + 0.06, y + 0.06, z + 0.06), color)
    }
}
