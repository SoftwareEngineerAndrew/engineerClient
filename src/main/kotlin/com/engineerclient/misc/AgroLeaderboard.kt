package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.player.PlayerSkin
import java.util.Locale

/**
 * F7 P1/P2: Maxor and Storm aggro onto whoever is closest, so this lists the party by distance
 * to the boss, closest (the one holding aggro) in green. Hidden outside those two phases.
 *
 * The phase that is actually running comes from the boss dialogue (Odin's phase is just your
 * height), and the board only shows while you are in that phase's arena: fall below Maxor's
 * platform during P1, or below Storm's during P2, and it disappears instead of switching to the
 * next boss. Before any boss line has been seen (joined mid-fight) it goes by height alone.
 *
 * The boss is the nearest live WitherBoss to you, preferring one whose name mentions the boss -
 * the other withers sit in their own arenas further down, so nearest is the one you're fighting.
 * If none is found the HUD says so rather than going blank, so a detection miss is visible.
 */
object AgroLeaderboard : Module(
    name = "Agro Leaderboard",
    category = Category.custom("Blood Rush"),
    description = "In F7 P1/P2, lists the party by distance to Maxor/Storm. Closest (who has aggro) is green.",
) {
    private class Entry(val name: String, val skin: PlayerSkin?, val distance: Double, val player: Player? = null)

    val sphereMode by BooleanSetting("Sphere Mode", false, desc = "Draws a see-through sphere around Maxor/Storm through the closest player: the aggro boundary. If you are the closest, it goes through the 2nd closest instead, showing your margin.")
    private val sphereColor by ColorSetting("Sphere Color", Color(255, 85, 85, 0.18f), true, desc = "Sphere colour when someone else has aggro.").withDependency { sphereMode }
    private val aggroColor by ColorSetting("Sphere Color (Your Aggro)", Color(85, 255, 85, 0.18f), true, desc = "Sphere colour when you are the closest and it shows the 2nd closest.").withDependency { sphereMode }

    private const val LAT_BANDS = 24
    private const val LON_BANDS = 48

    private var boss: WitherBoss? = null
    private var bossName: String? = null
    private var bossFound = false
    private var entries: List<Entry> = emptyList()

    /** The boss phase that is running, from chat; null before the first boss line or after Storm. */
    private var activePhase: M7Phases? = null
    private var sawBossLine = false
    private val maxorRegex = Regex("^\\[BOSS] Maxor: ")
    private val stormStartRegex = Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")
    private val stormEndRegex = Regex("^\\[BOSS] Storm: I should have known that I stood no chance\\.$")
    private val goldorStartRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")

    private const val LINE_HEIGHT = 10
    private const val HEAD_SIZE = 8

    private val leaderboardHud by HUD("Agro Leaderboard", "Party ordered by distance to Maxor/Storm.", toggleable = false) { example ->
        if (example) return@HUD draw(this, "Maxor", true, listOf(
            Entry("undonecoffee", mc.player?.skin, 4.2), Entry("Teammate", null, 9.8), Entry("Another", null, 15.1),
        ))
        val name = bossName ?: return@HUD 0 to 0
        draw(this, name, bossFound, entries)
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
            bossName = when (phase) {
                M7Phases.P1 -> "Maxor"
                M7Phases.P2 -> "Storm"
                else -> null
            }
            val name = bossName ?: run { entries = emptyList(); boss = null; return@on }
            val me = mc.player ?: return@on

            val withers = level.entitiesForRendering().filterIsInstance<WitherBoss>().filter { it.isAlive }
            val boss = withers.filter { it.name.string.contains(name, true) }.minByOrNull { it.distanceToSqr(me) }
                ?: withers.minByOrNull { it.distanceToSqr(me) }
            bossFound = boss != null
            this@AgroLeaderboard.boss = boss
            if (boss == null) { entries = emptyList(); return@on }

            entries = DungeonUtils.dungeonTeammates.mapNotNull { teammate ->
                if (teammate.isDead) return@mapNotNull null
                val player = teammate.entity?.takeIf { it.isAlive }
                    ?: level.players().firstOrNull { it.name.string == teammate.name }
                    ?: return@mapNotNull null
                Entry(teammate.name, teammate.playerSkin, player.distanceTo(boss).toDouble(), player)
            }.sortedBy { it.distance }
        }

        on<RenderEvent.Last> {
            if (!sphereMode) return@on
            val boss = boss?.takeIf { it.isAlive } ?: return@on
            val me = mc.player ?: return@on
            val pt = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            val center = boss.getPosition(pt)
            // Measured per frame from interpolated positions, so the sphere moves smoothly
            // instead of stepping each tick; same feet-to-feet distance as the leaderboard.
            val members = entries.mapNotNull { e ->
                val p = e.player?.takeIf { it.isAlive } ?: return@mapNotNull null
                AgroSphere.Member(p === me, p.getPosition(pt).distanceTo(center))
            }
            val result = AgroSphere.radius(members) ?: return@on
            val color = if (result.youHaveAggro) aggroColor else sphereColor
            drawSphere(context.poseStack(), context.bufferSource(), center, result.radius, color)
        }
    }

    /** Translucent, depth-tested, not culled: reads from inside the sphere as well as outside. */
    private fun drawSphere(
        pose: com.mojang.blaze3d.vertex.PoseStack,
        buffers: net.minecraft.client.renderer.MultiBufferSource.BufferSource,
        center: net.minecraft.world.phys.Vec3,
        radius: Double,
        color: Color,
    ) {
        if (radius <= 0.05) return
        val cam = mc.gameRenderer.mainCamera.position()
        pose.pushPose()
        pose.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z)
        val matrix = pose.last().pose()
        val argb = color.rgba
        val buffer = buffers.getBuffer(RenderTypes.debugQuads())
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
        for (lat in 0 until LAT_BANDS) for (lon in 0 until LON_BANDS) {
            for (v in arrayOf(point(lat, lon), point(lat + 1, lon), point(lat + 1, lon + 1), point(lat, lon + 1))) {
                buffer.addVertex(matrix, v[0], v[1], v[2]).setColor(argb)
            }
        }
        buffers.endBatch(RenderTypes.debugQuads())
        pose.popPose()
    }

    private fun draw(gfx: GuiGraphicsExtractor, boss: String, found: Boolean, list: List<Entry>): Pair<Int, Int> {
        val header = if (found) "§c$boss Aggro" else "§c$boss §7not found"
        gfx.text(header, 0, 0, Colors.WHITE)
        var width = mc.font.width(header)
        var y = LINE_HEIGHT + 2
        list.forEachIndexed { i, entry ->
            entry.skin?.let { PlayerFaceExtractor.extractRenderState(gfx, it, 0, y, HEAD_SIZE) }
            val line = "${i + 1}. ${entry.name} §7${String.format(Locale.ROOT, "%.1f", entry.distance)}m"
            gfx.text(line, HEAD_SIZE + 3, y, if (i == 0) Colors.MINECRAFT_GREEN else Colors.WHITE)
            width = maxOf(width, HEAD_SIZE + 3 + mc.font.width(line))
            y += LINE_HEIGHT
        }
        return width to y
    }
}
