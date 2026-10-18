package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.PackSet
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsServerContext
import com.engineerclient.p3sim.run.DungeonPlan
import java.util.concurrent.CompletableFuture

/** `/ads dev bots`, `botplan`, `botreplan` (in the world) and `botsim` (reads the packs, no world needed). */
object BotCommands {
    fun commands(): List<AdsCommand> = listOf(
        AdsCommand("bots", "/ads dev bots", "Lists the teammate bots: class, where they are, whether they are drawn, and what they are doing next.", server = ::bots),
        AdsCommand("botplan", "/ads dev botplan [rooms]", "Prints the bots' ClearPlan: the door and key times and who has which room when.", server = ::botPlan),
        AdsCommand("botreplan", "/ads dev botreplan", "Makes the bots redraw their plan from where the run stands now.", server = ::botReplan),
        AdsCommand("botsim", "/ads dev botsim [plans] [work scale]", "Builds many ClearPlans over the real layouts and compares their splits with the recorded ones.", requiresSim = false, client = ::botSim),
    )

    private fun session(ctx: AdsServerContext): BotsModule.Session? {
        val run = ctx.run
        if (run == null) { ctx.reply("No run is on."); return null }
        val s = BotsModule.session?.takeIf { it.run === run }
        if (s == null) { ctx.reply("This run has no bots (they are off, or there was nobody to plan for)."); return null }
        return s
    }

    private fun name(plan: DungeonPlan, room: Int) = plan.rooms.firstOrNull { it.index == room }?.name ?: "room $room"

    private fun bots(ctx: AdsServerContext) {
        val s = session(ctx) ?: return
        val t = s.run.t
        ctx.reply("Bots at t=$t (${if (s.active) "planning" else "stopped"}), ${s.replans} re-plans, ${if (s.covered) "covering your rooms" else "leaving your rooms to you"}.")
        for (b in s.roster.bots) {
            val p = s.pos[b.index]
            val room = p?.let { s.run.state.roomAt(it.x, it.z)?.plan?.name } ?: "between rooms"
            val next = s.executor.pending().filter { it.seat == b.index }.minByOrNull { it.t }
            val doing = when (next) {
                null -> "nothing left to do"
                is OpenDoor -> "open door ${next.door} at t=${next.t}"
                is ClearMobs -> "clear ${name(s.run.plan, next.room)} by t=${next.until}"
                is FindSecrets -> "find secrets in ${name(s.run.plan, next.room)} by t=${next.until}"
                is SolvePuzzle -> "solve ${name(s.run.plan, next.room)} by t=${next.until}"
            }
            ctx.reply("${s.roster.names[b.index]} (${b.dungeonClass}) in $room at ${p?.let { "%.0f, %.0f".format(it.x, it.z) } ?: "?"}, ${if (s.bodies.has(b.index)) "drawn" else "not drawn"}; next: $doing")
        }
        if (s.kept.isNotEmpty()) ctx.reply("Last re-plans: ${s.kept.takeLast(3).joinToString(" | ")}")
    }

    private fun botPlan(ctx: AdsServerContext) {
        val s = session(ctx) ?: return
        val plan = s.plan
        val ms = plan.milestones
        ctx.reply("ClearPlan built at t=${plan.builtAt}: blood key t=${ms.bloodKeyT}, blood door t=${ms.bloodOpenT}, Watcher done about t=${ms.watcherDoneT}, Maxor about t=${ms.maxorT}${ms.bloodSeat?.let { ", ${s.roster.names[it]} to the blood room" } ?: ""}.")
        for (d in plan.doors.values.sortedBy { it.openT }) {
            val door = s.run.plan.doors[d.door]
            ctx.reply("Door ${d.door} (${name(s.run.plan, door.roomA)} to ${name(s.run.plan, door.roomB)}, ${door.kind}): key t=${d.keyT}${if (d.keySeat != d.seat) " (${s.roster.names[d.keySeat]})" else ""}, opens t=${d.openT} by ${s.roster.names[d.seat]}")
        }
        if (ctx.arg(0) == "rooms") for (r in plan.rooms.values.sortedBy { it.arriveT }) {
            ctx.reply("${name(s.run.plan, r.room)}: ${s.roster.names[r.seat] ?: "?"}${if (r.soft) " (you)" else ""} ${r.work} from t=${r.arriveT} white ${r.whiteT ?: "-"} green ${r.greenT ?: "-"}")
        } else ctx.reply("${plan.rooms.size} rooms planned. Add 'rooms' to list them.")
    }

    private fun botReplan(ctx: AdsServerContext) {
        val s = session(ctx) ?: return
        BotsModule.requestReplan(s.run, "command")
        ctx.reply("The bots will redraw their plan in a moment.")
    }

    private fun botSim(ctx: com.engineerclient.p3sim.run.AdsClientContext) {
        val n = (ctx.arg(0)?.toIntOrNull() ?: 500).coerceIn(10, 5000)
        val scale = ctx.arg(1)?.toDoubleOrNull() ?: BotTuning.ROOM_WORK_SCALE
        ctx.reply("Building $n bot plans and comparing them with the recorded splits...")
        CompletableFuture.supplyAsync { Packs.current ?: Packs.load() }.thenAccept { set ->
            val lines = try { gate(set, n, scale) } catch (t: Throwable) { listOf("The bot plan check failed: ${t.message}") }
            net.minecraft.client.Minecraft.getInstance().execute { lines.forEach(ctx.reply) }
        }
    }

    /** The headless gate on the loaded packs (also what the real-pack test does). */
    fun gate(set: PackSet, n: Int, scale: Double): List<String> {
        val layouts = set.layouts ?: return set.userLines().ifEmpty { listOf("The layout pool is not loaded.") }
        val rs = set.runstate ?: return listOf("The run-state tables are not loaded, so there is nothing recorded to compare with.")
        val dungeons = layouts.pool().mapIndexedNotNull { i, l -> (DungeonPlan.build(l, i.toLong(), set.rooms, rs) as? DungeonPlan.Result.Ok)?.plan }
        if (dungeons.isEmpty()) return listOf("No layout in the pool could be built.")
        return ClearSim.gate(dungeons, BotTables(rs), n, 7L, workScale = scale, areas = PackRoomAreas(set.rooms)).lines()
    }
}
