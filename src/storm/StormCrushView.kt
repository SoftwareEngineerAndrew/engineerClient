package com.engineerclient.storm

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Storm's crush hitbox (Sub Splits' Show Crush Info): from his lightning on, at every 20-tick
 * crush check with Storm within 5 blocks of a pillar, where the check had him - the hitbox worked
 * out from the recorded runs ([StormCrush]) - for a few seconds: green if it fitted (inside that
 * pillar's square, head up into the pillar), red if not, and by how much. His position is the last
 * one the server sent: what his wither is drawn at trails it by the three ticks the game takes to
 * slide him there.
 */
class StormCrushView {

    /** Storm's arena: the boss rooms stack up, and his floor is the one between y 150 and 215. */
    private val arena = AABB(0.0, 150.0, 0.0, 140.0, 215.0, 145.0)

    /** When each pillar last stepped down (server ticks). */
    private val lastStep = HashMap<StormCrush.Pillar, Int>()

    private class Snapshot(val box: AABB, val verdict: StormCrush.Verdict, val bottom: Int?, val armed: Boolean, val at: Long)
    private val snapshots = ArrayList<Snapshot>()

    fun reset() { lastStep.clear(); snapshots.clear() }

    fun inArena(): Boolean = mc.player?.let { arena.contains(it.position()) } == true

    fun onStep(pillar: StormCrush.Pillar, tick: Int) { lastStep[pillar] = tick }

    /**
     * A crush check on server tick [now]: Storm against the pillar he is nearest, if within
     * [RANGE] of its zone. Returns the verdict and whether that pillar was lowered, or null.
     */
    fun check(now: Int): Pair<StormCrush.Verdict, Boolean>? {
        val level = mc.level ?: return null
        val storm = level.entitiesForRendering().filterIsInstance<WitherBoss>()
            .filter { it.isAlive && arena.contains(it.position()) && com.engineerclient.misc.Witherborn.isBoss(it) }
            .minByOrNull { it.distanceToSqr(70.0, 180.0, 53.0) } ?: return null
        val at = storm.positionCodec.base.takeIf { it != Vec3.ZERO } ?: storm.position()
        val pillar = StormCrush.nearest(at.x, at.z)
        if (StormCrush.distance(pillar, at.x, at.z) > RANGE) return null
        val bottom = bottomOf(level, pillar)
        val verdict = StormCrush.judge(pillar, at.x, at.y, at.z, bottom)
        val armed = StormCrush.armed(lastStep[pillar], now)
        val box = AABB(at.x, at.y, at.z, at.x + 1, at.y + StormCrush.HEAD, at.z + 1)
        snapshots += Snapshot(box, verdict, bottom, armed, System.currentTimeMillis())
        return verdict to armed
    }

    /** Draws the checks of the last [seconds], each fading out over its last second. */
    fun RenderExtractEvent.render(seconds: Int, margins: Boolean, outline: Boolean, insideColor: Color, outsideColor: Color) {
        if (snapshots.isEmpty()) return
        val now = System.currentTimeMillis()
        val life = seconds * 1000L
        snapshots.removeIf { now - it.at > life }
        if (!inArena()) return
        for (s in snapshots) {
            val fade = ((life - (now - s.at)) / 1000f).coerceIn(0f, 1f)
            val colour = if (s.verdict.inside) insideColor else outsideColor
            drawFilledBox(s.box, colour.withAlpha(colour.alphaFloat * fade), depth = false)
            drawWireFrameBox(s.box, colour.withAlpha(fade), depth = false)
            val p = s.verdict.pillar
            if (outline && s.bottom != null) {
                val y = s.bottom.toDouble()
                val square = AABB(p.minX.toDouble(), y, p.minZ.toDouble(), (p.minX + StormCrush.PILLAR).toDouble(), y + 0.02, (p.minZ + StormCrush.PILLAR).toDouble())
                drawWireFrameBox(square, colour.withAlpha(0.6f * fade), depth = false)
            }
            if (margins) drawText(label(s), Vec3((s.box.minX + s.box.maxX) / 2, s.box.maxY + 0.5, (s.box.minZ + s.box.maxZ) / 2), 1f, false)
        }
    }

    /** The pillar's lowest block: down its piston column from the top until the first air. */
    private fun bottomOf(level: ClientLevel, p: StormCrush.Pillar): Int? {
        val pos = BlockPos.MutableBlockPos(p.columnX, StormCrush.TOP, p.columnZ)
        if (level.getBlockState(pos).isAir) return null
        var y = StormCrush.TOP
        while (y > StormCrush.FLOOR && !level.getBlockState(pos.setY(y - 1)).isAir) y--
        return y
    }

    /** "0.42 in · head +1.20", with the pillar's colour, and "(not lowered)" if it wouldn't crush. */
    private fun label(s: Snapshot): String {
        val v = s.verdict
        val side = (if (v.inset >= 0) "§a" else "§c") + fmt(kotlin.math.abs(v.inset)) + if (v.inset >= 0) " in" else " out"
        val head = v.head?.let { (if (it >= 0) "§a" else "§c") + "head " + (if (it >= 0) "+" else "-") + fmt(kotlin.math.abs(it)) } ?: "§7no pillar"
        return v.pillar.colour + v.pillar.name + " §7· " + side + " §7· " + head + if (s.armed) "" else " §8(not lowered)"
    }

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.2f", d)

    companion object {
        /** Only checks with Storm this close to a pillar's crush zone are drawn. */
        const val RANGE = 5.0
    }
}
