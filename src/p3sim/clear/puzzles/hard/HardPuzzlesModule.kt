package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.ModuleEvent
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.PuzzleType
import com.engineerclient.p3sim.run.api.Services
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity

/**
 * The hard puzzles: Water Board, Teleport Maze, Ice Path. Provides them through `puzzleTypes()` (the puzzles module
 * creates and hosts them) and routes a hit on the Ice Path's silverfish to it.
 * FILLED BY: the `traps` work item (owns `src/p3sim/clear/puzzles/hard/` and below and `src/p3sim/clear/traps/` and below).
 */
object HardPuzzlesModule : AdsModule {
    override val id = "hardpuzzles"
    override val owner = "traps"

    private lateinit var routeGroupsS: NumberSetting<Int>

    override fun registerSettings(host: com.odtheking.odin.features.Module) {
        routeGroupsS = host.registerSetting(NumberSetting("Teleport Maze Route Groups", TpMazeModel.DEFAULT_ROUTE_GROUPS, 2..7, 1,
            desc = "How many of the Teleport Maze's seven pad groups the way to the chest passes through. A recorded solve takes about 11 hops; this model visits each group once, so 7 is the closest."))
    }

    /** The setting, or its default when the settings were never registered (headless). */
    fun tpRouteGroups(): Int = if (::routeGroupsS.isInitialized) routeGroupsS.value.toInt() else TpMazeModel.DEFAULT_ROUTE_GROUPS

    override fun devCommands() = listOf(HardDevCommands.hard)

    override fun puzzleTypes(): List<PuzzleType> = listOf(
        PuzzleType("WATER_BOARD", "Water Board", setOf("Water_Board")) { WaterBoardPuzzle(it) },
        PuzzleType("TP_MAZE", "Teleport Maze", setOf("Teleport_Maze")) { TpMazePuzzle(it) },
        PuzzleType("ICE_PATH", "Ice Path", setOf("Ice_Path")) { IcePathPuzzle(it) },
    )

    override fun onAttackEntity(run: Run, player: ServerPlayer, hand: InteractionHand, entity: Entity): InteractionResult? {
        for (room in run.plan.rooms) {
            if (room.family != "Ice_Path") continue
            val puzzle = Services.puzzles.puzzleOf(run, room.index) as? IcePathPuzzle ?: continue
            if (!puzzle.ownsFish(entity)) continue
            puzzle.onFishHit(player)
            return InteractionResult.SUCCESS
        }
        return null
    }

    /**
     * An arrow hit [entity]. The damage module posts `ModuleEvent("damage", "arrowHitEntity", {id, by})` for it (integration request for
     * `Bows.kt`); this is the Ice Path's real way to push the fish.
     */
    override fun onEvent(run: Run, event: RunEvent) {
        if (event !is ModuleEvent || event.source != "damage" || event.name != "arrowHitEntity") return
        val id = event.data["id"] as? Int ?: return
        val by = event.data["by"] as? java.util.UUID ?: return
        val shooter = com.engineerclient.p3sim.Sim.players().firstOrNull { it.uuid == by } ?: return
        for (room in run.plan.rooms) {
            if (room.family != "Ice_Path") continue
            val puzzle = Services.puzzles.puzzleOf(run, room.index) as? IcePathPuzzle ?: continue
            if (puzzle.ownsFishId(id)) { puzzle.onFishArrow(shooter); return }
        }
    }
}
