package com.engineerclient.mappred

import com.engineerclient.EngineerClient
import com.engineerclient.lcm.LcmStyle
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.map.DungeonMap
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.darker
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Map Prediction on Odin's Dungeon Map (F1-F7, M1-M7): what the unknown tiles probably are, from what the
 * map has shown so far and the rules every recorded map of the floor follows ([MapPredictor], [FloorRules]).
 * On F4-F6 that includes the puzzle column east of the main grid, where a tile is a puzzle or nothing.
 *
 * Each "?" tile behind a door is coloured by its chances (purple puzzle, orange trap, yellow
 * miniboss, red blood, pink fairy; the grey rest is a normal room; the tile behind a blood door is the
 * blood room, drawn as itself), and with Split Contents on it
 * says what that door's side of the map holds: its puzzles, trap, yellow and blood, and about how
 * many rooms. Tiles further in get a small mark where a special room is likely. Each kind of guess
 * has its own slider: a tile Dead End Chance sure to be a puzzle, trap, yellow or rare 1x1 (always
 * from 95%) gets Odin's own guess tile (the types it can be, side by side, with a "?"); one Blood &
 * Fairy Chance sure to be blood or the fairy room gets a guess tile in that room's colour; a big
 * room Big Room Chance sure and a normal 1x1 1x1 Room Chance sure are greyed out at their size; doors
 * between unknown rooms Door Chance sure are drawn where Odin draws doors (black on the way to blood,
 * red the blood door, pink the fairy room's). Near the end, once the secrets the tab list counts
 * leave only one way for the last rooms to be, they're drawn in full with their names. Everything
 * is worked out again on a background thread whenever the map shows more, so a wrong guess changes
 * as rooms open. Only what the map shows is used (never the world scan of rooms nobody has seen).
 *
 * Like the other Odin additions, the settings are registered into Odin's module and saved with it.
 * `MapPathHintsMixin` draws this instead of Odin's "?" tiles while it is on. With LCM Style on, LcmStyle
 * draws it in that look instead: its colours, its guess tiles and doors, and the chances where the "?"s were.
 */
object OdinMapPrediction {

    private enum class Mode { ADM, ODIN }

    private val mode = SelectorSetting("Room Prediction", Mode.ADM,
        desc = "ADM: unknown rooms coloured by their chances, from the rules every recorded map of the floor follows, updated as rooms open. Odin: Odin's own guess. Added by engineerClient.")
    private val on: Boolean get() = mode.value == Mode.ADM
    private val splitText = BooleanSetting("Split Contents", true,
        desc = "On each \"?\" behind a door: what that side of the map holds (puzzles P, trap T, yellow Y, blood B; a letter shows when it's more likely there than not) and about how many rooms.").withDependency { on }
    private val hidden = BooleanSetting("Hidden Tile Hints", true,
        desc = "A small mark on unknown tiles further in where a special room is likely.").withDependency { on }
    private val chances = BooleanSetting("Show Chances", false,
        desc = "The most likely special room's chance on each \"?\" tile.").withDependency { on }
    private val minChance = NumberSetting("Min Chance", 0.3f, 0.1..0.9, 0.05f,
        desc = "A room type shows on a tile only from this chance up.").withDependency { on }
    private val deadEndDropdown = DropdownSetting("Dead End Prediction",
        desc = "Tiles that are probably a puzzle, trap, yellow or rare room (a 1x1 dead end; blood and fairy have their own) drawn as Odin's guess tile: the types it can be, in their colours, with a \"?\".").withDependency { on }
    private val deadEndChance = NumberSetting("Dead End Chance", 60, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be that a tile is a puzzle, trap, yellow or rare 1x1 before it gets Odin's guess tile. From $ALWAYS_DEAD_END% it always does, whatever this is set to.").withDependency { on && deadEndDropdown.value }
    private val bloodFairyDropdown = DropdownSetting("Blood & Fairy Prediction",
        desc = "Tiles that are probably the blood room or the fairy room drawn as a guess tile in its colour (red, pink) with a \"?\".").withDependency { on }
    private val bloodFairyChance = NumberSetting("Blood & Fairy Chance", 60, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be that a tile is blood or the fairy room before it's marked.").withDependency { on && bloodFairyDropdown.value }
    private val bigDropdown = DropdownSetting("Big Room Prediction",
        desc = "A big room the prediction is sure of (the same size and place in most samples) drawn greyed out at its whole size.").withDependency { on }
    private val bigChance = NumberSetting("Big Room Chance", 70, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be of a big room's size and place before it's drawn.").withDependency { on && bigDropdown.value }
    private val oneDropdown = DropdownSetting("1x1 Room Prediction",
        desc = "A tile the prediction is sure is a normal 1x1 room (not a dead end, so not a puzzle, trap or yellow) drawn greyed out, like the big rooms.").withDependency { on }
    private val oneChance = NumberSetting("1x1 Room Chance", 70, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be that a tile is a normal 1x1 room before it's drawn.").withDependency { on && oneDropdown.value }
    private val doorDropdown = DropdownSetting("Door Prediction",
        desc = "Doors between rooms nobody has opened yet, where the prediction is sure of them: black on the way to blood, red the blood door, pink where the fairy room opens.").withDependency { on }
    private val doorChance = NumberSetting("Door Chance", 70, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be of a door before it's drawn.").withDependency { on && doorDropdown.value }
    private val exactRooms = BooleanSetting("Exact Last Rooms", true,
        desc = "Once the secrets the tab list counts leave only one way for the last rooms to be (their size, place and name: every room name comes up once a map), they're drawn in full with their name and secrets.").withDependency { on }

    private val question = Identifier.fromNamespaceAndPath("odin", "map/question.png")

    @Volatile private var shown: Prediction? = null
    @Volatile private var shownView: MapView? = null
    private var lastKey = ""
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-map-prediction").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Doors the map has shown as wither or blood doors this run (the map stops showing that once they're open). */
    private val pathDoors = HashMap<Int, Byte>()

    private const val W = 6
    private const val SAMPLES = 2000
    private const val BATCH = 100
    private const val TILE = 20
    private const val ROOM = 16
    /** From this sure (percent) a dead end always gets Odin's guess tile, whatever Dead End Chance is. */
    private const val ALWAYS_DEAD_END = 95
    /** An exact last room: every sample agrees (allowing for float sums). */
    private const val EXACT = 0.999f
    private val BIG_GREY = Color(85, 85, 85, 1f)

    fun install() {
        for (s in listOf(mode, splitText, hidden, chances, minChance, deadEndDropdown, deadEndChance, bloodFairyDropdown, bloodFairyChance, bigDropdown, bigChance, oneDropdown, oneChance, doorDropdown, doorChance, exactRooms)) DungeonMap.registerSetting(s)
        on<TickEvent.End> { if (mc.player != null && (mc.level?.gameTime ?: 0L) % 5L == 0L) EngineerClient.safely("map prediction") { update() } }
        on<LevelEvent.Load> { reset() }
        EventBus.subscribe(this)
    }

    private fun reset() {
        generation.incrementAndGet()
        shown = null; shownView = null; lastKey = ""
        pathDoors.clear()
    }

    /** The floor's rules; null on the entrance floor (no recorded maps). */
    private fun rules(): FloorRules? = FloorRules.of(DungeonUtils.floor?.floorNumber ?: 0)

    private fun supported(): Boolean = DungeonUtils.inClear && rules() != null

    /** The map as Odin has read it, discovered rooms only; null when it can't be read cleanly yet. */
    private fun view(): MapView? {
        val rules = rules() ?: return null
        val puzzles = DungeonUtils.puzzleCount
        if (puzzles <= 0) return null
        // Odin's tiles count from the dungeon's north-west corner on every floor, the floor's grid within its 6x6
        val v = MapView(W, W, puzzles, rules)
        for (room in DungeonScan.rooms) {
            if (!room.isViewable || room.tiles.isEmpty()) continue
            val kind = when (room.type) {
                RoomType.ENTRANCE -> Kind.ENTRANCE
                RoomType.NORMAL -> Kind.NORMAL
                RoomType.RARE -> Kind.RARE
                RoomType.PUZZLE -> Kind.PUZZLE
                RoomType.TRAP -> Kind.TRAP
                RoomType.CHAMPION -> Kind.YELLOW
                RoomType.BLOOD -> Kind.BLOOD
                RoomType.FAIRY -> Kind.FAIRY
                else -> continue
            }
            val tiles = room.tiles.map { it.z * W + it.x }.distinct().toIntArray()
            // a name only once someone has been in the room (what Odin shows on its map too)
            v.addRoom(kind, tiles, if (room.shouldShowName) room.name else null) ?: return null
        }
        if (v.kinds.none { it == Kind.ENTRANCE }) return null
        v.secretTotals = SecretNames.totals(DungeonUtils.secretCount, DungeonUtils.secretPercentage)
        val doors = HashMap<Int, Byte>()
        for (door in DungeonScan.doors.values) {
            val p = door.position; val o = door.rotation.offset
            val a = p.z * W + p.x; val b = (p.z + o.z) * W + p.x + o.x
            if (a !in 0 until W * W || b !in 0 until W * W) continue
            val k = when (door.type) { DoorType.Wither -> Edge.WITHER; DoorType.Blood -> Edge.BLOOD; else -> Edge.DOOR }
            doors[pair(a, b)] = k
            if (k != Edge.DOOR) pathDoors[pair(a, b)] = k
        }
        v.closeEdges { a, b -> pathDoors[pair(a, b)] ?: doors[pair(a, b)] }
        return v
    }

    private fun pair(a: Int, b: Int) = minOf(a, b) * 64 + maxOf(a, b)

    private fun update() {
        if (!on || !DungeonMap.enabled || !supported()) return
        // Odin fills rooms in from the map packets as they come; a read mid-change just waits for the next tick
        val v = try { view() } catch (_: ConcurrentModificationException) { null } ?: return
        val key = v.key()
        if (key == lastKey) return
        lastKey = key
        val gen = generation.incrementAndGet()
        worker.execute {
            EngineerClient.safely("map prediction worker") {
                val predictor = MapPredictor(v)
                val rnd = Random(System.nanoTime())
                // batches until enough samples agree, publishing as it goes; stops when the map changes
                // again, when nothing fits even the loosest rules, or when no more fit
                while (generation.get() == gen && predictor.accepted < SAMPLES) {
                    val kept = predictor.run(BATCH, 40_000, rnd)
                    if (generation.get() != gen) return@safely
                    predictor.prediction()?.let { shown = it; shownView = v }
                    if (kept == 0 && (predictor.accepted > 0 || predictor.level > 2)) break
                }
            }
        }
    }

    /** True while ADM's prediction replaces Odin's "?" tiles (MapPathHintsMixin). */
    @JvmStatic
    fun replacesOdin(): Boolean = on && DungeonMap.enabled && supported() && shown != null

    /** Unknown tiles behind a blood door the map shows: certain to be the blood room, nothing to predict. */
    private fun bloodTiles(known: BooleanArray): Set<Int> {
        val out = HashSet<Int>()
        for (door in DungeonScan.doors.values) {
            if (door.type != DoorType.Blood) continue
            val p = door.position; val o = door.rotation.offset
            val a = p.z * W + p.x; val b = (p.z + o.z) * W + p.x + o.x
            if (a !in 0 until W * W || b !in 0 until W * W) continue
            if (known[a] && !known[b]) out += b
            if (known[b] && !known[a]) out += a
        }
        return out
    }

    private fun knownTiles(): BooleanArray {
        val known = BooleanArray(W * W)
        for (room in DungeonScan.rooms) if (room.isViewable) for (t in room.tiles) if (t.x in 0 until W && t.z in 0 until W) known[t.z * W + t.x] = true
        return known
    }

    /** The blood room behind its door: Odin's blood colour, or in LCM Style dtMap's dark red for an unopened blood room. */
    private fun bloodRoom(g: GuiGraphicsExtractor, t: Int) {
        val x = (t % W) * TILE; val y = (t / W) * TILE
        g.fill(x, y, x + ROOM, y + ROOM, if (lcm) LcmStyle.darker(LcmStyle.BLOOD) else DungeonMap.bloodRoomColor.rgba)
    }

    /**
     * Before there's a prediction (or when nothing fits the map): Odin's plain "?" tiles, with the blood room
     * behind a blood door drawn as itself. Only when there is such a door; otherwise Odin draws its own. LCM
     * Style's plain unopened tiles have no "?".
     */
    private fun drawWithoutPrediction(g: GuiGraphicsExtractor): Boolean {
        if (!on || !DungeonMap.enabled || !supported()) return false
        val known = knownTiles()
        val blood = bloodTiles(known)
        if (blood.isEmpty()) return false
        for (t in hintTiles(known)) if (t !in blood) plainTile(g, t)
        for (t in blood) bloodRoom(g, t)
        return true
    }

    /** Unknown tiles behind a door the map shows (Odin's "?" tiles), the instant the door is on the map. */
    private fun hintTiles(known: BooleanArray): Set<Int> {
        val out = HashSet<Int>()
        for (tile in DungeonScan.pathHints) {
            val t = tile.position.z * W + tile.position.x
            if (t in 0 until W * W && !known[t]) out += t
        }
        return out
    }

    /** Odin's plain "?" tile (LCM Style's plain unopened tile, no "?"). */
    private fun plainTile(g: GuiGraphicsExtractor, t: Int) {
        val x = (t % W) * TILE; val y = (t / W) * TILE
        g.fill(x, y, x + ROOM, y + ROOM, if (lcm) LcmStyle.UNOPENED else DungeonMap.unknownRoomColor.rgba)
        if (!lcm) icon(g, x, y)
    }

    private fun colorOf(c: Int): Color = when (c) {
        Cls.PUZZLE -> DungeonMap.puzzleRoomColor
        Cls.TRAP -> DungeonMap.trapRoomColor
        Cls.YELLOW -> DungeonMap.championRoomColor
        Cls.BLOOD -> DungeonMap.bloodRoomColor
        Cls.FAIRY -> DungeonMap.fairyRoomColor
        Cls.RARE -> DungeonMap.normalRoomColor
        else -> DungeonMap.unknownRoomColor
    }

    /** LCM Style's colour for a type. */
    private fun lcmColor(c: Int): Int = when (c) {
        Cls.PUZZLE -> LcmStyle.PUZZLE
        Cls.TRAP -> LcmStyle.TRAP
        Cls.YELLOW -> LcmStyle.CHAMPION
        Cls.BLOOD -> LcmStyle.BLOOD
        Cls.FAIRY -> LcmStyle.FAIRY
        Cls.RARE -> LcmStyle.RARE
        else -> LcmStyle.UNOPENED
    }

    /** A type's colour on a guess: Odin's darkened by half, or LCM Style's darkened as dtMap does. */
    private fun dim(c: Int): Int = if (lcm) LcmStyle.darker(lcmColor(c)) else colorOf(c).darker(0.5f).rgba

    private val SPECIALS = intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.BLOOD, Cls.FAIRY, Cls.RARE)

    /** "0%".."100%", made once: every chance on the map is drawn every frame. */
    private val PERCENTS = Array(101) { "$it%" }
    private fun percent(chance: Float): String = (chance * 100).roundToInt().let { PERCENTS.getOrNull(it) ?: "$it%" }

    /**
     * [shown]'s splits by their door tile and its named rooms grouped by their tiles. The draw reads them every frame;
     * a prediction is replaced (never changed) a few times a second, so they are worked out once per prediction.
     */
    private var lookupsOf: Prediction? = null
    private var splitsByTile: Map<Int, Split> = emptyMap()
    private var namedByTiles: Collection<List<NamedRoom>> = emptyList()

    private fun lookups(p: Prediction) {
        if (lookupsOf === p) return
        splitsByTile = p.splits.associateBy { it.tile }
        namedByTiles = p.namedRooms.groupBy { it.tiles.joinToString(",") }.values
        lookupsOf = p
    }

    /** Drawing in LCM Style's look (LcmStyle draws the map): its colours and guess tiles, chances where Odin puts a "?". */
    private var lcm = false

    /** Draws the prediction in Odin's map space (20 per tile), in Odin's look or LCM Style's; false when there is none to draw. */
    @JvmStatic
    @JvmOverloads
    fun render(g: GuiGraphicsExtractor, lcm: Boolean = false): Boolean {
        this.lcm = lcm
        return try { draw(g) } catch (_: ConcurrentModificationException) { false }
    }

    private fun draw(g: GuiGraphicsExtractor): Boolean {
        if (!replacesOdin()) return drawWithoutPrediction(g)
        val p = shown ?: return drawWithoutPrediction(g)
        val v = shownView ?: return drawWithoutPrediction(g)
        val known = knownTiles()
        // behind a blood door is the blood room: drawn as itself, never as a guess
        val blood = bloodTiles(known)
        for (t in blood) bloodRoom(g, t)
        val min = minChance.value
        val deadEndAt = minOf(deadEndChance.value, ALWAYS_DEAD_END) / 100f
        val oneAt = oneChance.value / 100f
        val bloodFairyAt = bloodFairyChance.value / 100f
        // while a new prediction is still being worked out, the one shown is for the map before the last door:
        // every tile behind a door the map shows is drawn anyway, never left blank
        val hints = hintTiles(known)
        lookups(p)
        val splits = splitsByTile
        // the last rooms the secret total leaves only one size and place for: in full, named when only one name fits
        val exact = BooleanArray(W * W)
        if (exactRooms.value) for (names in namedByTiles) {
            val tiles = names[0].tiles
            if (names.sumOf { it.chance.toDouble() } < EXACT || tiles.any { known[it] || exact[it] || it in blood }) continue
            for (t in tiles) exact[t] = true
            exactRoom(g, tiles, names)
        }
        // the big rooms it's sure of, greyed out at their whole size (likeliest first, never two on one tile)
        val inBig = arrayOfNulls<BigRoom>(W * W)
        val bigAt = bigChance.value / 100f
        for (room in p.bigRooms) {
            if (room.chance < bigAt || room.chance <= 0f || room.tiles.any { known[it] || exact[it] || it in blood || inBig[it] != null }) continue
            for (t in room.tiles) inBig[t] = room
            greyRoom(g, room.tiles)
        }
        for (t in 0 until W * W) {
            if (known[t] || exact[t] || t in blood) continue
            val probs = p.probs[t]
            if (probs == null || v.roomAt[t] >= 0) {
                if (t in hints) plainTile(g, t)
                continue
            }
            val x = (t % W) * TILE; val y = (t / W) * TILE
            inBig[t]?.let { room ->
                // its door's tile keeps the split's label (or the "?") on the grey
                val split = splits[t]
                if (split != null || t in hints) overlay(g, x, y, split, room.chance, true)
                continue
            }
            // the likeliest special types over the slider: only the "?" tile and Hidden Rooms draw them
            fun shownCls() = SPECIALS.filter { probs[it] >= min }.sortedByDescending { probs[it] }.take(3)
            val split = splits[t]
            // dead end (puzzle, trap, yellow, rare), blood or fairy, or a normal 1x1: each on its own slider, the likeliest that passes
            val deadEnd = probs[Cls.PUZZLE] + probs[Cls.TRAP] + probs[Cls.YELLOW] + probs[Cls.RARE]
            val one = probs[Cls.NORMAL]
            val bfCls = if (probs[Cls.BLOOD] >= probs[Cls.FAIRY]) Cls.BLOOD else Cls.FAIRY
            val bf = probs[bfCls]
            val isDeadEnd = deadEnd > 0f && deadEnd >= deadEndAt
            val isOne = one > 0f && one >= oneAt
            val isBloodFairy = bf > 0f && bf >= bloodFairyAt
            val best = maxOf(if (isDeadEnd) deadEnd else 0f, if (isOne) one else 0f, if (isBloodFairy) bf else 0f)
            if (isBloodFairy && bf >= best) {
                // Odin's guess look in the room's colour, as Odin draws a "?" behind the blood door
                g.fill(x, y, x + ROOM, y + ROOM, dim(bfCls))
                overlay(g, x, y, null, bf, true)
            } else if (isDeadEnd && deadEnd >= best) {
                deadEndGuess(g, x, y, probs, deadEnd)
                overlay(g, x, y, null, deadEnd, true)
            } else if (isOne) {
                // a normal 1x1, greyed out like the big rooms; a door's tile keeps the split label
                greyRoom(g, intArrayOf(t))
                overlay(g, x, y, split, one, split != null)
            } else if (split != null || t in hints) {
                // the "?" tile: its chances as bands across it, the rest grey (a door the prediction doesn't know
                // yet: the chances it gave the tile so far, no split label)
                g.fill(x, y, x + ROOM, y + ROOM, if (lcm) LcmStyle.UNOPENED else DungeonMap.unknownRoomColor.rgba)
                val shownCls = shownCls()
                var at = x.toFloat()
                for (c in shownCls) {
                    val wdt = probs[c] * ROOM
                    g.fill(at.roundToInt(), y, (at + wdt).roundToInt().coerceAtMost(x + ROOM), y + ROOM, dim(c))
                    at += wdt
                }
                overlay(g, x, y, split, shownCls.firstOrNull()?.let { probs[it] }, true)
            } else if (hidden.value) {
                val c = shownCls().firstOrNull() ?: continue
                val a = (0.35f + 0.65f * probs[c]).coerceAtMost(1f)
                val inset = 5
                val color = if (lcm) (LcmStyle.darker(lcmColor(c)) and 0xFFFFFF) or ((a * 255).roundToInt() shl 24) else Color(colorOf(c).darker(0.6f).rgba, a).rgba
                g.fill(x + inset, y + inset, x + ROOM - inset, y + ROOM - inset, color)
            }
        }
        // the doors it's sure of between unknown tiles, over everything else
        val doorAt = doorChance.value / 100f
        for (d in p.doors) {
            if (d.chance < doorAt || d.chance <= 0f || known[d.a] || known[d.b]) continue
            door(g, d)
        }
        return true
    }

    /** A predicted door where Odin draws doors (renderDoors): in the gap between its tiles, in the colour of its kind. */
    private fun door(g: GuiGraphicsExtractor, d: PredictedDoor) {
        if (lcm) {
            // as LCM Style draws a door to rooms nobody has opened: blood and fairy darkened, wither black, else dark grey
            val color = when {
                d.blood >= 0.5f * d.chance -> LcmStyle.darker(LcmStyle.BLOOD)
                d.fairy >= 0.5f * d.chance -> LcmStyle.darker(LcmStyle.FAIRY)
                d.wither >= 0.5f * d.chance -> LcmStyle.WITHER
                else -> LcmStyle.UNOPENED
            }
            LcmStyle.doorAt(g, d.a % W, d.a / W, d.b == d.a + 1, color)
            return
        }
        val color = when {
            d.blood >= 0.5f * d.chance -> DungeonMap.bloodDoorColor
            d.fairy >= 0.5f * d.chance -> DungeonMap.fairyDoorColor
            d.wither >= 0.5f * d.chance -> DungeonMap.witherDoorColor
            else -> DungeonMap.normalDoorColor
        }
        val x = (d.a % W) * TILE; val y = (d.a / W) * TILE
        val half = (ROOM - 8) / 2
        if (d.b == d.a + 1) g.fill(x + ROOM, y + half, x + TILE, y + half + 8, color.rgba)
        else g.fill(x + half, y + ROOM, x + half + 8, y + TILE, color.rgba)
    }

    /**
     * An exact last room: its shape in its type's colour (a little see-through, as nobody has opened it) and
     * Odin's name text - its name when only one fits, else "?" (nothing in LCM Style, which draws no "?") with
     * the secrets if every name that fits has the same count.
     */
    private fun exactRoom(g: GuiGraphicsExtractor, tiles: IntArray, names: List<NamedRoom>) {
        val best = names.maxBy { it.chance }
        val type = when (best.name.pool) {
            MapStats.POOL_PUZZLE -> Cls.PUZZLE
            MapStats.POOL_TRAP -> Cls.TRAP
            MapStats.POOL_YELLOW -> Cls.YELLOW
            MapStats.POOL_BLOOD -> Cls.BLOOD
            MapStats.POOL_FAIRY -> Cls.FAIRY
            else -> -1
        }
        val color = if (lcm) (if (type < 0) LcmStyle.NORMAL else lcmColor(type)) and 0xFFFFFF or (0xBF shl 24)
            else Color((if (type < 0) DungeonMap.normalRoomColor else colorOf(type)).rgba, 0.75f).rgba
        fillTiles(g, tiles, color)
        val cx = tiles.map { (it % W) * TILE + ROOM / 2f }.average().toFloat()
        val cz = tiles.map { (it / W) * TILE + ROOM / 2f }.average().toFloat()
        val secrets = names.map { it.name.secrets }.distinct().singleOrNull()
        val name = if (best.chance >= EXACT) best.name.name else if (lcm) "" else "?"
        val text = name + if (secrets != null && secrets > 0) " 0/$secrets" else ""
        val lines = text.trim().split(" ").filter { it.isNotEmpty() }
        val fontH = mc.font.lineHeight
        val scale = DungeonMap.textScaling
        val totalH = (lines.size - 1) * fontH * scale
        for ((i, line) in lines.withIndex()) {
            g.pose().pushMatrix()
            g.pose().translate(cx, cz - totalH / 2f + i * fontH * scale)
            g.pose().scale(scale)
            g.centeredText(mc.font, line, 0, -fontH / 2, 0xFFB4B4B4.toInt())
            g.pose().popMatrix()
        }
    }

    /** A room's tiles and the gaps between them, as Odin draws a room (fillRoom), in grey. */
    private fun greyRoom(g: GuiGraphicsExtractor, tiles: IntArray) = fillTiles(g, tiles, BIG_GREY.rgba)

    private fun fillTiles(g: GuiGraphicsExtractor, tiles: IntArray, c: Int) {
        val set = tiles.toHashSet()
        for (t in tiles) {
            val x = (t % W) * TILE; val y = (t / W) * TILE
            val right = t % W + 1 < W && t + 1 in set; val down = t + W in set
            g.fill(x, y, x + ROOM, y + ROOM, c)
            if (right) g.fill(x + ROOM, y, x + TILE, y + ROOM, c)
            if (down) g.fill(x, y + ROOM, x + ROOM, y + TILE, c)
            if (right && down && t + W + 1 in set) g.fill(x + ROOM, y + ROOM, x + TILE, y + TILE, c)
        }
    }

    /**
     * A dead end's guess tile: Odin's (its renderGuess: one type in its colour, or two or three side by side
     * darkened) or, in LCM Style, dtMap's ([LcmStyle.guessTile], darkened as dtMap does). The types are the ones
     * a dead end here can be: yellow, trap, puzzle in Odin's order, a rare room on 5-puzzle maps; any at least 5%
     * of the dead end's chance, the likeliest three.
     */
    private fun deadEndGuess(g: GuiGraphicsExtractor, x: Int, y: Int, probs: FloatArray, deadEnd: Float) {
        val types = intArrayOf(Cls.YELLOW, Cls.TRAP, Cls.PUZZLE, Cls.RARE).filter { probs[it] >= 0.05f * deadEnd }
        val keep = types.sortedByDescending { probs[it] }.take(3).toSet()
        if (lcm) {
            // dtMap's order: puzzle on the outside, trap, yellow in the middle
            LcmStyle.guessTile(g, x, y, intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.RARE).filter { it in keep }.map { dim(it) }.toIntArray())
            return
        }
        val colors = types.filter { it in keep }.map { colorOf(it) }
        when (colors.size) {
            1 -> g.fill(x, y, x + ROOM, y + ROOM, colors[0].rgba)
            2 -> {
                val half = ROOM / 2
                g.fill(x, y, x + half, y + ROOM, colors[0].darker(0.5f).rgba)
                g.fill(x + half, y, x + ROOM, y + ROOM, colors[1].darker(0.5f).rgba)
            }
            else -> {
                val third = ROOM / 3
                g.fill(x, y, x + third, y + ROOM, colors[0].darker(0.5f).rgba)
                g.fill(x + third, y, x + third * 2, y + ROOM, colors[1].darker(0.5f).rgba)
                g.fill(x + third * 2, y, x + ROOM, y + ROOM, colors[2].darker(0.5f).rgba)
            }
        }
    }

    /**
     * What goes on a predicted tile: the split's label (with Split Contents), else Odin's "?" where [question] says
     * Odin draws one, and the [chance] under them with Show Chances. LCM Style never draws the "?" and always
     * shows the chance, in the tile's middle when no label is over it.
     */
    private fun overlay(g: GuiGraphicsExtractor, x: Int, y: Int, split: Split?, chance: Float?, question: Boolean) {
        val label = split != null && splitText.value
        if (label) splitLabel(g, x, y, split!!) else if (question && !lcm) icon(g, x, y)
        if (chance != null && (lcm || chances.value)) small(g, percent(chance), x + ROOM / 2, if (label || !lcm) y + 13 else y + 8, -1)
    }

    private fun icon(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val size = ROOM - 4
        g.blit(RenderPipelines.GUI_TEXTURED, question, x + 2, y + 2, size.toFloat(), size.toFloat(), size, size, size, size)
    }

    /** "2P T Y B" (what's more likely there than not, puzzles by their expected count) over "~6" rooms. */
    private fun splitLabel(g: GuiGraphicsExtractor, x: Int, y: Int, s: Split) {
        val parts = ArrayList<String>()
        val pz = s.expect[Cls.PUZZLE].roundToInt()
        if (pz > 0) parts += "§d${if (pz > 1) pz else ""}P"
        if (s.expect[Cls.TRAP] >= 0.5f) parts += "§6T"
        if (s.expect[Cls.YELLOW] >= 0.5f) parts += "§eY"
        if (s.expect[Cls.BLOOD] >= 0.5f) parts += "§cB"
        val top = parts.joinToString(" ").ifEmpty { "§7-" }
        small(g, top, x + ROOM / 2, y + 3, -1)
        small(g, "§f~${s.rooms.roundToInt()}", x + ROOM / 2, y + 8, -1)
    }

    private fun small(g: GuiGraphicsExtractor, text: String, cx: Int, cy: Int, color: Int) {
        val scale = minOf(0.45f, (ROOM - 1f) / maxOf(1, mc.font.width(text)))
        g.pose().pushMatrix()
        g.pose().translate(cx.toFloat(), cy.toFloat())
        g.pose().scale(scale)
        g.centeredText(mc.font, text, 0, -mc.font.lineHeight / 2, color)
        g.pose().popMatrix()
    }
}
