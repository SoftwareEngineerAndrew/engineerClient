package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Three Weirdos in the world: three chests with an NPC stand beside each, the stand named as Odin's solver finds it (an armor
 * stand whose name is the NPC's name, the chest one block east of it in the room's frame), and the sentences as `[NPC] name: text`
 * chat lines. An NPC speaks only when a player clicks it. Opening a chest before all three NPCs have spoken makes that chest's NPC
 * say to talk to all of them first and the chest stays shut. Once all three have spoken, the right chest solves it (the two other
 * chests vanish and the reward chest stays); a wrong one fails it (the NPC says "You fool!" and all three chests vanish) until the
 * Architect's First Draft puts the chests back with the same three sentences.
 *
 * UNMEASURED: the real NPCs' reaction time to a click is not simulated (they answer on the same tick), and the recorded rooms
 * do not say what the chest NPCs do once the room is solved besides losing their names about [NAME_BLANK_TICKS] ticks after the solve.
 */
class WeirdosPuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.WEIRDOS, context) {
    private var scenario: WeirdosLogic.Scenario? = null
    private var names: List<String> = emptyList()
    private var chests: List<WorldPos> = emptyList()
    private val talked = BooleanArray(3)
    private val nameStands = ArrayList<ArmorStand>()
    private var solvedAt = -1

    /** Hypixel names the NPC that holds the reward, in red, whatever chest was clicked. */
    override fun solvedPhrase(): String? {
        val sc = scenario ?: return null
        return "wasn't fooled by §r§c${names[sc.reward]}§r§e!"
    }

    /** Chat first, then the room turns green 3 ticks later. */
    override val greenLeadTicks: Int get() = -GREEN_AFTER_CHAT_TICKS

    override fun rewardSpots(): List<WorldPos> = scenario?.let { sc -> chests.getOrNull(sc.reward)?.let { listOf(it) } }.orEmpty()

    override fun build() {
        val rnd = run.subRandom("weirdos:${context.room.index}")
        scenario = WeirdosLogic.draw(rnd)
        names = WeirdosLogic.drawNames(rnd)
        chests = (1..3).mapNotNull { context.anchor("chest$it").firstOrNull() }
        if (chests.size < 3) { scenario = null; return }
        // A replay: the recording's NPCs (by where their stands were) and the one setting their lines and the solve allow.
        val spots = (1..3).map { i -> context.room.design?.puzzle?.anchors?.get("chest$i")?.firstOrNull()?.let { a -> world(a.x - 1, a.y, a.z) }?.let { doubleArrayOf(it.x + 0.5, it.z + 0.5) } }
        replayRoom?.invoke(run, spots)?.let { rec ->
            rec.names?.takeIf { it.size == 3 }?.let { names = it }
            WeirdosLogic.fit(names, rec)?.let { scenario = it }
        }
        placeChests()
        chests.indices.forEach { i ->
            // The stand is the block west of its chest in the room's frame: Odin takes the chest as the stand's block plus one in x.
            val anchor = context.room.design?.puzzle?.anchors?.get("chest${i + 1}")?.firstOrNull() ?: return@forEach
            val s = world(anchor.x - 1, anchor.y, anchor.z) ?: return@forEach
            nameStands += spawnStand("§c${names[i]}", s.x + 0.5, s.y.toDouble(), s.z + 0.5, invisible = false, marker = false)
            spawnStand("§e§lCLICK", s.x + 0.5, s.y - CLICK_DROP, s.z + 0.5)
        }
        talked.fill(false)
        solvedAt = -1
    }

    private fun placeChests() { chests.forEach { setWorld(it, Blocks.CHEST.defaultBlockState()) } }

    private fun clearChests(except: Int = -1) {
        chests.forEachIndexed { i, c -> if (i != except) setWorld(c, Blocks.AIR.defaultBlockState()) }
    }

    override fun onTick() {
        if (solvedAt >= 0 && now >= solvedAt + NAME_BLANK_TICKS) {
            solvedAt = -1
            nameStands.forEach { runCatching { it.setCustomName(Sim.legacy("")); it.isCustomNameVisible = false } }
        }
    }

    private fun speak(i: Int, text: String) = say("§e[NPC] §r§c${names[i]}§r§f: §r$text")

    override fun onNpcClick(player: UUID, npcName: String): Boolean {
        val i = names.indexOf(npcName)
        if (i < 0) return false
        val sc = scenario ?: return true
        if (status != PuzzleStatus.UNSOLVED) return true
        talked[i] = true
        speak(i, sc.lines(names)[i])
        return true
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val sc = scenario ?: return false
        val i = chests.indexOfFirst { it.x == x && it.y == y && it.z == z }
        if (i < 0) return false
        if (status != PuzzleStatus.UNSOLVED) return status != PuzzleStatus.SOLVED || i != sc.reward
        if (!talked.all { it }) { speak(i, "Talk to all of us first!"); return true }
        if (i == sc.reward) {
            clearChests(except = i)
            solvedAt = now
            solve(player)
        } else {
            speak(i, "You fool!")
            clearChests()
            fail(player, "was fooled by §r§c${names[i]}§r§e!")
        }
        return true
    }

    override fun applySolved() {
        val sc = scenario ?: return
        clearChests(except = sc.reward)
    }

    override fun onReset() {
        // The chests come back and the NPCs say the same three sentences again, so everyone has to be talked to again.
        if (scenario == null) return
        placeChests()
        talked.fill(false)
    }

    companion object {
        /** Ticks from the solve line to the room turning green (measured median 3). */
        const val GREEN_AFTER_CHAT_TICKS = 3

        /** Set by a replay: what the recording shows of the room whose NPC stands stand at these (x, z) spots, slot by slot; null for a drawn room. */
        @Volatile var replayRoom: ((com.engineerclient.p3sim.run.Run, List<DoubleArray?>) -> WeirdosLogic.Recorded?)? = null

        /** The NPC names go blank this long after the solve (measured 46). */
        const val NAME_BLANK_TICKS = 46

        /** The CLICK label stand hangs this far below the name stand. */
        const val CLICK_DROP = 0.25
    }
}
