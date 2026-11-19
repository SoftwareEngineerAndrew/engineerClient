package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.MessageEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.formatTime
import com.adm.utils.modMessage
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBeaconBeam
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.KuudraUtils
import com.adm.utils.skyblock.Supply
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

object SupplyHelper : Module(
    name = "Supply Helper",
    description = "Provides visual aid for supply drops in Kuudra."
) {
    private val suppliesWaypoints by BooleanSetting("Supplies Waypoints", true, desc = "Renders the supply waypoints.")
    private val supplyWaypointColor by ColorSetting("Supply Waypoint Color", Colors.MINECRAFT_YELLOW, true, desc = "Color of the supply waypoints.").withDependency { suppliesWaypoints }
    private val supplyDropWaypoints by BooleanSetting("Supply Drop Waypoints", true, desc = "Renders the supply drop waypoints.")
    private val sendSupplyTime by BooleanSetting("Send Supply Time", true, desc = "Sends a message when a supply is collected.")
    private val renderArea by BooleanSetting("Render Area", true, desc = "Renders the area where supplies can be collected.").withDependency { supplyDropWaypoints }

    private val recovered = Regex("(?:\\[[^]]*])? ?(\\w{1,16}) recovered one of Elle's supplies! \\((\\d)/(\\d)\\)")
    private var runStartedAt = 0L

    private class Spot(val supply: Supply, val pos: BlockPos) {
        val box = AABB(pos)
        val labelAt: Vec3 = Vec3.atCenterOf(pos).add(0.0, 1.7, 0.0)
    }

    private val dropSpots = Supply.entries.filter { it != Supply.None && it != Supply.Square }.map { Spot(it, it.dropOffSpot) }
    private val areaLabels = Supply.entries.map { "§e${it.name}" to Vec3.atCenterOf(it.pickUpSpot) }

    // One reusable slot per giant: the spot only moves when the giant does.
    private class PickUp {
        var x = Int.MIN_VALUE
        var z = Int.MIN_VALUE
        lateinit var pos: BlockPos
        lateinit var box: AABB
        lateinit var labelAt: Vec3
        var dist = -1
        var label = ""

        fun moveTo(nx: Int, nz: Int) {
            if (nx == x && nz == z) return
            x = nx; z = nz
            pos = BlockPos(nx, 73, nz)
            box = AABB(pos)
            labelAt = Vec3.atCenterOf(pos).add(0.0, 1.7, 0.0)
        }

        fun labelFor(d: Int): String {
            if (d != dist) { dist = d; label = "Pick Up! §f(§3${d}m§f)" }
            return label
        }
    }

    private val pickUps = ArrayList<PickUp>()
    private val boxes = BoxBatch()

    init {
        on<MessageEvent.Chat> {
            if (!sendSupplyTime || !KuudraUtils.inKuudra) return@on
            if (message == "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!") {
                runStartedAt = System.currentTimeMillis()
                return@on
            }
            if (KuudraUtils.phase != 1 || !message.contains(" recovered one of Elle's supplies! (")) return@on
            val (name, current, total) = recovered.matchEntire(message)?.destructured ?: return@on
            modMessage("§6$name §a§lrecovered a supply in ${formatTime(System.currentTimeMillis() - runStartedAt)}! §r§8($current/$total)", "")
            cancel()
        }

        on<RenderExtractEvent> {
            val phase = KuudraUtils.phase
            if (phase != 1 && phase != 3 || !KuudraUtils.inKuudra) return@on
            val player = mc.player ?: return@on
            boxes.clear()

            if (supplyDropWaypoints && phase == 1) {
                val missing = NoPre.missing
                for (spot in dropSpots) {
                    if (!spot.supply.isActive) continue
                    val color = if (missing == spot.supply) Colors.MINECRAFT_GREEN else Colors.MINECRAFT_RED
                    boxes.outline(spot.box, color)
                    beacon(spot.pos, color, "§ePlace Here!", spot.labelAt)
                }
            }

            if (suppliesWaypoints) {
                val giants = KuudraUtils.giantZombies
                while (pickUps.size < giants.size) pickUps.add(PickUp())
                val color = supplyWaypointColor
                val feet = player.blockPosition()
                for (i in giants.indices) {
                    val giant = giants[i]
                    val angle = (giant.yRot + 130) * (Math.PI / 180)
                    val spot = pickUps[i]
                    spot.moveTo((giant.x + 3.7 * cos(angle)).toInt(), (giant.z + 3.7 * sin(angle)).toInt())
                    boxes.outline(spot.box, color)
                    beacon(spot.pos, color, spot.labelFor(feet.distManhattan(spot.pos)), spot.labelAt)
                }
            }

            drawBoxes(boxes)

            if (renderArea && phase == 1) for ((text, at) in areaLabels) drawText(text, at, 2f, true)
        }
    }

    private fun RenderExtractEvent.beacon(pos: BlockPos, color: Color, label: String, labelAt: Vec3) {
        drawBeaconBeam(pos, color)
        drawText(label, labelAt, 2f, false)
    }
}
