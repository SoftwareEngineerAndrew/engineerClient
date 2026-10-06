package com.engineerclient.storm

import com.engineerclient.EngineerClient
import com.engineerclient.recorder.EcRec
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * F7/M7 phase 2 (Storm). Only while Storm's split is running - his first line to Goldor's, the
 * same lines the splits use - and only while you are in his arena:
 *
 *  - Storm Ticks: server ticks since his phase started, optionally modulo 20. His crush checks
 *    come every 20 server ticks from the start of the phase, so with Modulo 20 on they land on the
 *    rollover to 0.
 *  - Crush Hitbox: from his lightning on, at every check Storm is within 5 blocks of a pillar,
 *    where the check had him - the hitbox worked out from the recorded runs ([StormCrush]) - for a
 *    few seconds: green if it fitted (inside that pillar's square, head up into the pillar), red if
 *    not, and by how much.
 *
 * The phase starts when Storm's wither appears, one server tick before his first line (every
 * recording), so the count reads 1 as that line arrives. Server ticks are Odin's (one per ping the
 * server sends), so a lagging server slows the count down with it.
 */
object StormPhase : Module(
    name = "Storm Phase",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "F7 P2: a server-tick counter from the start of Storm's phase, and Storm's crush hitbox at each 20-tick crush check near a pillar.",
) {

    private val CONTROL_CODES = Regex("§.")

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private val LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")

    /** Storm's arena: the boss rooms stack up, and his floor is the one between y 150 and 215. */
    private val ARENA = AABB(0.0, 150.0, 0.0, 140.0, 215.0, 145.0)

    /** Only checks with Storm this close to a pillar's crush zone are drawn. */
    private const val RANGE = 5.0

    private val modulo by BooleanSetting("Modulo 20", false, desc = "Show the count modulo 20 (0-19). The crush checks are every 20 server ticks from the start of the phase, so they land on the rollover to 0.")
    private val offset by NumberSetting("Tick Offset", 0, -10..10, 1, desc = "Shifts the count, and which ticks count as crush checks, by this many ticks - in case your own testing puts the checks on a different tick than the recorded runs did.", unit = "t")
    private val hitbox by BooleanSetting("Crush Hitbox", true, desc = "After the lightning, at every crush check with Storm within 5 blocks of a pillar: where the check had him. Green if he was inside that pillar's crush zone, red if not.")
    private val seconds by NumberSetting("Hitbox Seconds", 5, 1..10, 1, desc = "How long each check's hitbox stays up.", unit = "s").withDependency { hitbox }
    private val margins by BooleanSetting("Hitbox Margins", true, desc = "Over each hitbox, how far in or out of the zone sideways, and how far his head was above or below the pillar's bottom.").withDependency { hitbox }
    private val outline by BooleanSetting("Pillar Outline", true, desc = "The square the hitbox has to fit inside, at the height of the pillar's bottom, with each hitbox.").withDependency { hitbox }
    private val insideColor by ColorSetting("Inside Color", Color(85, 255, 85, 0.25f), true, desc = "Hitbox colour when the check had him inside the zone.").withDependency { hitbox }
    private val outsideColor by ColorSetting("Outside Color", Color(255, 85, 85, 0.25f), true, desc = "Hitbox colour when the check had him outside it.").withDependency { hitbox }

    private val ticksHud by HUD("Storm Ticks", "Server ticks since Storm's phase started.", true, 10, 60, 1.5f) { example ->
        if (example) return@HUD draw(this, if (modulo) 7 else 347)
        val count = count() ?: return@HUD 0 to 0
        if (!inArena()) return@HUD 0 to 0
        draw(this, if (modulo) Math.floorMod(count, StormCrush.CHECK_PERIOD) else count)
    }

    // The clock: Odin's server ticks, counted here so the module works without the splits on.
    // Chat and pings are both read off the network in the order they arrive, so the tick a line
    // came on is exact.
    @Volatile private var serverTicks = 0
    /** The server tick Storm's phase started on (a tick before his first line); null outside it. */
    @Volatile private var phaseStart: Int? = null
    @Volatile private var lightning = false
    @Volatile private var stormDead = false

    /** When each pillar last stepped down (server ticks). */
    private val lastStep = HashMap<StormCrush.Pillar, Int>()

    private class Snapshot(
        val box: AABB, val verdict: StormCrush.Verdict, val bottom: Int?, val armed: Boolean, val at: Long,
    )
    private val snapshots = ArrayList<Snapshot>()

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.Server> {
            val now = ++serverTicks
            val start = phaseStart ?: return@on
            if (!hitbox || !lightning || stormDead) return@on
            if (!StormCrush.isCheck(now - start + offset)) return@on
            mc.execute { EngineerClient.safely("storm check") { check(now) } }
        }

        // Chat straight off the network, before any mod can hide it (the splits do the same).
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            when (content.string.replace(CONTROL_CODES, "")) {
                STORM_START -> { reset(keepClock = true); phaseStart = serverTicks - 1 }
                in LIGHTNING -> if (phaseStart != null) lightning = true
                STORM_DEAD -> stormDead = true
                GOLDOR_START -> reset(keepClock = true)
            }
        }

        // A pillar stepping down: its layers go through moving pistons (going back up, they don't).
        on<BlockUpdateEvent> {
            if (phaseStart == null || updated.block != Blocks.MOVING_PISTON) return@on
            if (pos.y !in StormCrush.FLOOR..StormCrush.TOP) return@on
            val p = StormCrush.PILLARS.firstOrNull { pos.x == it.columnX && pos.z == it.columnZ } ?: return@on
            lastStep[p] = serverTicks
        }

        on<RenderExtractEvent> {
            if (snapshots.isEmpty()) return@on
            val now = System.currentTimeMillis()
            val life = seconds * 1000L
            snapshots.removeIf { now - it.at > life }
            if (phaseStart == null || !inArena()) return@on
            for (s in snapshots) {
                // Fades out over its last second.
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
    }

    private fun reset(keepClock: Boolean = false) {
        if (!keepClock) serverTicks = 0
        phaseStart = null; lightning = false; stormDead = false
        mc.execute { lastStep.clear(); snapshots.clear() }
    }

    /** Server ticks since the phase started (with [offset]), or null outside it. */
    private fun count(): Int? = phaseStart?.let { serverTicks - it + offset }

    private fun inArena(): Boolean = mc.player?.let { ARENA.contains(it.position()) } == true

    /**
     * A crush check: where Storm was as the check ran, against the pillar he was nearest. His
     * position is the last one the server sent - what his wither is drawn at trails it by the
     * three ticks the game takes to slide him there.
     */
    private fun check(now: Int) {
        val level = mc.level ?: return
        val storm = level.entitiesForRendering().filterIsInstance<WitherBoss>()
            .filter { it.isAlive && ARENA.contains(it.position()) }
            .minByOrNull { it.distanceToSqr(70.0, 180.0, 53.0) }
        if (storm == null) { recCheck(now) { o -> o.str("verdict", "no storm in view") }; return }
        val fromServer = storm.positionCodec.base != Vec3.ZERO
        val at = storm.positionCodec.base.takeIf { it != Vec3.ZERO } ?: storm.position()
        val pillar = StormCrush.nearest(at.x, at.z)
        val dist = StormCrush.distance(pillar, at.x, at.z)
        if (dist > RANGE) {
            recCheck(now) { o -> o.str("verdict", "far").num("id", storm.id).nums("pos", listOf(at.x, at.y, at.z)).bool("serverPos", fromServer).str("pillar", pillar.name).num("dist", dist) }
            return
        }
        val bottom = bottomOf(level, pillar)
        val verdict = StormCrush.judge(pillar, at.x, at.y, at.z, bottom)
        recCheck(now) { o ->
            o.str("verdict", if (verdict.inside) "inside" else "outside").num("id", storm.id).nums("pos", listOf(at.x, at.y, at.z)).bool("serverPos", fromServer)
            o.str("pillar", pillar.name).num("dist", dist).num("inset", verdict.inset).num("head", verdict.head).num("bottom", bottom)
            o.bool("armed", StormCrush.armed(lastStep[pillar], now)).num("lastStep", lastStep[pillar])
        }
        val box = AABB(at.x, at.y, at.z, at.x + 1, at.y + StormCrush.HEAD, at.z + 1)
        snapshots += Snapshot(box, verdict, bottom, StormCrush.armed(lastStep[pillar], now), System.currentTimeMillis())
    }

    /** A crush check for the recorder: the server tick it ran on, the phase count, and what it found. */
    private inline fun recCheck(now: Int, body: (EcRec.Obj) -> Unit) = EcRec.line("ec.storm.check") { o ->
        o.num("tick", now).num("count", count()).num("offset", offset).num("phaseStart", phaseStart)
        body(o)
    }

    /** Read-only, for the Dungeon Recorder's ec.clocks. */
    val recServerTicks: Int get() = serverTicks

    /** The pillar's lowest block: down its piston column from the top until the first air. */
    private fun bottomOf(level: net.minecraft.client.multiplayer.ClientLevel, p: StormCrush.Pillar): Int? {
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

    private fun draw(gfx: GuiGraphicsExtractor, value: Int): Pair<Int, Int> {
        // The check tick itself in green, so the rollover stands out.
        val colour = if (modulo && value == 0) "§a" else "§f"
        val line = "§bStorm " + colour + value + if (modulo) "" else "t"
        gfx.text(line, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(line) to 10
    }
}
