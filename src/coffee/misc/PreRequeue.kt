package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.coffeeclient.CoffeeClient.mc
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
 * Requeues an F7 run before its score, on the tick Necron's death TNT appears.
 *
 * Necron's death drops a burst of 10 TNT at once (sometimes split 9 + 1 over two ticks), and the
 * score follows it 39-50 server ticks later: 60 recorded P4 endings before Hypixel's October 2026
 * boss update, and 14 of 14 after it. The update dropped his death line ("All this, for
 * nothing...") and shortened the fight, so the plan is armed by his first line instead, and only a
 * burst fires it: the single TNT his "Let's make some space!" can drop mid-fight (1 in 3 runs,
 * 200-310 ticks before the score) never does. Sending `/joininstance` for the same floor (not
 * `/instancerequeue`) on the TNT saves ~2.4 s a run over an instant requeue at the score. The risk
 * is the transfer beating the score, which loses the run: only a server freeze after the send did
 * that (1 of 60). Counting server ticks makes a lagging server requeue later by itself, and the
 * freeze guard holds while no server ticks are arriving.
 *
 * F7 only: in M7 Necron's death leads into the Wither King, with no TNT burst and no score.
 *
 * With no TNT seen it falls back to the chest room (you're teleported up to y 166 at the score).
 * Odin's Auto Requeue is told to skip that run, so it doesn't requeue a second time.
 *
 * `!dt` / `!downtime` in party chat (optionally with a reason) stops every requeue for the end of
 * the run: this one, and Odin's Auto Requeue, which is held off until the run is over even when
 * Odin's own chat commands are off. `!undt` / `!undowntime` takes yours back.
 */
object PreRequeue : Module(
    name = "Pre-Requeue",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Joins the next F7 run (/joininstance) on Necron's death TNT, ~2.4 s before the score (about 1 run in 60 lost to a server freeze after the send).",
) {
    private val leaderOnly by BooleanSetting("Leader Only", true, desc = "Only requeue when you lead the party (or are solo): one requeue for everyone.")
    private val delay by NumberSetting("Extra Ticks", 0, 0..40, 1, desc = "Server ticks to wait after the TNT. Waiting doesn't lower the risk in the recordings, it only gives back the time saved.", unit = "ticks")
    private val freezeGuard by BooleanSetting("Freeze Guard", true, desc = "Hold the requeue while the server is frozen (no server tick in the last 3 client ticks).")
    private val chestFallback by BooleanSetting("Chest Room Fallback", true, desc = "No TNT seen: requeue when you're teleported up to the chest room.")
    private val notify by BooleanSetting("Chat Note", true, desc = "Says in chat when it requeued, and on what.")

    private val plan = Plan()
    private val downtime = Downtime()

    /**
     * Who asked for downtime with `!dt` in party chat, and why: while anyone has, no requeue. Kept
     * until the run's end has been dealt with ([clear]), across the world loads into the run.
     */
    class Downtime {
        private val reasons = LinkedHashMap<String, String>()
        val active get() = reasons.isNotEmpty()

        /** A party chat message from [ign]: a note for chat when it was a `!dt` / `!undt`, else null. */
        fun onParty(ign: String, message: String): String? {
            val words = message.trim().split(Regex("\\s+"))
            return when (words.first().lowercase()) {
                "!dt", "!downtime" -> {
                    reasons[ign] = words.drop(1).joinToString(" ").ifBlank { "no reason given" }
                    "§c!dt §7from §f$ign§7: no requeue at the end of this run (${reasons[ign]})."
                }
                "!undt", "!undowntime" -> {
                    if (reasons.remove(ign) == null) return null
                    if (active) "§7!undt from §f$ign§7, still waiting on " + who() + "." else "§a!undt §7from §f$ign§7: requeue back on."
                }
                else -> null
            }
        }

        fun who() = reasons.entries.joinToString(", ") { (ign, why) -> "$ign ($why)" }
        fun clear() = reasons.clear()
    }
    private var serverTicks = 0

    /** The decision, without Minecraft: armed by Necron's first line, fired by his death TNT burst or the chest room. */
    class Plan {
        var armed = false; private set
        var sent = false; private set
        private var at: Int? = null
        private var why = ""
        /** Client ticks since the last server tick. */
        private var quiet = 0
        /** Server ticks of the TNT seen lately, for telling his death burst from a single stray one. */
        private val tnt = ArrayDeque<Int>()

        fun reset() { armed = false; sent = false; at = null; why = ""; quiet = 0; tnt.clear() }

        fun onNecron() { if (!armed && !sent) { armed = true; at = null; tnt.clear() } }

        fun onTnt(tick: Int, extra: Int) {
            if (!armed || at != null) return
            tnt.addLast(tick)
            while (tick - tnt.first() > BURST_TICKS) tnt.removeFirst()
            if (tnt.size < BURST) return
            at = tick + extra; why = "Necron's TNT" + if (extra > 0) " +$extra ticks" else ""
        }

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
            /** His death burst: at least this many TNT within [BURST_TICKS] server ticks. */
            const val BURST = 3
            const val BURST_TICKS = 1

            /** `/joininstance`'s name for F7, as Odin's `/od f7` sends it. */
            const val INSTANCE = "catacombs_floor_seven"
        }
    }

    private val EXTRA_STATS = Regex(" {29}> EXTRA STATS <")
    private const val NECRON = "[BOSS] Necron: "
    private val CODES = Regex("§.")
    /** "Party > [MVP++] name: message", the rank bracket absent for unranked players. */
    private val PARTY = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")

    /**
     * F7, or a dungeon whose floor Odin hasn't read yet (its scoreboard can lag the boss lines): only
     * a floor known to be another one (M7) keeps the plan off.
     */
    private fun inF7() = DungeonUtils.inDungeons && DungeonUtils.floor?.name.let { it == null || it == "F7" }

    init {
        on<LevelEvent.Load> { plan.reset() }
        on<TickEvent.Server> { serverTicks++; plan.onServerTick() }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CODES, "")
            if (text.startsWith(NECRON)) mc.execute { if (inF7()) plan.onNecron() }
            PARTY.find(text.trim())?.let { m ->
                mc.execute { downtime.onParty(m.groupValues[1], m.groupValues[2].trim())?.let { if (enabled) CoffeeClient.msg(it) } }
            }
            // The run's stats: Odin's Auto Requeue has looked at its flag by now, so the downtime is spent.
            if (EXTRA_STATS.matches(text)) mc.execute { downtime.clear() }
        }

        on<EntityEvent.Add> {
            if (entity is PrimedTnt && plan.armed) plan.onTnt(serverTicks, delay)
        }

        on<TickEvent.End> {
            // Odin's Auto Requeue forgets its flag on every world load (the run's own included).
            if (enabled && downtime.active) DungeonQueue.disableRequeue = true
            if (!plan.armed || plan.sent) return@on
            // The score teleports everyone up to the chest room (y 166); Necron's arena is far below.
            if (chestFallback && (mc.player?.y ?: 0.0) > 150) plan.onChestRoom(serverTicks)
            val why = plan.onClientTick(serverTicks, freezeGuard) ?: return@on
            // Armed before Odin knew the floor, and it has turned out not to be F7.
            if (!inF7()) return@on
            if (leaderOnly && PartyUtils.isInParty && !PartyUtils.isLeader()) return@on
            if (downtime.active) {
                CoffeeClient.msg("§7Not requeueing: §c!dt §7from " + downtime.who() + ".")
                return@on
            }
            CoffeeClient.safely("pre-requeue") {
                // Odin's Auto Requeue would send another at the score's stats line.
                DungeonQueue.disableRequeue = true
                sendCommand("joininstance " + Plan.INSTANCE)
                if (notify) CoffeeClient.msg("§7Sent /joininstance " + Plan.INSTANCE + " on $why.")
            }
        }
    }
}
