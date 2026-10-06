package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.DungeonQueue
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.PartyUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.item.PrimedTnt

/**
 * Requeues an F7 / M7 run before its score, on the tick Necron's death TNT appears.
 *
 * From 60 recorded P4 endings: the TNT comes after "All this, for nothing..." and the score
 * follows it 39-50 server ticks later in every one, while the line itself is 79-128 ticks before
 * the score. Sending `/joininstance` for the same floor (not `/instancerequeue`) on the TNT saves ~2.4 s a run over an instant requeue at
 * the score. The risk is the transfer beating the score, which loses the run: only a server freeze
 * after the send did that (1 of 60). Counting server ticks makes a lagging server requeue later
 * by itself, and the freeze guard holds while no server ticks are arriving.
 *
 * With no TNT seen it falls back to the chest room (you're teleported up to y 166 at the score).
 * Odin's Auto Requeue is told to skip that run, so it doesn't requeue a second time.
 */
object PreRequeue : Module(
    name = "Pre-Requeue",
    category = Category.custom("Engineer Client", 1030, 10),
    description = "Joins the next F7/M7 run (/joininstance, same floor) on Necron's death TNT, ~2.4 s before the score (about 1 run in 60 lost to a server freeze after the send).",
) {
    private val leaderOnly by BooleanSetting("Leader Only", true, desc = "Only requeue when you lead the party (or are solo): one requeue for everyone.")
    private val delay by NumberSetting("Extra Ticks", 0, 0..40, 1, desc = "Server ticks to wait after the TNT. Waiting doesn't lower the risk in the recordings, it only gives back the time saved.", unit = "ticks")
    private val freezeGuard by BooleanSetting("Freeze Guard", true, desc = "Hold the requeue while the server is frozen (no server tick in the last 3 client ticks).")
    private val chestFallback by BooleanSetting("Chest Room Fallback", true, desc = "No TNT seen: requeue when you're teleported up to the chest room.")
    private val notify by BooleanSetting("Chat Note", true, desc = "Says in chat when it requeued, and on what.")

    private val plan = Plan()
    private var serverTicks = 0

    /** The decision, without Minecraft: armed by the end line, fired by the TNT or the chest room. */
    class Plan {
        var armed = false; private set
        var sent = false; private set
        private var at: Int? = null
        private var why = ""
        /** Client ticks since the last server tick. */
        private var quiet = 0

        fun reset() { armed = false; sent = false; at = null; why = ""; quiet = 0 }

        fun onEndLine() { if (!sent) { armed = true; at = null } }

        fun onTnt(tick: Int, extra: Int) { if (armed && at == null) { at = tick + extra; why = "Necron's TNT" + if (extra > 0) " +$extra ticks" else "" } }

        fun onChestRoom(tick: Int) { if (armed && (at == null || at!! > tick)) { at = tick; why = "the chest room (no TNT seen)" } }

        fun onServerTick() { quiet = 0 }

        /** Each client tick: what fired it, the one time it should be sent now; else null. */
        fun onClientTick(tick: Int, guard: Boolean): String? {
            val t = at
            val frozen = guard && quiet >= FROZEN
            quiet++
            if (!armed || sent || t == null || tick < t || frozen) return null
            sent = true
            return why
        }

        companion object {
            const val FROZEN = 3

            /** `/joininstance`'s name for F7 / M7, as Odin's `/od f7` sends it. */
            fun instance(floor: String) = if (floor == "M7") "master_catacombs_floor_seven" else "catacombs_floor_seven"
        }
    }

    private const val END_LINE = "[BOSS] Necron: All this, for nothing..."
    private val CODES = Regex("§.")

    /** The floor the end line came on: the next run is the same one. */
    private var floor = "F7"


    private fun inFloor7() = DungeonUtils.inDungeons && DungeonUtils.floor?.name.let { it == "F7" || it == "M7" } &&
        !com.engineerclient.p3sim.P3Sim.inSim

    init {
        on<LevelEvent.Load> { plan.reset() }
        on<TickEvent.Server> { serverTicks++; plan.onServerTick() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CODES, "")
            if (text == END_LINE) mc.execute { if (inFloor7()) { floor = DungeonUtils.floor?.name ?: "F7"; plan.onEndLine() } }
        }

        on<EntityEvent.Add> {
            if (entity is PrimedTnt && plan.armed) plan.onTnt(serverTicks, delay)
        }

        on<TickEvent.End> {
            if (!plan.armed || plan.sent) return@on
            // The score teleports everyone up to the chest room (y 166); Necron's arena is far below.
            if (chestFallback && (mc.player?.y ?: 0.0) > 150) plan.onChestRoom(serverTicks)
            val why = plan.onClientTick(serverTicks, freezeGuard) ?: return@on
            if (leaderOnly && PartyUtils.isInParty && !PartyUtils.isLeader()) return@on
            EngineerClient.safely("pre-requeue") {
                // Odin's Auto Requeue would send another at the score's stats line.
                DungeonQueue.disableRequeue = true
                sendCommand("joininstance " + Plan.instance(floor))
                if (notify) EngineerClient.msg("§7Sent /joininstance " + Plan.instance(floor) + " on $why.")
            }
        }
    }
}
