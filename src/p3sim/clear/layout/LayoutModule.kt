package com.engineerclient.p3sim.clear.layout

import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.LayoutPick
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.PlanFailure
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.WorldRegion
import com.engineerclient.p3sim.run.api.LayoutService

/**
 * The layout builder: picks a recorded layout, pastes every room at its tiles and rotation at Hypixel coordinates, carves
 * and fills the doors, and provides the world region the engine resyncs. Spike commands: `/ads dev rooms`, `/ads dev layout`.
 *
 * What a player sees of it: the dungeon of a recorded run, room by room, at the coordinates Odin expects, with the doors of that run
 * shut (wither doors coal, the blood door red, the entrance door infested stone) and walls where the run had none.
 *
 * FILLED BY: the `layout` work item (owns `src/p3sim/clear/layout/` and below, `src/p3sim/pack/rooms/` and below, `src/p3sim/pack/layouts/` and below).
 */
object LayoutModule : AdsModule, LayoutService {
    override val id = "layout"
    override val owner = "layout"

    override fun onInit() {
        Spike.installServerHooks()
        Spike.installClientHooks()
    }

    /** Picks the layout by [RunConfig.layout] ([LayoutPick], the one place that choice is made) and resolves its rooms (pure). */
    override fun plan(config: RunConfig, packs: PackSet): DungeonPlan.Result {
        val layouts = packs.layouts ?: return DungeonPlan.Result.Failed(PlanFailure("The layout pool is not loaded, so there is no dungeon to build."))
        val rooms = packs.rooms ?: return DungeonPlan.Result.Failed(PlanFailure("The room library is not loaded, so there is no dungeon to build."))
        (config.layout as? LayoutSource.Practice)?.let { return com.engineerclient.p3sim.run.PracticePlan.build(it.room, layouts, config.seed, rooms, packs.runstate, config.floor) }
        return when (val picked = LayoutPick.pick(config, layouts)) {
            is LayoutPick.Result.Failed -> DungeonPlan.Result.Failed(PlanFailure(picked.message))
            is LayoutPick.Result.Ok -> DungeonPlan.build(picked.picked.layout, config.seed, rooms, packs.runstate)
        }
    }

    /** The dungeon's blocks as a world region (name `dungeon`), or null when a room's blocks cannot be read. Decodes the layout's volumes. */
    override fun region(plan: DungeonPlan, packs: PackSet): WorldRegion? {
        val rooms = packs.rooms ?: return null
        val practice = plan.practice
        val start = practice?.let { DoorGeometry.slotOf(it.tx, it.tz, it.side) }
        val doors = if (start != null) mapOf(start to com.engineerclient.p3sim.pack.layouts.DoorKind.NORMAL) else LayoutBuilder.doorKinds(plan)
        return when (val b = LayoutBuilder.build(plan, rooms, doors)) {
            is LayoutBuilder.Result.Failed -> { org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] layout: {}", b.message); null }
            is LayoutBuilder.Result.Ok -> LayoutRegion("dungeon", b.built.instances, b.built.edits + (start?.let { practiceBackstop(it) } ?: emptyList()), rooms.textures)
        }
    }

    /** A practice room's way in is a doorway with nothing behind it: its gap is bedrock, the frame's opening filled (y 68 to 73 across the frame). */
    internal fun practiceBackstop(slot: Slot): List<Edit> = buildList {
        for (a in DoorGeometry.FRAME_MIN..DoorGeometry.FRAME_MAX) {
            val (x, z) = DoorGeometry.gapCell(slot, a)
            for (y in com.engineerclient.p3sim.pack.DoorModel.THRESHOLD_Y..(com.engineerclient.p3sim.pack.DoorModel.BODY_Y_MAX + 1)) add(Edit(x, y, z, "minecraft:bedrock"))
        }
    }

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand(
            "rooms", "/ads dev rooms",
            "Rebuilds the ADS world with three rooms side by side (Andesite, Blue Skulls turned, Pirate) and asks Odin whether it names them.",
            requiresSim = false,
            client = { ctx -> Spike.launch(ctx.reply, Spike::prepareRooms) },
        ),
        AdsCommand(
            "layout", "/ads dev layout <id|random|seed N>",
            "Rebuilds the ADS world as a whole recorded dungeon (a recorded run's id, a random one, or the one a seed picks) and checks Odin against it.",
            requiresSim = false,
            client = { ctx ->
                val a = ctx.args
                val config = when {
                    a.isEmpty() || a[0].equals("random", true) -> RunConfig(layout = LayoutSource.Random)
                    a[0].equals("seed", true) -> a.getOrNull(1)?.toLongOrNull()?.let { RunConfig(layout = LayoutSource.Seed(it), seed = it) }
                    else -> RunConfig(layout = LayoutSource.Recorded(a[0]))
                }
                if (config == null) ctx.reply("Say which seed: /ads dev layout seed 42") else Spike.launch(ctx.reply, Spike.prepareLayout(config))
            },
        ),
        AdsCommand(
            "resync", "/ads dev resync",
            "Puts every block of the built dungeon back to how it was built (what a savepoint restore does for the dungeon) and says how many it fixed.",
            server = { ctx ->
                val region = Spike.lastRegion
                val level = com.engineerclient.p3sim.SimServer.level
                if (region == null || level == null) ctx.reply("No dungeon has been built this session; use /ads dev rooms or /ads dev layout first.")
                else {
                    val t0 = System.nanoTime()
                    val fixed = region.resync(level)
                    ctx.reply("Resynced ${region.stats().chunks} chunks in ${(System.nanoTime() - t0) / 1_000_000} ms: $fixed blocks put back.")
                }
            },
        ),
        AdsCommand(
            "probe", "/ads dev probe",
            "Says which rooms the unmodified Odin recognised in the world you stand in, with their rotation, and how long the last build took.",
            requiresSim = false,
            client = { ctx -> Spike.report().forEach(ctx.reply) },
        ),
    )
}
