package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.runstate.RunStatePack
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.plugin.AdsPlugins
import com.engineerclient.p3sim.run.plugin.BloodAssignment
import com.engineerclient.p3sim.run.plugin.PartyStrategy
import com.engineerclient.p3sim.run.plugin.Seat
import com.engineerclient.p3sim.run.plugin.StrategyContext
import kotlin.random.Random

/**
 * The party strategy the bots module ships: nobody is routed to the blood room, the bots just finish their rooms. Presets that decide who
 * goes to the blood room and when come from a plug-in through the seam (`AdsPlugin.partyStrategies`); nothing about how a camp works is here.
 */
object NeutralStrategy : PartyStrategy {
    const val ID = "neutral"
    override val id = ID
    override val label = "Neutral"
    override val description = "The bots finish the rooms they are given. Nobody is sent to the blood room."
    override fun bloodAssignment(ctx: StrategyContext): BloodAssignment = BloodAssignment.NOBODY
}

/** What a strategy sees at plan time. */
class BotStrategyContext(
    override val plan: DungeonPlan,
    override val seats: List<Seat>,
    override val random: Random,
    override val tables: RunStatePack?,
    override val now: Int,
) : StrategyContext

object BotStrategies {
    /** The strategy named by [id] (a run's `RunConfig.strategy`): the built-in neutral one for null, "neutral" or a name no plug-in offers. */
    fun resolve(id: String?): PartyStrategy {
        if (id == null || id == NeutralStrategy.ID) return NeutralStrategy
        return AdsPlugins.strategy(id) ?: NeutralStrategy
    }

    /** Every choice for a settings list: the neutral default first, then what the plug-ins offer. */
    fun available(): List<PartyStrategy> = listOf<PartyStrategy>(NeutralStrategy) + AdsPlugins.strategies().filter { it.id != NeutralStrategy.ID }

    /**
     * The blood assignment of [strategy] for these seats, guarded: a plug-in that throws, or names a seat that does not exist or is the
     * human, gets [BloodAssignment.NOBODY].
     */
    fun assignment(strategy: PartyStrategy, ctx: StrategyContext): BloodAssignment {
        val a = try { strategy.bloodAssignment(ctx) } catch (t: Throwable) { return BloodAssignment.NOBODY }
        val seat = a.seat ?: return a
        val s = ctx.seats.firstOrNull { it.index == seat }
        return if (s == null || s.isHuman) BloodAssignment.NOBODY else a
    }
}
