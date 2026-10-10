package com.devgineerclient.maxor

import com.devgineerclient.DevgineerClient
import com.devgineerclient.maxor.CrystalCycles.From
import com.devgineerclient.maxor.CrystalCycles.Side
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundLoginPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * F7/M7 phase 1: how long Maxor's energy crystals took to place, cycle by cycle, in server ticks.
 *
 * The first cycle is timed from the pylons opening, the later ones from the crystals coming back on
 * the top platforms (40 ticks after a hit). Each line says when each pylon got its crystal and who
 * placed it (the picker nearest the pylon - a guess), which 10-tick check the laser was charged
 * for and which it fired on, and how early both crystals had to be in to make the earliest check
 * the cycle could make (the beacon, or the previous hit + 70). The rules are the Alpha server's
 * fight, which the main server has run since Hypixel's boss update (Oct 2026): pylons open at
 * s0 + 79-83, beacon at 118-122, kill to Storm 61-64. The bookkeeping is [CrystalCycles].
 *
 * Everything is read off the network ahead of every other mod (ConnectionTapMixin), and timed by
 * its own count of the server's top-level pings - as Odin counts server ticks.
 */
object MaxorCrystals : Module(
    name = "Maxor Crystals",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "F7 P1: how many server ticks each energy crystal took to place, cycle by cycle, and which laser check that made.",
) {
    private val chatReport by BooleanSetting("Chat Report", true, desc = "A line in your chat for each crystal cycle, when the laser fires (or the phase ends without it).")
    private val showPlacers by BooleanSetting("Show Placers", true, desc = "Who placed each crystal: the crystal picker nearest the pylon a few ticks after it appeared. A guess.")
    private val showPicks by BooleanSetting("Show Pickups", true, desc = "When each crystal was picked up, in the cycles after the first.").withDependency { chatReport }
    private val phaseSummary by BooleanSetting("Phase Summary", true, desc = "At Storm's first line: the whole phase in ticks - first hit after the beacon, ticks between hits, kill after the last hit, Storm after the kill.")
    private val partyReport by BooleanSetting("Party Report", false, desc = "Also sends each cycle's crystal times, and the phase summary, to party chat.")

    /** Whether the module has had its one-time switch-on (on by default, existing installs too). */
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private val PICKED = Regex("""^(\w+) picked up an Energy Crystal!$""")
    private val CONTROL_CODES = Regex("§.")

    /** Pylons' centres; a placed crystal sits on one at y 224.4, fresh ones on the top platforms at 238.4. */
    private val PYLONS = mapOf(Side.W to doubleArrayOf(52.5, 224.0, 41.5), Side.E to doubleArrayOf(94.5, 224.0, 41.5))
    private val SLOT = BlockPos(73, 221, 73)
    private val SLOT_TOP = BlockPos(73, 222, 73)

    /** Maxor's synced health is 1 while he cannot be hurt and 1000 while a laser stun lets him be. */
    private const val HEALTH_INDEX = 9
    private const val DAMAGEABLE = 500f

    // Network thread only.
    @Volatile private var ticks = 0
    private var model: CrystalCycles? = null
    private val withers = HashSet<Int>()
    private val health = HashMap<Int, Float>()

    // Placements waiting a few ticks for the placer to be where the client can see him (main thread).
    private data class Pending(val cycle: Int, val side: Side, val tick: Int, val pickers: List<String>)
    private val pending = ConcurrentLinkedQueue<Pending>()
    private val placers = HashMap<Pair<Int, Side>, String>()

    init {
        on<TickEvent.End> {
            val now = ticks
            while (true) {
                val p = pending.peek() ?: break
                if (now < p.tick + 3) break
                pending.poll()
                DevgineerClient.safely("maxor crystals placer") { nearest(p.side, p.pickers)?.let { placers[p.cycle to p.side] = it } }
            }
        }
    }

    /** Every inbound packet, on the network thread, from ConnectionTapMixin. Never modifies anything. */
    fun tap(packet: Packet<*>) {
        if (!enabled) return
        DevgineerClient.safely("maxor crystals tap") { collect(packet, inBundle = false) }
    }

    private fun collect(p: Packet<*>, inBundle: Boolean) {
        when (p) {
            is ClientboundBundlePacket -> p.subPackets().forEach { collect(it, inBundle = true) }
            // Server ticks as Odin counts them: top-level pings only (the anticheat's bundled pairs are not ticks).
            is ClientboundPingPacket -> if (p.id != 0 && !inBundle) ticks++
            is ClientboundLoginPacket, is ClientboundRespawnPacket -> { model?.end(null); model = null; withers.clear(); health.clear() }
            is ClientboundSystemChatPacket -> if (!p.overlay) chat(p.content.string.replace(CONTROL_CODES, ""))
            is ClientboundAddEntityPacket -> {
                // Not a Witherborn wither (full Storm armor): it is never Maxor.
                if (p.type == EntityType.WITHER && !com.devgineerclient.splits.Witherborn.onSpawn(p.id, p.x, p.y, p.z)) withers += p.id
                val m = model ?: return
                if (p.type != EntityType.END_CRYSTAL) return
                if (p.y > 231) { m.crystalsBack(ticks); return }
                val side = PYLONS.entries.firstOrNull { (_, c) -> kotlin.math.abs(p.x - c[0]) < 1.5 && kotlin.math.abs(p.z - c[2]) < 1.5 }?.key ?: return
                m.placed(side, ticks)
                pending += Pending(m.cycle, side, ticks, m.pickers())
            }
            is ClientboundSetEntityDataPacket -> {
                val m = model ?: return
                if (p.id() !in withers) return
                val hp = p.packedItems().firstOrNull { it.id() == HEALTH_INDEX }?.value() as? Float ?: return
                val was = health.put(p.id(), hp) ?: 0f
                if (hp >= DAMAGEABLE && was < DAMAGEABLE) {
                    m.hit(ticks)
                }
            }
            is ClientboundBlockUpdatePacket -> block(p.pos, p.blockState)
            is ClientboundSectionBlocksUpdatePacket -> p.runUpdates { pos, state -> block(pos, state) }
        }
    }

    private fun block(pos: BlockPos, state: BlockState) {
        val m = model ?: return
        if (pos == SLOT || pos == SLOT_TOP) {
            when {
                state.isAir -> m.slotCleared(ticks)
                state.block == Blocks.BEACON && pos == SLOT -> m.beacon(ticks)
                state.block == Blocks.BEDROCK && pos == SLOT -> m.kill(ticks)
            }
        }
    }

    private fun chat(text: String) {
        when {
            text == MAXOR_START -> {
                health.clear(); pending.clear()
                DevgineerClient.mc.execute { placers.clear() }
                model = CrystalCycles(::onCycle, ::onPhase).also { it.start(ticks) }
            }
            text == STORM_START -> { model?.end(ticks); model = null }
            else -> {
                val m = model ?: return
                PICKED.matchEntire(text)?.let { r -> m.picked(r.groupValues[1], ticks) }
            }
        }
    }

    // ---- reports (built on the network thread, said on the main thread) --------------------------

    private fun onCycle(r: CrystalCycles.Report) {
        DevgineerClient.mc.execute {
            DevgineerClient.safely("maxor crystals report") {
                // Placers whose few ticks have not passed yet (a report at the very end): guess now.
                for ((side, _) in r.placedAt) if ((r.cycle to side) !in placers) nearest(side, emptyList())?.let { placers[r.cycle to side] = it }
                say(r)
            }
        }
    }

    private fun say(r: CrystalCycles.Report) {
        val from = when (r.from) {
            From.PYLONS_OPEN -> "from pylons open"
            From.CRYSTALS_BACK -> "from crystals back"
            From.MAXOR_START -> "from Maxor's first line"
        }
        val sides = Side.entries.filter { it in r.placed }
        val coloured = sides.joinToString(" §7| ") { side ->
            val t = r.placed.getValue(side)
            val c = when {
                r.best != null && t <= r.best -> "§a"
                r.next != null && t <= r.next -> "§e"
                else -> "§c"
            }
            val who = if (showPlacers) placers[r.cycle to side]?.let { " §b$it" } ?: "" else ""
            "§f$side$who $c+$t"
        }
        val picks = if (showPicks && r.picked.isNotEmpty()) " §8(picked " + r.picked.joinToString(", ") { "+${it.second}" } + ")" else ""
        val missing = if (sides.size < 2) " §7| §c" + Side.entries.first { it !in r.placed } + " never placed" else ""
        val firstCycle = r.cycle == 1
        fun check(v: Int) = if (firstCycle) (if (v == 0) "the beacon" else "beacon+$v") else "+$v"
        val ready = r.readyFor?.let { "§7 → ready for §f${check(it)}" } ?: ""
        val hit = r.hitAt?.let { " §7· hit §f${check(it)}" } ?: if (r.readyFor != null) " §7· §cno hit" else ""
        // Since Hypixel's boss update (Oct 2026) the main server runs the Alpha fight: no 10 s laser
        // cooldown (hit 1 to hit 2 can be as quick as ~79 ticks), so +70 is always the target.
        val target = r.best?.let { b -> " §8(" + (if (firstCycle) "the beacon" else "+70") + ": both by +$b)" } ?: ""
        val line = "§dCrystals ${r.cycle} §8$from§7: $coloured$missing$picks$ready$hit$target"
        if (chatReport) DevgineerClient.msg(line)
        val plain = line.replace(CONTROL_CODES, "")
        if (partyReport) sendCommand("pc " + plain.replace("→", "->").replace("·", "|"))
    }

    private fun onPhase(p: CrystalCycles.Phase) {
        DevgineerClient.mc.execute {
            DevgineerClient.safely("maxor crystals phase") {
                val parts = ArrayList<String>()
                p.firstHitAfterBeacon?.let { parts += "first hit beacon+$it" }
                if (p.hitGaps.isNotEmpty()) parts += "hits " + p.hitGaps.joinToString(", ") { "+$it" }
                p.killAfterHit?.let { parts += "kill +$it" }
                p.stormAfterKill?.let { parts += "Storm kill+$it" + if (it > 62) " (waited for someone to drop into his arena)" else "" }
                val line = "§dMaxor §f${p.total}t §8(${String.format(java.util.Locale.ROOT, "%.2f", p.total / 20.0)}s at 20 TPS)§7: " + parts.joinToString(" · ")
                val plain = line.replace(CONTROL_CODES, "")
                if (phaseSummary) DevgineerClient.msg(line)
                if (partyReport) sendCommand("pc " + plain.replace("·", "|"))
            }
        }
    }

    /** The picker (else any player) nearest the pylon, as the client sees them now. Main thread. */
    private fun nearest(side: Side, pickers: List<String>): String? {
        val c = PYLONS.getValue(side)
        val players = DevgineerClient.mc.level?.players() ?: return null
        val pool = players.filter { it.name.string in pickers }.ifEmpty { players }
        return pool.minByOrNull { it.distanceToSqr(c[0], c[1], c[2]) }?.takeIf { it.distanceToSqr(c[0], c[1], c[2]) < 36.0 }?.name?.string
    }
}
