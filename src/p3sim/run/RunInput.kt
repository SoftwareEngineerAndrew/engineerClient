package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimServer
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

/**
 * The one door through which the modules see what a player does in the dungeon: a right click on a block (doors, chests,
 * levers, skulls, the reset item's target), a right click on an entity (a puzzle's NPC, a key stand), a left click on an entity
 * (a melee hit on a mob), and a right click with an item in the air.
 *
 * Why it exists: the sim's own handlers ([com.engineerclient.p3sim.SimItems], `Sim.guardBlocks`) are Fabric callbacks in the DEFAULT
 * phase, and `guardBlocks` answers FAIL to every block click that reached it, which ends the chain. Six modules each registering
 * their own callback would depend on registration order against those. Instead ONE callback per event is installed here in an
 * EARLIER phase ([PHASE]); it asks the modules in [AdsModules.all] order, the first answer other than null wins, and a null from
 * everybody lets the sim's own handlers (items, the block guard) go on exactly as in the boss practice.
 *
 * It only ever speaks while a [Run] exists and is in a dungeon stage (`COUNTDOWN`, `CLEAR`, `BLOOD`, see [RunStage.inDungeon]),
 * on the server side, in the sim's own world, for the main hand. In the boss stages and in today's boss practice it never
 * answers, so those flows are untouched. The module hook runs as the player who clicked ([Sim.asPlayer]): `Sim.chat` and friends
 * reach that player alone. Server thread only.
 */
object RunInput {
    /** The Fabric phase our callbacks live in: before [Event.DEFAULT_PHASE]. */
    val PHASE: Identifier = Identifier.fromNamespaceAndPath("engineerclient", "ads_run")

    private val log = LoggerFactory.getLogger("engineerclient")
    private var installed = false

    /**
     * Asks every module in [modules] order; the first non-null answer wins. A module that throws is logged and skipped (it is
     * treated as having no opinion). Pure apart from the logging: this is what the unit test drives.
     */
    internal fun <R : Any> ask(modules: List<AdsModule>, what: String, call: (AdsModule) -> R?): R? {
        for (m in modules) {
            val r = try { call(m) } catch (t: Throwable) { log.error("[ads] ${m.id}.$what failed", t); null }
            if (r != null) return r
        }
        return null
    }

    /** The run to route to for this event, or null (client side, another world, no run, or not a dungeon stage). */
    private fun runFor(level: Level, player: net.minecraft.world.entity.player.Player): Run? {
        if (level.isClientSide || player !is ServerPlayer || !SimServer.isSimLevel(level)) return null
        val run = Runs.current ?: return null
        return if (run.isOver || !run.stage.inDungeon) null else run
    }

    /** Registers the four callbacks (once). Called by [AdsBootstrap.init]. */
    fun install() {
        if (installed) return
        installed = true
        UseBlockCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        UseEntityCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        AttackEntityCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        UseItemCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        UseBlockCallback.EVENT.register(PHASE) { player, level, hand, hit ->
            val run = runFor(level, player)
            if (run == null || hand != InteractionHand.MAIN_HAND) InteractionResult.PASS
            else route(player as ServerPlayer, "onUseBlock") { it.onUseBlock(run, player, hand, hit) }
        }
        UseEntityCallback.EVENT.register(PHASE) { player, level, hand, entity, _ ->
            val run = runFor(level, player)
            if (run == null || hand != InteractionHand.MAIN_HAND) InteractionResult.PASS
            else route(player as ServerPlayer, "onUseEntity") { it.onUseEntity(run, player, hand, entity) }
        }
        AttackEntityCallback.EVENT.register(PHASE) { player, level, hand, entity, _ ->
            val run = runFor(level, player)
            if (run == null || hand != InteractionHand.MAIN_HAND) InteractionResult.PASS
            else route(player as ServerPlayer, "onAttackEntity") { it.onAttackEntity(run, player, hand, entity) }
        }
        UseItemCallback.EVENT.register(PHASE) { player, level, hand ->
            val run = runFor(level, player)
            if (run == null || hand != InteractionHand.MAIN_HAND) InteractionResult.PASS
            else route(player as ServerPlayer, "onUseItem") { it.onUseItem(run, player, hand) }
        }
    }

    private fun route(player: ServerPlayer, what: String, call: (AdsModule) -> InteractionResult?): InteractionResult =
        Sim.asPlayer(player) { ask(AdsModules.all, what, call) } ?: InteractionResult.PASS
}
