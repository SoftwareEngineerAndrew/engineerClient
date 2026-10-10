package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.Player

/**
 * F7 P1/P2: Maxor and Storm aggro onto whoever is closest, so a see-through sphere around the boss
 * through the closest player is the aggro boundary: step inside it and you take aggro. When YOU are
 * the closest, it goes through the 2nd closest instead - the margin you have before someone else
 * takes it - in its own colour.
 *
 * The phase that is actually running comes from the boss dialogue (Odin's phase is just your
 * height), and the sphere only shows while you are in that phase's arena: fall below Maxor's
 * platform during P1, or below Storm's during P2, and it disappears instead of switching to the
 * next boss. Before any boss line has been seen (joined mid-fight) it goes by height alone.
 *
 * The boss is the nearest live WitherBoss to you, preferring one whose name mentions the boss -
 * the other withers sit in their own arenas further down, so nearest is the one you're fighting.
 */
object AgroSphere : Module(
    name = "Agro Sphere",
    category = Category.custom("Engineer Client", 860, 10),
    description = "In F7 P1/P2, a see-through sphere around Maxor/Storm through the closest player: the aggro boundary. If you're closest, it shows your margin to the 2nd closest.",
    key = null,
) {
    private val sphereColor by ColorSetting("Sphere Color", Color(255, 85, 85, 0.18f), true, desc = "Sphere colour when someone else has aggro.")
    private val aggroColor by ColorSetting("Sphere Color (Your Aggro)", Color(85, 255, 85, 0.18f), true, desc = "Sphere colour when you are the closest and it shows the 2nd closest.")

    private const val LAT_BANDS = 24
    private const val LON_BANDS = 48

    private var boss: WitherBoss? = null
    /** The party members alive near the boss this tick. */
    private var players: List<Player> = emptyList()

    /** The boss phase that is running, from chat; null before the first boss line or after Storm. */
    private var activePhase: M7Phases? = null
    private var sawBossLine = false
    private val maxorRegex = Regex("^\\[BOSS] Maxor: ")
    private val stormStartRegex = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")
    private val stormEndRegex = Regex("^\\[BOSS] Storm: I should have known that I stood no chance\\.$")
    private val goldorStartRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")

    data class Member(val isMe: Boolean, val distance: Double)
    data class Result(val radius: Double, val youHaveAggro: Boolean)

    /**
     * The sphere's radius from every live party member's distance to the boss (order doesn't
     * matter): the closest's, or the 2nd closest's when you are the closest. Null: no one to measure.
     */
    fun radius(distances: List<Member>): Result? {
        val sorted = distances.sortedBy { it.distance }
        val closest = sorted.firstOrNull() ?: return null
        if (!closest.isMe) return Result(closest.distance, youHaveAggro = false)
        val second = sorted.getOrNull(1) ?: return null
        return Result(second.distance, youHaveAggro = true)
    }

    init {
        on<LevelEvent.Load> { activePhase = null; sawBossLine = false }

        on<MessageEvent.Chat> {
            when {
                stormStartRegex.matches(message) -> { activePhase = M7Phases.P2; sawBossLine = true }
                stormEndRegex.matches(message) || goldorStartRegex.matches(message) -> { activePhase = null; sawBossLine = true }
                maxorRegex.containsMatchIn(message) && !sawBossLine -> { activePhase = M7Phases.P1; sawBossLine = true }
            }
        }

        on<TickEvent.End> {
            val here = DungeonUtils.getF7Phase()
            val phase = if (sawBossLine) activePhase?.takeIf { it == here } else here
            val name = when (phase) {
                M7Phases.P1 -> "Maxor"
                M7Phases.P2 -> "Storm"
                else -> null
            }
            if (name == null) { boss = null; players = emptyList(); return@on }
            val me = mc.player ?: return@on

            val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { it.isAlive && Witherborn.isBoss(it) }
            boss = withers.filter { it.name.string.contains(name, true) }.minByOrNull { it.distanceToSqr(me) }
                ?: withers.minByOrNull { it.distanceToSqr(me) }
            if (boss == null) { players = emptyList(); return@on }

            players = DungeonUtils.dungeonTeammates.mapNotNull { teammate ->
                if (teammate.isDead) return@mapNotNull null
                teammate.entity?.takeIf { it.isAlive } ?: level.players().firstOrNull { it.name.string == teammate.name }
            }
        }

        on<RenderExtractEvent> {
            val boss = boss?.takeIf { it.isAlive } ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val center = boss.getPosition(pt)
            // Measured per frame from interpolated positions, so the sphere moves smoothly
            // instead of stepping each tick; feet-to-feet distance.
            val members = players.filter { it.isAlive }.map { Member(it === me, it.getPosition(pt).distanceTo(center)) }
            val result = radius(members) ?: return@on
            val color = if (result.youHaveAggro) aggroColor else sphereColor
            drawSphere(context.poseStack(), context.submitNodeCollector(), center, result.radius, color)
        }
    }

    /** Translucent, depth-tested, not culled: reads from inside the sphere as well as outside. */
    private fun drawSphere(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        center: net.minecraft.world.phys.Vec3,
        radius: Double,
        color: Color,
    ) {
        if (radius <= 0.05) return
        val cam = mc.gameRenderer.mainCamera().position()
        pose.pushPose()
        pose.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z)
        val argb = color.rgba
        val r = radius.toFloat()
        fun point(lat: Int, lon: Int): FloatArray {
            val theta = Math.PI * lat / LAT_BANDS          // 0 at the top, PI at the bottom
            val phi = 2 * Math.PI * lon / LON_BANDS
            return floatArrayOf(
                (r * Math.sin(theta) * Math.cos(phi)).toFloat(),
                (r * Math.cos(theta)).toFloat(),
                (r * Math.sin(theta) * Math.sin(phi)).toFloat(),
            )
        }
        collector.submitCustomGeometry(pose, RenderTypes.debugQuads()) { last, buffer ->
            for (lat in 0 until LAT_BANDS) for (lon in 0 until LON_BANDS) {
                for (v in arrayOf(point(lat, lon), point(lat + 1, lon), point(lat + 1, lon + 1), point(lat, lon + 1))) {
                    buffer.addVertex(last, v[0], v[1], v[2]).setColor(argb)
                }
            }
        }
        pose.popPose()
    }
}
