package com.engineerclient.p3sim.clear.traps

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * How fast a crusher moves: it advances one block every [ticksPerBlock] ticks and, once back at its resting end, waits [stillTicks]
 * ticks between its last move and its next one (so a crusher that never lingers has the same number for both). The single rest is at the
 * fully retracted end. [sweep], when set, replaces the rooms pack's sweep for this crusher (the Old Trap's two arms are one pincer of 13
 * blocks each, while the pack splits the same cells into a 10 and a 16 block sweep).
 */
class CrusherTiming(val ticksPerBlock: Int, val stillTicks: Int, val sweep: Int? = null) {
    init { require(ticksPerBlock >= 1 && stillTicks >= 1) { "a crusher moves at least every tick and rests at least a tick" } }

    /** Ticks in one out-and-back cycle of a crusher that extends [sweep] blocks: `(2 * sweep - 1)` move intervals and the rest. */
    fun period(sweep: Int): Int = (2 * sweep - 1) * ticksPerBlock + stillTicks
}

/**
 * Crushers whose pace changes from one visit to the next: the New Trap's two low crushers run one of two paces. A group draws ONE option
 * for the whole run ([TrapTimings.roll]); [sharedStart] says whether the crushers of the option begin on the same tick (they always did
 * when both ran 40 / 40) or each on its own ([startSpread] bounds the draw).
 */
class CrusherVariantOption(val crushers: Map<String, CrusherTiming>, val sharedStart: Boolean)
class CrusherVariantGroup(val options: List<CrusherVariantOption>, val startSpread: Int)

/**
 * What a run settled on for its crushers: the pace of each, and the tick (counted from the room waking up) of its first step.
 * Pure data; [TrapTimings.roll] makes it from the run's own random source, so a seed repeats it.
 */
class CrusherPlan(private val timings: Map<String, CrusherTiming>, private val starts: Map<String, Int>, private val fallback: TrapTimings) {
    fun timing(id: String): CrusherTiming = timings[id] ?: fallback.forCrusher(id)

    /** The room-clock tick of the crusher's first step; [Crusher.START_LAG] unless the crusher's group spread it. */
    fun startAt(id: String): Int = starts[id] ?: Crusher.START_LAG
}

/**
 * The crusher timings of the rooms and the data the other hazards need. Every crusher has its own pace (some rest 20 ticks, some 90) and the
 * rooms pack holds only their geometry, so the timings come from an optional file next to the packs, `traps.json`
 * (the spec is kept outside this repository). Without the file (or for a crusher it does not list) every crusher uses [DEFAULT]: 5 ticks a
 * block and a 30-tick rest. The numbers for the two trap rooms are measured from recordings; the rest are unmeasured defaults.
 *
 * [tripwireHurtRooms] are the rooms (by in-game name) whose tripwires hit for 15 percent; a tripwire anywhere else only flips its hooks.
 */
class TrapTimings(
    private val default: CrusherTiming,
    private val byId: Map<String, CrusherTiming>,
    val variants: List<CrusherVariantGroup> = emptyList(),
    val tripwireHurtRooms: Set<String> = DEFAULT_TRIPWIRE_HURT_ROOMS,
    val tripwireClickRooms: Set<String> = DEFAULT_TRIPWIRE_CLICK_ROOMS,
    val sites: TrapSites = TrapSites.NONE,
) {
    fun forCrusher(id: String): CrusherTiming = byId[id] ?: default

    /** Whether a tripwire in the room called [roomName] hits (case and spacing as in game). */
    fun tripwiresHurtIn(roomName: String): Boolean = tripwireHurtRooms.any { it.equals(roomName, ignoreCase = true) }

    /** Whether the hooks of a string that does not hit still click (they do in the Old Trap, where the strings set the floor and the pit off). */
    fun tripwiresClickIn(roomName: String): Boolean = tripwireClickRooms.any { it.equals(roomName, ignoreCase = true) }

    /** Settles the variants and first-step delays for one run. */
    fun roll(random: Random): CrusherPlan {
        val timings = HashMap<String, CrusherTiming>()
        val starts = HashMap<String, Int>()
        for (g in variants) {
            if (g.options.isEmpty()) continue
            val o = g.options[random.nextInt(g.options.size)]
            val shared = if (g.startSpread > 0) random.nextInt(g.startSpread + 1) else Crusher.START_LAG
            for ((id, t) in o.crushers) {
                timings[id] = t
                starts[id] = if (o.sharedStart) shared else if (g.startSpread > 0) random.nextInt(g.startSpread + 1) else Crusher.START_LAG
            }
        }
        return CrusherPlan(timings, starts, this)
    }

    companion object {
        val DEFAULT = CrusherTiming(5, 30)

        /**
         * The rooms in which a real tripwire was ever seen to hit ("The Tripwire Trap hit you for N damage!"): 71 hits in 60 recorded runs,
         * and none in the other 17 rooms that have tripwires (404 Entrance visits, both trap rooms, ...). A room never entered in the
         * recordings (Lava Pit, Rare Overgrown, Trinity) is unmeasured and defaults to no damage.
         */
        val DEFAULT_TRIPWIRE_HURT_ROOMS: Set<String> = setOf(
            "Deathmite", "Cathedral", "Painting", "Chambers", "Pit", "Melon", "Lava Ravine", "Hall", "Golden Oasis", "Altar", "Atlas", "Spider", "Red Green",
        )

        /** Rooms whose strings click without hitting: in the Old Trap each string's hooks clicked on touch and off 10 ticks later; the New Trap's never did. */
        val DEFAULT_TRIPWIRE_CLICK_ROOMS: Set<String> = setOf("Old Trap")

        val NONE = TrapTimings(DEFAULT, emptyMap())

        /** Reads `traps.json` from a folder; a missing or unreadable file gives [NONE] (the caller may mention it, nothing else changes). */
        fun load(packsDir: Path?): TrapTimings = if (packsDir == null) NONE else load(com.engineerclient.p3sim.pack.PackSource.ofDir(packsDir))

        /** Reads `traps.json` as the packs resolved it (the jar's copy, or the override folder's); a missing or unreadable file gives [NONE]. */
        fun load(source: com.engineerclient.p3sim.pack.PackSource): TrapTimings {
            val f = source.file(com.engineerclient.p3sim.pack.PackFiles.TRAPS) ?: return NONE
            return try { parse(Files.readString(f)) } catch (e: Exception) { NONE }
        }

        fun parse(json: String): TrapTimings {
            val root = JsonParser.parseString(json).asJsonObject
            fun timing(o: JsonObject?, fallback: CrusherTiming): CrusherTiming {
                val tpb = o?.get("ticksPerBlock")?.asInt ?: return fallback
                val still = o.get("stillTicks")?.asInt ?: tpb
                val sweep = o.get("sweep")?.asInt?.takeIf { it >= 1 }
                return if (tpb >= 1 && still >= 1) CrusherTiming(tpb, still, sweep) else fallback
            }
            val def = timing(root.getAsJsonObject("default"), DEFAULT)
            val map = HashMap<String, CrusherTiming>()
            root.getAsJsonObject("crushers")?.entrySet()?.forEach { (id, v) -> if (v.isJsonObject) map[id] = timing(v.asJsonObject, def) }
            val groups = root.getAsJsonArray("variants")?.mapNotNull { g ->
                val go = g.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val options = go.getAsJsonArray("options")?.mapNotNull { oe ->
                    val oo = oe.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val cs = HashMap<String, CrusherTiming>()
                    oo.getAsJsonObject("crushers")?.entrySet()?.forEach { (id, v) -> if (v.isJsonObject) cs[id] = timing(v.asJsonObject, def) }
                    if (cs.isEmpty()) null else CrusherVariantOption(cs, oo.get("sharedStart")?.asBoolean ?: false)
                }.orEmpty()
                if (options.isEmpty()) null else CrusherVariantGroup(options, (go.get("startSpread")?.asInt ?: 0).coerceAtLeast(0))
            }.orEmpty()
            val hurt = root.getAsJsonArray("tripwireHurtRooms")?.map { it.asString }?.toSet() ?: DEFAULT_TRIPWIRE_HURT_ROOMS
            val click = root.getAsJsonArray("tripwireClickRooms")?.map { it.asString }?.toSet() ?: DEFAULT_TRIPWIRE_CLICK_ROOMS
            return TrapTimings(def, map, groups, hurt, click, TrapSites.parse(root))
        }
    }
}
