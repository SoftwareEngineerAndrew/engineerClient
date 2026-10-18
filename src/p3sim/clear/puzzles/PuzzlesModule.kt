package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.AdsModules
import com.engineerclient.p3sim.run.EndReason
import com.engineerclient.p3sim.run.ModuleEvent
import com.engineerclient.p3sim.run.PuzzleFailed
import com.engineerclient.p3sim.run.PuzzleRecord
import com.engineerclient.p3sim.run.PuzzleSolved
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.RoomEntered
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.SecretFound
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.PuzzleHost
import com.engineerclient.p3sim.run.api.PuzzleType
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The shared puzzle frame (tab rows, the reward chest signal, Hypixel's solved and failed lines, the reset item, the routing of
 * clicks to the puzzle of the room) and the easy and medium puzzles: Three Weirdos, Quiz, Creeper Beams, Boulder, Ice Fill,
 * Tic Tac Toe, Higher Or Lower. It collects every module's `puzzleTypes()` at run start (the hard puzzles come from the
 * `hardpuzzles` module through the same list).
 *
 * What the frame does for a run:
 *  - one [PuzzleRecord] per puzzle room of the layout (the layout decides which rooms hold which puzzle, so the 2/3/4/5 count is the
 *    layout's; [PuzzleDraw] is the measured draw for a generated map), added to `RunState.puzzles` for the HUD's tab
 *    ([PuzzleLines.tabHeadline] and [PuzzleLines.tabRow] are the words);
 *  - the tab row shows `???` until a player enters the room;
 *  - a solve: the room turns green through `PuzzleSolved` (the state module), the puzzle counts as Completed at once and the 20-tick tab refresh shows it (Higher Or Lower:
 *    Completed at the last kill, the green mark when its chest stops, 244 ticks after the first kill; Tic Tac Toe: green 2 ticks after the last mark, Completed at 13), the reward chest's cherry-log block event goes to the clients
 *    (Odin's finish signal), and Weirdos and Tic Tac Toe say `PUZZLE SOLVED!`;
 *  - a fail: `PUZZLE FAIL!`, the `PuzzleFailed` event, and the failing player gets the Architect's First Draft, which resets
 *    Higher Or Lower, Three Weirdos and Tic Tac Toe (`You used the Architect's First Draft to reset ...!`).
 *
 * FILLED BY: the `puzzles` work item (owns `src/p3sim/clear/puzzles/` and below except `hard/`).
 */
object PuzzlesModule : AdsModule, PuzzleHost {
    override val id = "puzzles"
    override val owner = "puzzles"

    private val log = LoggerFactory.getLogger("engineerclient")

    /** The reset item's name (what Odin's draft prompt fetches). */
    const val DRAFT_NAME = "Architect's First Draft"

    /** Players within this many blocks of a puzzle room's middle make it build its world state (the chunks are loaded then). */
    const val ACTIVATE_RANGE = 48.0

    private class Later(val at: Int, val what: () -> Unit)

    private class RunData(val run: Run) {
        val types = HashMap<String, PuzzleType>()
        val puzzles = ArrayList<Puzzle>()
        val byRoom = HashMap<Int, Puzzle>()
        val later = ArrayList<Later>()
        /** Puzzles that are being solved (the solve is delayed a few ticks). */
        val solving = HashSet<Puzzle>()
    }

    private var data: RunData? = null

    private fun dataOf(run: Run): RunData? = data?.takeIf { it.run === run }

    // ---- the types ----------------------------------------------------------------------------------------------

    override fun puzzleTypes(): List<PuzzleType> = listOf(
        PuzzleType(PuzzleKinds.WEIRDOS, "Three Weirdos", setOf("Three_Weirdos")) { WeirdosPuzzle(it) },
        PuzzleType(PuzzleKinds.QUIZ, "Quiz", setOf("Quiz")) { QuizPuzzle(it) },
        PuzzleType(PuzzleKinds.BEAMS, "Creeper Beams", setOf("Creeper_Beams")) { BeamsPuzzle(it) },
        PuzzleType(PuzzleKinds.BOULDER, "Boulder", setOf("Boulder")) { BoulderPuzzle(it) },
        PuzzleType(PuzzleKinds.ICE_FILL, "Ice Fill", setOf("Ice_Fill")) { IceFillPuzzle(it) },
        PuzzleType(PuzzleKinds.TTT, "Tic Tac Toe", setOf("Tic_Tac_Toe")) { TicTacToePuzzle(it) },
        PuzzleType(PuzzleKinds.BLAZE, "Higher Or Lower", setOf("Blaze_Lower", "Blaze_Upper")) { BlazePuzzle(it) },
    )

    // ---- run lifecycle ------------------------------------------------------------------------------------------

    override fun onRunStart(run: Run) {
        val d = RunData(run)
        data = d
        run.state.puzzles.clear()
        for (m in AdsModules.all) {
            val types = try { m.puzzleTypes() } catch (t: Throwable) { log.error("[ads] ${m.id}.puzzleTypes failed", t); emptyList() }
            for (t in types) d.types.putIfAbsent(t.kind, t)
        }
        for (room in run.plan.rooms) {
            if (room.type != RoomType.PUZZLE) continue
            val kind = PuzzleKinds.fromPackKind(room.design?.puzzle?.kind) ?: PuzzleKinds.fromFamily(room.family)
                ?: d.types.values.firstOrNull { room.family in it.families }?.kind
            if (kind == null) { log.warn("[ads] the puzzle room ${room.name} (${room.family}) has no known puzzle kind"); continue }
            val type = d.types[kind]
            val record = PuzzleRecord(room.index, kind, type?.tabName ?: PuzzleKinds.tabNames[kind] ?: room.name)
            run.state.puzzles += record
            if (type == null) { log.warn("[ads] no module provides the $kind puzzle; the room ${room.name} stays unsolved (/ads dev puzzlesolve)"); continue }
            val puzzle = try { type.create(PuzzleContext(run, room, record, this)) } catch (t: Throwable) { log.error("[ads] creating the $kind puzzle failed", t); continue }
            d.puzzles += puzzle
            d.byRoom[room.index] = puzzle
            try { puzzle.onRunStart() } catch (t: Throwable) { log.error("[ads] $kind onRunStart failed", t) }
        }
    }

    override fun onRunEnd(run: Run, reason: EndReason) {
        val d = dataOf(run) ?: return
        for (p in d.puzzles) (p as? WorldPuzzle)?.deactivate()
        data = null
    }

    override fun onTick(run: Run) {
        val d = dataOf(run) ?: return
        val due = d.later.filter { it.at <= run.serverTick }
        if (due.isNotEmpty()) { d.later.removeAll(due.toSet()); due.forEach { guard("delayed step") { it.what() } } }
        if (!run.stage.inDungeon) return
        val humans = Sim.players()
        if (humans.isEmpty()) return
        val occupied = HashSet<Int>()
        for (p in humans) {
            run.state.roomAt(p.x, p.z)?.index?.let { occupied += it }
        }
        for (puzzle in d.puzzles) {
            val room = puzzle.context.room
            if (puzzle is WorldPuzzle) {
                if (!puzzle.activated && humans.any { near(it, room) }) guard("${puzzle.kind} activate") { puzzle.activate() }
                if (puzzle.activated) {
                    if (room.index in occupied) puzzle.noteEntered(run.serverTick)
                    // Wakes the room a few ticks after the first step into it, then runs its timed steps (even with nobody inside).
                    guard("${puzzle.kind} pump") { puzzle.pump(run.serverTick) }
                }
            }
            if (room.index in occupied && (puzzle !is WorldPuzzle || (puzzle.activated && puzzle.awake))) guard("${puzzle.kind} onTick") { puzzle.onTick() }
        }
    }

    private fun near(p: ServerPlayer, room: com.engineerclient.p3sim.run.PlacedRoom): Boolean {
        val cx = room.tiles.map { DungeonFrame.tileCentre(it.x) }.average()
        val cz = room.tiles.map { DungeonFrame.tileCentre(it.z) }.average()
        return (p.x - cx) * (p.x - cx) + (p.z - cz) * (p.z - cz) <= ACTIVATE_RANGE * ACTIVATE_RANGE
    }

    override fun onEvent(run: Run, event: RunEvent) {
        val d = dataOf(run) ?: return
        when (event) {
            is RoomEntered -> {
                val puzzle = d.byRoom[event.room]
                val record = run.state.puzzles.firstOrNull { it.roomIndex == event.room } ?: return
                val human = run.state.players[event.player]?.isBot != true
                if (human && !record.revealed) record.revealed = true
                if (human && puzzle != null) {
                    if (puzzle is WorldPuzzle) { puzzle.activate(); puzzle.noteEntered(run.serverTick) }
                    guard("${puzzle.kind} onRoomEntered") { puzzle.onRoomEntered(event.player) }
                }
            }
            is SecretFound -> (d.byRoom[event.room] as? WorldPuzzle)?.takeIf { it.activated }?.let { wp -> guard("${wp.kind} onSecretFound") { wp.onSecretFound(event.x, event.y, event.z, event.player) } }
            // The damage module tells the bus where an arrow landed (an integration request in the puzzles report): x, y, z, by.
            is ModuleEvent -> if (event.source == "damage" && event.name == "arrowHitBlock") {
                val x = (event.data["x"] as? Number)?.toInt() ?: return
                val y = (event.data["y"] as? Number)?.toInt() ?: return
                val z = (event.data["z"] as? Number)?.toInt() ?: return
                val room = run.state.roomAt(x + 0.5, z + 0.5)?.index ?: return
                (d.byRoom[room] as? WorldPuzzle)?.takeIf { it.activated }?.onArrowHit(x, y, z, event.data["by"] as? UUID)
            }
            else -> {}
        }
    }

    // ---- savepoints ---------------------------------------------------------------------------------------------

    override fun snapshot(run: Run): JsonObject? {
        val d = dataOf(run) ?: return null
        val o = JsonObject()
        o.add("records", JsonArray().also { a ->
            for (r in run.state.puzzles) a.add(JsonObject().also { j ->
                j.addProperty("room", r.roomIndex); j.addProperty("status", r.status.name); j.addProperty("revealed", r.revealed)
                j.addProperty("failures", r.failures); r.solvedBy?.let { j.addProperty("solvedBy", it.toString()) }
            })
        })
        o.add("puzzles", JsonArray().also { a ->
            for (p in d.puzzles) p.snapshot()?.let { s -> a.add(JsonObject().also { j -> j.addProperty("room", p.context.room.index); j.add("data", s) }) }
        })
        return o
    }

    override fun restore(run: Run, data: JsonObject) {
        val d = dataOf(run) ?: return
        d.later.clear(); d.solving.clear()
        data.getAsJsonArray("records")?.forEach { e ->
            val j = e.asJsonObject
            val r = run.state.puzzles.firstOrNull { it.roomIndex == j["room"].asInt } ?: return@forEach
            r.status = runCatching { PuzzleStatus.valueOf(j["status"].asString) }.getOrDefault(PuzzleStatus.UNSOLVED)
            r.revealed = j["revealed"]?.asBoolean ?: false
            r.failures = j["failures"]?.asInt ?: 0
            r.solvedBy = j["solvedBy"]?.asString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }
        // The blocks went back to the start state with the region; each puzzle builds itself again when a player comes near.
        for (p in d.puzzles) (p as? WorldPuzzle)?.deactivate()
        data.getAsJsonArray("puzzles")?.forEach { e ->
            val j = e.asJsonObject
            d.byRoom[j["room"].asInt]?.let { p -> guard("${p.kind} restore") { p.restore(j.getAsJsonObject("data")) } }
        }
    }

    // ---- the host (what the puzzles call) -----------------------------------------------------------------------

    override fun puzzleOf(run: Run, roomIndex: Int): Puzzle? = dataOf(run)?.byRoom?.get(roomIndex)

    /** A player's name for the lines: the party record, else the server player, else a stand-in. */
    fun nameOf(run: Run, player: UUID?): String =
        player?.let { run.state.players[it]?.name ?: Sim.players().firstOrNull { p -> p.uuid == it }?.gameProfile?.name } ?: "Someone"

    override fun reportSolved(puzzle: Puzzle, by: UUID?) {
        val run = puzzle.context.run
        val d = dataOf(run) ?: return
        val rec = puzzle.context.record
        if (rec.status == PuzzleStatus.SOLVED || puzzle in d.solving) return
        d.solving += puzzle
        val wp = puzzle as? WorldPuzzle
        val timing = wp?.solveTiming() ?: SolveTiming.fromLead(wp?.greenLeadTicks ?: GREEN_LEAD_TICKS)
        val name = nameOf(run, by)
        val index = run.state.puzzles.indexOf(rec)
        fun announce() { wp?.solvedPhrase()?.let { Services.channels.chat(PuzzleLines.solved(name, it)) } }
        fun complete() {
            d.solving -= puzzle
            rec.status = PuzzleStatus.SOLVED
            rec.solvedBy = by
        }
        fun green() {
            if (wp == null || wp.rewardSignalAtGreen) rewardChestOpened(puzzle, by)
            // A puzzle room's one secret is its reward chest: it counts now, so the room can turn green (a room that prints 0 has nothing to count).
            if (wp == null || wp.countsRewardAsSecret) run.state.room(rec.roomIndex)?.let { rr ->
                if (rr.secretsFound < rr.secretsMax) {
                    val spot = wp?.rewardSpots()?.firstOrNull()
                    Services.secrets.found(run, rec.roomIndex, com.engineerclient.p3sim.run.SecretKind.CHEST, by, spot?.x ?: 0, spot?.y ?: 0, spot?.z ?: 0)
                }
            }
            run.bus.post(PuzzleSolved(run.t, index, rec.roomIndex, by))
        }
        // Steps due on the same tick run green, then the line, then Completed (the order the single lead number always gave).
        val steps = listOf(timing.green to ::green, timing.chat to ::announce, timing.complete to ::complete).sortedBy { it.first }
        for ((delay, step) in steps) if (delay <= 0) step() else later(d, run.serverTick + delay, step)
    }

    override fun reportFailed(puzzle: Puzzle, by: UUID?, reason: String) {
        val run = puzzle.context.run
        val rec = puzzle.context.record
        if (rec.status == PuzzleStatus.SOLVED) return
        rec.status = PuzzleStatus.FAILED
        rec.failures++
        val wp = puzzle as? WorldPuzzle
        if (wp == null || wp.printsFailLine) Services.channels.chat(PuzzleLines.failed(nameOf(run, by), reason))
        val index = run.state.puzzles.indexOf(rec)
        run.bus.post(PuzzleFailed(run.t, index, rec.roomIndex, by, reason))
        // Players fetch the draft from their sack a tick or two after the fail line (1 tick in the Ice Fill, Tic Tac Toe and Higher Or Lower samples):
        // the sack line and the item arrive in the same tick.
        if (rec.kind in PuzzleKinds.resettable && by != null) {
            val d = dataOf(run)
            if (d == null) giveDraft(by)
            else later(d, run.serverTick + 1) {
                Services.channels.chat(PuzzleLines.SACK_MOVED, com.engineerclient.p3sim.run.api.Audience.Only(by))
                giveDraft(by)
            }
        }
    }

    override fun rewardChestOpened(puzzle: Puzzle, by: UUID?) {
        val spots = (puzzle as? WorldPuzzle)?.rewardSpots().orEmpty()
        for (s in spots) sendRewardSignal(BlockPos(s.x, s.y, s.z))
    }

    /** Odin's chest-open signal: a cherry-log block event at the chest's position, to every player. */
    fun sendRewardSignal(pos: BlockPos) {
        val packet = ClientboundBlockEventPacket(pos, Blocks.CHERRY_LOG, 1, 1)
        for (p in Sim.players()) p.connection.send(packet)
    }

    private fun later(d: RunData, at: Int, what: () -> Unit) { d.later += Later(at, what) }

    /** Sets a puzzle back to its start (the reset item, the Quiz starting over): the tab row goes back to Incomplete. */
    fun resetPuzzle(puzzle: Puzzle) {
        val rec = puzzle.context.record
        if (rec.status == PuzzleStatus.SOLVED) return
        rec.status = PuzzleStatus.UNSOLVED
        guard("${puzzle.kind} onReset") { puzzle.onReset() }
    }

    // ---- the reset item -----------------------------------------------------------------------------------------

    /** The lines under the draft's name, as Hypixel shows them. */
    private val DRAFT_LORE = listOf(
        "§7Can be used to reset a puzzle room", "§7when in dungeons.", "", "§7A 'reset' button seems to have been",
        "§7hidden somewhere in the floor, just", "§7in case something goes wrong...", "", "§cDungeons only!", "", "§5§lEPIC DUNGEON ITEM",
    )

    /** The Architect's First Draft: a paper with that name (dark purple, not italic) and Hypixel's lore. */
    fun draftItem(): ItemStack {
        val s = ItemStack(Items.PAPER)
        s.set(DataComponents.CUSTOM_NAME, Component.literal(DRAFT_NAME).withStyle { it.withColor(ChatFormatting.DARK_PURPLE).withItalic(false) })
        s.set(DataComponents.LORE, ItemLore(DRAFT_LORE.map { Sim.legacy(it).copy().withStyle { st -> st.withItalic(false) } }))
        return s
    }

    private fun isDraft(s: ItemStack) = !s.isEmpty && s.hoverName.string == DRAFT_NAME

    /** Puts a draft in a player's inventory (what the sack command does on Hypixel); the sack line is printed by the caller. */
    fun giveDraft(player: UUID) {
        val p = Sim.players().firstOrNull { it.uuid == player } ?: return
        if (p.inventory.contains { isDraft(it) }) return
        p.inventory.add(draftItem())
    }

    // ---- player interaction -------------------------------------------------------------------------------------

    override fun onUseBlock(run: Run, player: ServerPlayer, hand: InteractionHand, hit: BlockHitResult): InteractionResult? {
        val d = dataOf(run) ?: return null
        val pos = hit.blockPos
        val room = run.state.roomAt(pos.x + 0.5, pos.z + 0.5)?.index ?: return null
        val puzzle = d.byRoom[room] ?: return null
        if (puzzle is WorldPuzzle && !puzzle.activated) return null
        return if (puzzle.onInteract(player.uuid, pos.x, pos.y, pos.z)) InteractionResult.SUCCESS else null
    }

    override fun onUseEntity(run: Run, player: ServerPlayer, hand: InteractionHand, entity: Entity): InteractionResult? {
        val d = dataOf(run) ?: return null
        val puzzle = puzzleOfEntity(d, entity) ?: return null
        val name = entity.customName?.string ?: return null
        return if (puzzle.onNpcClick(player.uuid, name)) InteractionResult.SUCCESS else null
    }

    override fun onAttackEntity(run: Run, player: ServerPlayer, hand: InteractionHand, entity: Entity): InteractionResult? {
        val d = dataOf(run) ?: return null
        val puzzle = puzzleOfEntity(d, entity) as? WorldPuzzle ?: return null
        return if (puzzle.onAttack(player.uuid, entity)) InteractionResult.SUCCESS else null
    }

    private fun puzzleOfEntity(d: RunData, e: Entity): Puzzle? {
        if (WorldPuzzle.TAG !in e.entityTags()) return null
        val room = e.entityTags().firstOrNull { it.startsWith(WorldPuzzle.TAG_ROOM) }?.removePrefix(WorldPuzzle.TAG_ROOM)?.toIntOrNull() ?: return null
        return d.byRoom[room]
    }

    override fun onUseItem(run: Run, player: ServerPlayer, hand: InteractionHand): InteractionResult? {
        val d = dataOf(run) ?: return null
        val held = player.getItemInHand(hand)
        if (!isDraft(held)) return null
        val room = run.state.roomAt(player.x, player.z)?.index
        val puzzle = room?.let { d.byRoom[it] }
        val rec = puzzle?.context?.record
        if (puzzle == null || rec == null || rec.kind !in PuzzleKinds.resettable || rec.status == PuzzleStatus.SOLVED) {
            Sim.note("The $DRAFT_NAME only works in an unsolved Higher Or Lower, Three Weirdos or Tic Tac Toe room.")
            return InteractionResult.FAIL
        }
        held.shrink(1)
        resetPuzzle(puzzle)
        Services.channels.chat(PuzzleLines.reset(rec.tabName), com.engineerclient.p3sim.run.api.Audience.Only(player.uuid))
        return InteractionResult.SUCCESS
    }

    // ---- dev commands -------------------------------------------------------------------------------------------

    override fun devCommands(): List<AdsCommand> = listOf(
        AdsCommand("puzzles", "/ads dev puzzles", "Lists the puzzle rooms of the run: kind, status, where, and whether the room is built.", server = { ctx ->
            val run = ctx.run
            val d = run?.let { dataOf(it) }
            if (run == null || d == null) { ctx.reply("No run is on."); return@AdsCommand }
            ctx.reply(PuzzleLines.tabHeadline(run.state.puzzles.size))
            run.state.puzzles.forEachIndexed { i, r ->
                val room = run.plan.rooms[r.roomIndex]
                val p = d.byRoom[r.roomIndex]
                val cx = room.tiles.map { DungeonFrame.tileCentre(it.x) }.average().toInt()
                val cz = room.tiles.map { DungeonFrame.tileCentre(it.z) }.average().toInt()
                val built = if (p == null) "no module" else if (p is WorldPuzzle) (if (p.activated) "built" else "not built yet") else "hard puzzle"
                ctx.reply("#$i ${r.tabName} (${r.kind}) ${r.status} ${PuzzleLines.glyph(r.status)} room ${room.name} at $cx, $cz, $built")
            }
        }),
        AdsCommand("puzzlesolve", "/ads dev puzzlesolve <number>", "Solves a puzzle by its number from /ads dev puzzles.", server = { ctx ->
            val run = ctx.run
            val rec = run?.state?.puzzles?.getOrNull(ctx.arg(0)?.toIntOrNull() ?: -1)
            val p = rec?.let { puzzleOf(run, it.roomIndex) }
            if (run == null || rec == null) { ctx.reply("Usage: /ads dev puzzlesolve <number> (see /ads dev puzzles)."); return@AdsCommand }
            if (p == null) {
                rec.status = PuzzleStatus.SOLVED; rec.solvedBy = ctx.player.uuid
                run.bus.post(PuzzleSolved(run.t, run.state.puzzles.indexOf(rec), rec.roomIndex, ctx.player.uuid))
            } else reportSolved(p, ctx.player.uuid)
            ctx.reply("Solved ${rec.tabName}.")
        }),
        AdsCommand("puzzlewake", "/ads dev puzzlewake <number>", "Builds a puzzle room and wakes it at once (the blazes and the chest, the Ice Fill chests, Tic Tac Toe's first mark) without walking there.", server = { ctx ->
            val run = ctx.run
            val rec = run?.state?.puzzles?.getOrNull(ctx.arg(0)?.toIntOrNull() ?: -1)
            val p = rec?.let { puzzleOf(run, it.roomIndex) } as? WorldPuzzle
            if (rec == null || p == null) { ctx.reply("Usage: /ads dev puzzlewake <number> (see /ads dev puzzles; Higher Or Lower, Ice Fill and Tic Tac Toe wake)."); return@AdsCommand }
            p.wakeNow()
            ctx.reply("Woke ${rec.tabName}.")
        }),
        AdsCommand("puzzlereset", "/ads dev puzzlereset <number>", "Puts a puzzle back to its start.", server = { ctx ->
            val run = ctx.run
            val rec = run?.state?.puzzles?.getOrNull(ctx.arg(0)?.toIntOrNull() ?: -1)
            val p = rec?.let { puzzleOf(run, it.roomIndex) }
            if (rec == null || p == null) { ctx.reply("Usage: /ads dev puzzlereset <number> (see /ads dev puzzles)."); return@AdsCommand }
            rec.status = PuzzleStatus.UNSOLVED
            guard("reset") { p.onReset() }
            ctx.reply("Reset ${rec.tabName}.")
        }),
        AdsCommand("draft", "/ads dev draft", "Gives you the Architect's First Draft (the puzzle reset item).", server = { ctx ->
            ctx.player.inventory.add(draftItem())
            ctx.reply("Here is the $DRAFT_NAME.")
        }),
        AdsCommand("puzzledraw", "/ads dev puzzledraw [count]", "Shows a draw of puzzle rooms by the measured odds (2/3/4/5 rooms; Blaze rooms never both).", requiresSim = false, client = { ctx ->
            val rnd = kotlin.random.Random(System.nanoTime())
            val n = ctx.arg(0)?.toIntOrNull()?.coerceIn(1, 10)
            val picks = if (n != null) PuzzleDraw.drawDesigns(rnd, n) else PuzzleDraw.draw(rnd)
            ctx.reply("${PuzzleLines.tabHeadline(picks.size)}: " + picks.joinToString(", ") { it.room })
        }),
    )

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log.error("[ads] puzzles: $what failed", t) }
    }

    /** UNMEASURED fallback for a puzzle that is not a [WorldPuzzle]: the measured lead of the green mark over Completed. */
    const val GREEN_LEAD_TICKS = 0
}
