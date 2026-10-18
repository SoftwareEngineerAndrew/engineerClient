package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.clear.puzzles.hard.BlockEdit
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID

/** A player's feet position in the room's canonical frame. */
class PlayerPos(val id: UUID, val x: Double, val y: Double, val z: Double)

/** What hurt a player: a crusher on top of them, a tripwire's blast, an arrow volley or a flamethrower. */
enum class HazardKind { CRUSHER, TRIPWIRE, ARROW, FLAME }

/** [line] is the index of the tripwire line that fired (-1 for anything else). */
class HazardHit(val player: UUID, val kind: HazardKind, val line: Int = -1)

/** The sounds a room's hazards make; the volume and pitch are what the recordings show, the module maps them to Minecraft sound events. */
enum class HazardSound(val volume: Float, val pitch: Float) {
    /** A tripwire's hooks click on when the string is touched. */
    LEVER_ON(0.4f, 0.5873016f),
    /** ... and click off when they release. */
    LEVER_OFF(0.4f, 0.4920635f),
    /** The blast on the player a tripwire hit makes. */
    TRIPWIRE_BLAST(4.0f, 2.0f),
    /** A crusher arm reaching full extension. */
    CRUSHER_SLAM(1.0f, 0.4920635f),
    /** A falling floor letting go. */
    FLOOR_COLLAPSE(5.0f, 0.4920635f),
    /** A flamethrower's shot (a dispenser arrow volley makes no sound at the dispensers: none in 150 recorded volleys). */
    FLAME_SHOOT(1.0f, 0.6984127f),
}

/** A sound at a canonical position (x, y, z are in block coordinates, a cell's middle at +0.5). */
class SoundCue(val sound: HazardSound, val x: Double, val y: Double, val z: Double)

/** One part of a flame's picture: the stretch of the stream between [from] and [to] blocks out from the emitter's face (the module draws flame particles there). */
class FlameFx(val emitter: Emitter, val from: Double, val to: Double)

/** What one tick of a room's hazards asks for: blocks to write, players to hurt, sounds to play, floor cells that are about to fall as debris, arrow volleys and flames. */
class HazardOut {
    val edits = ArrayList<BlockEdit>()
    val hits = ArrayList<HazardHit>()
    val cues = ArrayList<SoundCue>()

    /** Canonical cells that turn into falling-block entities this tick; the module reads each block BEFORE it applies [edits]. */
    val debris = ArrayList<IntArray>()

    /** Indexes of the tripwire lines touched this tick (so a falling floor can wait on one). */
    val touched = HashSet<Int>()

    /** The arrow banks that fire this tick (every dispenser of each shoots). */
    val volleys = ArrayList<ArrowRule>()

    /** The flame streams to draw this tick. */
    val flames = ArrayList<FlameFx>()
}

/** A hazard of one room beyond the crushers and the strings: a falling floor, an arrow trap, a flamethrower. Runs on the room's clock. */
interface Hazard {
    fun tick(clock: Int, players: List<PlayerPos>, out: HazardOut)
    fun snapshot(o: JsonObject) {}
    fun restore(o: JsonObject) {}

    /** The block edits that put the hazard's changes back after a savepoint restore wiped the room to its start. */
    fun currentEdits(): List<BlockEdit> = emptyList()
}

/**
 * The moving and firing hazards of one room: its crushers, its tripwires and whatever else it has ([extras]: a falling floor, flamethrowers). Runs on a clock of its own
 * that only moves while somebody is in the room ([tick] is called for those ticks), so the room starts when the first player walks in. Pure:
 * block edits, hits and sounds come out, the caller writes the blocks, hurts the players (exactly 15 percent through the damage service)
 * and plays the sounds.
 *
 * Crushers: each tick the extension of every crusher follows [Crusher.extensionAt]; a player whose box meets a cell of the arm is hit
 * (every tick it holds; the damage service's 10-tick immunity window spaces the hits, and the arm's slam sounds at the first of them). An arm that
 * reaches full extension slams.
 *
 * Tripwires: the player whose box touches the string sets it off at once. The hooks (a lever click each, when [linesClick]) hold for
 * [HOOK_TICKS] ticks and release with a second click; the string can be set off again when they release. When [linesHurt] the toucher is hit by
 * a blast at the same moment. In the many rooms where a real string never hit anyone, or clicked, nothing at all happens (the vanilla hooks flip
 * on their own).
 */
class RoomHazards(
    val crushers: List<Crusher>,
    val lines: List<TripLine>,
    val linesHurt: Boolean = true,
    val linesClick: Boolean = linesHurt,
    val extras: List<Hazard> = emptyList(),
) {
    /** Ticks the clock has run (only counts ticks with a player in the room). */
    var clock: Int = 0
        private set

    private val extension = IntArray(crushers.size)
    private val hitSoundAt = IntArray(crushers.size) { -HIT_SOUND_GAP }
    private val readyAt = IntArray(lines.size)
    private val releaseAt = IntArray(lines.size) { NEVER }

    class Result(
        val edits: List<BlockEdit>, val hits: List<HazardHit>, val cues: List<SoundCue> = emptyList(), val debris: List<IntArray> = emptyList(),
        val volleys: List<ArrowRule> = emptyList(), val flames: List<FlameFx> = emptyList(),
    )

    fun tick(players: List<PlayerPos>): Result {
        clock++
        val out = HazardOut()
        for ((i, c) in crushers.withIndex()) {
            val k = c.extensionAt(clock)
            if (k != extension[i]) {
                out.edits += c.edits(extension[i], k)
                if (k == c.sweep && k > extension[i]) c.tipFace(k).let { out.cues += SoundCue(HazardSound.CRUSHER_SLAM, it[0], it[1], it[2]) }
                extension[i] = k
            }
            if (k == 0) continue
            val box = c.armBox(k)
            for (p in players) if (overlaps(p, box)) {
                out.hits += HazardHit(p.id, HazardKind.CRUSHER)
                // The recorded hit sounded the arm's slam as well; once per immunity window, not on every tick the player stays inside.
                if (clock - hitSoundAt[i] >= HIT_SOUND_GAP) { hitSoundAt[i] = clock; c.tipFace(k).let { out.cues += SoundCue(HazardSound.CRUSHER_SLAM, it[0], it[1], it[2]) } }
            }
        }
        for ((i, l) in lines.withIndex()) {
            if (releaseAt[i] == clock) { if (linesClick) hooks(l, HazardSound.LEVER_OFF, out); releaseAt[i] = NEVER }
            if (clock < readyAt[i]) continue
            val touching = players.filter { l.touches(it.x, it.y, it.z) }
            if (touching.isEmpty()) continue
            readyAt[i] = clock + HOOK_TICKS
            releaseAt[i] = clock + HOOK_TICKS
            out.touched += i
            if (linesClick) hooks(l, HazardSound.LEVER_ON, out)
            if (linesHurt) for (p in touching) {
                out.hits += HazardHit(p.id, HazardKind.TRIPWIRE, i)
                out.cues += SoundCue(HazardSound.TRIPWIRE_BLAST, p.x, p.y, p.z)
            }
        }
        for (h in extras) h.tick(clock, players, out)
        return Result(out.edits, out.hits, out.cues, out.debris, out.volleys, out.flames)
    }

    /** A sound at both hooks of [l]. */
    private fun hooks(l: TripLine, sound: HazardSound, out: HazardOut) {
        for (end in intArrayOf(l.from, l.to)) {
            val x = if (l.axis == 'x') end + 0.5 else l.fixed + 0.5
            val z = if (l.axis == 'x') l.fixed + 0.5 else end + 0.5
            out.cues += SoundCue(sound, x, l.y.toDouble(), z)
        }
    }

    /** Every hazard's changes in their current state, for re-applying after a savepoint restore wiped the room back to its start. */
    fun currentEdits(): List<BlockEdit> =
        crushers.flatMapIndexed { i, c -> if (extension[i] == 0) emptyList() else c.edits(0, extension[i]) } + extras.flatMap { it.currentEdits() }

    fun snapshot(): JsonObject = JsonObject().also { o ->
        o.addProperty("clock", clock)
        o.add("ready", JsonArray().also { a -> readyAt.forEach(a::add) })
        o.add("release", JsonArray().also { a -> releaseAt.forEach(a::add) })
        for ((i, h) in extras.withIndex()) o.add("x$i", JsonObject().also { h.snapshot(it) })
    }

    /** Restores the clock; the crushers' extensions are re-derived from it (the caller applies [currentEdits]). */
    fun restore(o: JsonObject) {
        clock = o.get("clock")?.asInt ?: 0
        o.getAsJsonArray("ready")?.forEachIndexed { i, e -> if (i < readyAt.size) readyAt[i] = e.asInt }
        o.getAsJsonArray("release")?.forEachIndexed { i, e -> if (i < releaseAt.size) releaseAt[i] = e.asInt }
        for ((i, c) in crushers.withIndex()) extension[i] = c.extensionAt(clock)
        for ((i, h) in extras.withIndex()) o.getAsJsonObject("x$i")?.let { h.restore(it) }
    }

    /** Whether the player's box meets the cells of [box] (inclusive cell corners x0, y0, z0, x1, y1, z1). */
    private fun overlaps(p: PlayerPos, box: IntArray): Boolean =
        p.x + HALF > box[0] && p.x - HALF < box[3] + 1 && p.y + HEIGHT > box[1] && p.y < box[4] + 1 && p.z + HALF > box[2] && p.z - HALF < box[5] + 1

    companion object {
        /** Ticks the hooks stay powered after the string is touched (recorded 9 to 11); the string can be set off again when they release. */
        const val HOOK_TICKS = 10

        /** Ticks between two hit sounds of one crusher (the damage service's immunity window). */
        const val HIT_SOUND_GAP = 10
        private const val NEVER = Int.MIN_VALUE
        private const val HALF = 0.3
        private const val HEIGHT = 1.8
    }
}
