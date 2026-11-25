package com.engineerclient.mappred

import com.engineerclient.EngineerClient
import com.engineerclient.lcm.LcmStyle
import com.adm.AdmMod.mc
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.CheckmarkUpdateEvent
import com.adm.events.LevelEvent
import com.adm.events.MapUpdateEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.DungeonMap
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DoorType
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.Color.Companion.darker
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Map Prediction on ADM's Dungeon Map (F1-F7, M1-M7): what the unknown tiles probably are, from what the
 * map has shown so far, by a net trained on every recorded map of the floor ([RoomNet], [FloorRules]).
 * On F4-F6 that includes the puzzle column east of the main grid, where a tile is a puzzle or nothing.
 *
 * Each "?" tile behind a door is coloured by its chances (purple puzzle, orange trap, yellow
 * miniboss, red blood, pink fairy, each from Min Chance up; the grey rest is a normal room; the tile behind
 * a blood door is the blood room, drawn as itself), and with Split Contents on it
 * says what that door's side of the map holds: its puzzles, trap, yellow and blood, about how
 * many rooms, and with Split Secrets about how many secrets (its rooms' averages, fitted to the tab's
 * secret total once it shows one: [MapPredictor.splitSecrets]; a guess beside the rooms, never steering them). Each kind of guess
 * has its own slider: a tile Dead End Chance sure to be a puzzle, trap, yellow or rare 1x1 (always
 * from 95%) gets ADM's own guess tile (the types it can be, side by side, with a "?"); one Blood &
 * Fairy Chance sure to be blood or the fairy room gets a guess tile in that room's colour; a big
 * room Big Room Chance sure and a normal 1x1 1x1 Room Chance sure are greyed out at their size; doors
 * between unknown rooms Door Chance sure are drawn where ADM draws doors (black on the way to blood,
 * red the blood door, pink the fairy room's). Near the end, once the secrets the tab list counts
 * leave only one way for the last rooms to be, they're drawn in full with their names. Everything
 * is worked out again on a background thread whenever the map shows more, so a wrong guess changes
 * as rooms open. Only what the map shows is used (never the world scan of rooms nobody has seen).
 * A trained net ([RoomNet], in four sizes) predicts the whole map in one pass whenever it changes.
 *
 * Like the other ADM additions, the settings are registered into ADM's module and saved with it.
 * `MapPathHintsMixin` draws this instead of ADM's "?" tiles while it is on. With LCM Style on, LcmStyle
 * draws it in that look instead: its colours, its guess tiles and doors, and the chances where the "?"s were.
 */
object MapPrediction {

    private enum class Mode { ADM, BASIC, OFF }

    private val mode = SelectorSetting("Room Prediction", Mode.ADM,
        desc = "ADM: unknown rooms coloured by their chances, from the rules every recorded map of the floor follows, updated as rooms open. Basic: a simple guess. Off: no guesses, every unknown tile in the unknown room colour (the blood room behind a blood door still shows). Added by engineerClient.")
    private val on: Boolean get() = mode.value == Mode.ADM
    private val off: Boolean get() = mode.value == Mode.OFF

    private val engine = SelectorSetting("Prediction Engine", RoomNet.Size.BEST,
        desc = "Best is the most exact; Balanced, Fast and Fastest take less time per room.").withDependency { on }

    /** The net size Room Prediction uses; AMap uses the same so only one is loaded. */
    @JvmStatic val netSize: RoomNet.Size get() = engine.value
    private val splitText = BooleanSetting("Split Contents", true,
        desc = "On each \"?\" behind a door: what that side of the map holds (puzzles P, trap T, yellow Y, blood B; a letter shows when it's more likely there than not) and about how many rooms.").withDependency { on }
    private val splitSecrets = BooleanSetting("Split Secrets", true,
        desc = "With Split Contents: about how many secrets that side of the map holds (blue), from the rooms it probably has and, once you've found a secret, the dungeon's secret total. Only a guess; it doesn't change the room prediction.").withDependency { on && splitText.value }
    private val chances = BooleanSetting("Show Chances", false,
        desc = "The most likely special room's chance on each \"?\" tile.").withDependency { on }
    private val minChance = NumberSetting("Min Chance", 0.3f, 0.1..0.9, 0.05f,
        desc = "A room type shows on a \"?\" tile only from this chance up.").withDependency { on }
    private val deadEndDropdown = DropdownSetting("Dead End Prediction",
        desc = "Tiles that are probably a puzzle, trap, yellow or rare room (a 1x1 dead end; blood and fairy have their own) drawn as ADM's guess tile: the types it can be, in their colours, with a \"?\".").withDependency { on }
    private val deadEndChance = NumberSetting("Dead End Chance", 60, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be that a tile is a puzzle, trap, yellow or rare 1x1 before it gets ADM's guess tile. From $ALWAYS_DEAD_END% it always does, whatever this is set to.").withDependency { on && deadEndDropdown.value }
    private val bloodFairyDropdown = DropdownSetting("Blood & Fairy Prediction",
        desc = "Tiles that are probably the blood room or the fairy room drawn as a guess tile in its colour (red, pink) with a \"?\".").withDependency { on }
    private val bloodFairyChance = NumberSetting("Blood & Fairy Chance", 60, 0..100, 1, unit = "%",
        desc = "How sure the prediction has to be that a tile is blood or the fairy room before it's marked.").withDependency { on && bloodFairyDropdown.value }
    private val bigDropdown = DropdownSetting("Big Room Prediction",
        desc = "A big room the prediction is sure of drawn greyed out at its whole size.").withDependency { on }
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
    private val exactOpacity = NumberSetting("Exact Last Rooms Opacity", 75, 0..100, 1, unit = "%",
        desc = "How solid the rooms Exact Last Rooms draws are (their names and secrets stay fully visible).").withDependency { on && exactRooms.value }

    private val colorsDropdown = DropdownSetting("Prediction Colors",
        desc = "The colours predicted rooms are drawn in. Off, they follow the map's own room colours (ADM's, or LCM Colors in LCM Style), darkened.").withDependency { on }
    private val customColors = BooleanSetting("Custom Prediction Colors", false,
        desc = "Draws predicted rooms in the colours below instead of the map's room colours, in ADM's look and LCM Style alike.").withDependency { on && colorsDropdown.value }
    // dtMap's room colours darkened as it darkens a guess, and the grey of a sure big or 1x1 room, to begin with
    private val predPuzzle = predColor("Puzzle", LcmStyle.darker(0xFF750085.toInt()), "a predicted puzzle room")
    private val predTrap = predColor("Trap", LcmStyle.darker(0xFFD87F33.toInt()), "a predicted trap room")
    private val predYellow = predColor("Yellow", LcmStyle.darker(0xFFFEDF00.toInt()), "a predicted yellow (miniboss) room")
    private val predRare = predColor("Rare", LcmStyle.darker(0xFFFFCB59.toInt()), "a predicted rare room")
    private val predBlood = predColor("Blood", LcmStyle.darker(0xFFFF0000.toInt()), "a predicted blood room")
    private val predFairy = predColor("Fairy", LcmStyle.darker(0xFFF4138B.toInt()), "a predicted fairy room")
    private val predRoom = predColor("Room", 0xFF555555.toInt(), "a big or 1x1 room the prediction is sure of")

    private fun predColor(type: String, default: Int, what: String) =
        ColorSetting("Predicted $type", Color(default), true, desc = "Custom Prediction Colors' colour for $what.")
            .withDependency { on && colorsDropdown.value && customColors.value }

    /** A predicted type's colour from Prediction Colors, or null to use the map's room colours. */
    private fun custom(c: Int): Int? {
        if (!customColors.value) return null
        return when (c) {
            Cls.PUZZLE -> predPuzzle
            Cls.TRAP -> predTrap
            Cls.YELLOW -> predYellow
            Cls.RARE -> predRare
            Cls.BLOOD -> predBlood
            Cls.FAIRY -> predFairy
            else -> return null
        }.value.rgba
    }

    private val question = Identifier.fromNamespaceAndPath("engineerclient", "map/question.png")

    @Volatile private var shown: Prediction? = null
    @Volatile private var shownView: MapView? = null
    /** Every unnamed room's names once the map is known for sure ([MapPredictor.certainNames]); null until then. */
    @Volatile private var shownNames: List<NamedRoom>? = null
    /** [shownNames] leaning to rooms the Mimic could still be in (Mimic Steers Names); only ever a guess. */
    @Volatile private var shownSteered: List<NamedRoom>? = null
    /** The key of the view [shown] was made for: while it isn't the map's ([lastKey]), the prediction up is the one before. */
    @Volatile private var shownKey = ""
    private var lastKey = ""
    @Volatile private var mapChanged = false
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "engineerclient-map-prediction").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Doors the map has shown as wither or blood doors this run (the map stops showing that once they're open). */
    private val pathDoors = HashMap<Int, Byte>()

    private const val W = 6
    private const val TILE = 20
    /** A room on the map: Room Size in ADM's look (MapSizes), dtMap's 16 in LCM Style. */
    private val ROOM: Int get() = if (lcm) 16 else com.engineerclient.mapicons.MapSizes.room()
    /** From this sure (percent) a dead end always gets ADM's guess tile, whatever Dead End Chance is. */
    private const val ALWAYS_DEAD_END = 95
    /**
     * Shown as 100% (the chances are drawn rounded): from here anything predicted is drawn as an exact room, and a name
     * as the room's name.
     */
    private const val SURE = 0.995f
    private val BIG_GREY = Color(85, 85, 85, 1f)

    fun install() {
        for (s in listOf(mode, engine, splitText, splitSecrets, chances, minChance, deadEndDropdown, deadEndChance, bloodFairyDropdown, bloodFairyChance, bigDropdown, bigChance, oneDropdown, oneChance, doorDropdown, doorChance, exactRooms, exactOpacity,
            colorsDropdown, customColors, predPuzzle, predTrap, predYellow, predRare, predBlood, predFairy, predRoom)) DungeonMap.registerSetting(s)
        // map packets are read off the game thread: they only mark the map changed and the next tick updates once;
        // the slow poll is for the tab list's puzzles and secrets, which change without the map
        on<MapUpdateEvent> { mapChanged = true }
        on<CheckmarkUpdateEvent> { mapChanged = true }
        on<TickEvent.End> {
            if (mc.player == null) return@on
            if (!mapChanged && (mc.level?.gameTime ?: 0L) % 20L != 0L) return@on
            mapChanged = false
            EngineerClient.safely("map prediction") { update() }
        }
        on<LevelEvent.Load> { reset() }
        on<MessageEvent.Chat> { if (message == "Starting in 1 second.") startAt = System.currentTimeMillis() + 1000L }
        SubscribeWhile.add(this, { mapChanged = true }, ::reset) { DungeonMap.enabled }
    }

    private fun reset() {
        generation.incrementAndGet()
        shown = null; shownView = null; shownNames = null; shownSteered = null; shownKey = ""; lastKey = ""
        doorsFor = null; doorsCache = emptyList()
        pathDoors.clear()
        startAt = 0L
    }

    /** When the run starts ("Starting in 1 second."): nothing is shown before it. */
    private var startAt = 0L
    private fun started(): Boolean =
        (startAt != 0L && System.currentTimeMillis() >= startAt) ||
            // joined mid-run: someone has already opened a room past the entrance
            DungeonScan.rooms.any { it.isViewable && it.type != RoomType.ENTRANCE }

    /** The floor's rules; null on the entrance floor (no recorded maps). */
    private fun rules(): FloorRules? = FloorRules.of(DungeonUtils.floor?.floorNumber ?: 0)

    private fun supported(): Boolean = DungeonUtils.inClear && rules() != null

    /** The map as ADM has read it, discovered rooms only; null when it can't be read cleanly yet. */
    private fun view(): MapView? {
        val rules = rules() ?: return null
        val puzzles = DungeonUtils.puzzleCount
        if (puzzles <= 0) return null
        // ADM's tiles count from the dungeon's north-west corner on every floor, the floor's grid within its 6x6
        val v = MapView(W, W, puzzles, rules)
        for (room in DungeonScan.rooms) {
            if (!room.isViewable || room.tiles.isEmpty()) continue
            val kind = when (room.type) {
                RoomType.ENTRANCE -> Kind.ENTRANCE
                RoomType.NORMAL -> Kind.NORMAL
                // a brown room ADM has seen only on the map is RARE (its map colour is both's) until it scans the room's
                // core, often tens of seconds for a room far off: a normal one to the predictor until then
                RoomType.RARE -> if (room.data == null) Kind.NORMAL else Kind.RARE
                RoomType.PUZZLE -> Kind.PUZZLE
                RoomType.TRAP -> Kind.TRAP
                RoomType.CHAMPION -> Kind.YELLOW
                RoomType.BLOOD -> Kind.BLOOD
                RoomType.FAIRY -> Kind.FAIRY
                else -> continue
            }
            val tiles = room.tiles.map { it.z * W + it.x }.distinct().toIntArray()
            // the name once the room is open, as dtMap has it (ADM knows it then, but shows it only once someone's been in)
            v.addRoom(kind, tiles, LcmStyle.knownName(room)) ?: return null
        }
        if (v.kinds.none { it == Kind.ENTRANCE }) return null
        v.secretTotals = SecretNames.totals(DungeonUtils.secretCount, DungeonUtils.secretPercentage)
        v.puzzleSlots = puzzleSlots()
        val doors = HashMap<Int, Byte>()
        for (door in DungeonScan.doors.values) {
            val p = door.position; val o = door.rotation.offset
            val a = p.z * W + p.x; val b = (p.z + o.z) * W + p.x + o.x
            if (a !in 0 until W * W || b !in 0 until W * W) continue
            val k = when (door.type) { DoorType.Wither -> Edge.WITHER; DoorType.Blood -> Edge.BLOOD; DoorType.Fairy -> Edge.FAIRY; else -> Edge.DOOR }
            doors[pair(a, b)] = k
            if (k != Edge.DOOR) pathDoors[pair(a, b)] = k
        }
        v.closeEdges { a, b -> pathDoors[pair(a, b)] ?: doors[pair(a, b)] }
        return v.connectedPart()
    }

    private fun pair(a: Int, b: Int) = minOf(a, b) * 64 + maxOf(a, b)

    /** The tab list's puzzle lines in the order it draws them (each a name, null for "???"): TabPuzzles. */
    private fun puzzleSlots(): List<String?> = com.engineerclient.misc.TabPuzzles.read().slots.map { it.name }

    private fun update() {
        if (!on || !DungeonMap.enabled || !supported()) {
            if (lastKey.isNotEmpty()) { lastKey = ""; generation.incrementAndGet() }
            return
        }
        // ADM fills rooms in from the map packets as they come; a read mid-change just waits for the next tick
        val v = try { view() } catch (_: ConcurrentModificationException) { null } ?: return
        val size = engine.value
        val steer = com.engineerclient.mimic.MimicFinder.steerNames.value && com.engineerclient.mimic.MimicFinder.active
        val key = v.key() + "|" + size.name + if (steer) "|m${com.engineerclient.mimic.MimicFinder.ruledLocked},${com.engineerclient.mimic.MimicFinder.ruledPlain}" else ""
        if (key == lastKey) return
        lastKey = key
        val gen = generation.incrementAndGet()
        // one pass of the net, off the render and tick threads; a newer map drops it
        worker.execute {
            EngineerClient.safely("map prediction worker") {
                if (generation.get() != gen) return@safely
                val p = RoomNet.of(size).predict(v)
                if (generation.get() != gen) return@safely
                // the names from the secret total once the map is known for sure
                val namer = try { MapPredictor(v) } catch (_: Exception) { null }
                val steered = if (steer) try { MapPredictor(v, com.engineerclient.mimic.MimicFinder::nameFactor).certainNames(p) } catch (_: Exception) { null } else null
                shown = p; shownView = v; shownKey = key; shownNames = namer?.certainNames(p); shownSteered = steered
            }
        }
    }

    /** True while ADM's prediction replaces ADM's "?" tiles (MapPathHintsMixin). */
    @JvmStatic
    /** Wither and blood doors the prediction is sure of between tiles the map hasn't shown; the last pass's while it's catching up. */
    fun certainDoors(): List<com.adm.features.impl.dungeon.map.tile.DungeonDoor> {
        if (!started() || !on || !DungeonMap.enabled || !supported()) return emptyList()
        val p = shown ?: return emptyList()
        if (shownKey != lastKey || p === doorsFor) return doorsCache
        doorsFor = p
        doorsCache = p.doors.filter { it.wither >= SURE || it.blood >= SURE }.map {
            com.adm.features.impl.dungeon.map.tile.DungeonDoor(
                com.adm.utils.IVec2(it.a % W, it.a / W),
                if (it.b == it.a + 1) com.adm.features.impl.dungeon.map.tile.DoorRotation.Horizontal else com.adm.features.impl.dungeon.map.tile.DoorRotation.Vertical,
                if (it.blood >= SURE) DoorType.Blood else DoorType.Wither,
                com.adm.utils.Colors.WHITE,
            )
        }
        return doorsCache
    }

    private var doorsFor: Prediction? = null
    private var doorsCache: List<com.adm.features.impl.dungeon.map.tile.DungeonDoor> = emptyList()

    /** The prediction for the map as it is now; null while it's being worked out or off. */
    fun current(): Prediction? = if (on && DungeonMap.enabled && supported() && shownKey == lastKey) shown else null

    fun replacesCore(): Boolean = on && DungeonMap.enabled && supported() && shown != null

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

    private fun knownTile(t: Int): Boolean = DungeonScan.rooms.any { r -> r.isViewable && r.tiles.any { it.z * W + it.x == t } }

    private fun knownTiles(): BooleanArray {
        val known = BooleanArray(W * W)
        for (room in DungeonScan.rooms) if (room.isViewable) for (t in room.tiles) if (t.x in 0 until W && t.z in 0 until W) known[t.z * W + t.x] = true
        return known
    }

    /** The blood room behind its door: ADM's blood colour, or in LCM Style dtMap's dark red for an unopened blood room. */
    private fun bloodRoom(g: GuiGraphicsExtractor, t: Int) {
        val x = (t % W) * TILE; val y = (t / W) * TILE
        g.fill(x, y, x + ROOM, y + ROOM, if (lcm) LcmStyle.darker(LcmStyle.BLOOD) else DungeonMap.bloodRoomColor.rgba)
    }

    /**
     * Before there's a prediction (or when nothing fits the map): ADM's plain "?" tiles, with the blood room
     * behind a blood door drawn as itself. Only when there is such a door; otherwise ADM draws its own. LCM
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

    /**
     * Room Prediction Off: ADM's "?" tiles all in the unknown room colour (LCM Style's unopened colour), no guess
     * colours, on any floor; the blood room behind a blood door is known, not guessed, so it still shows.
     */
    private fun drawPlain(g: GuiGraphicsExtractor): Boolean {
        if (!DungeonMap.enabled) return false
        val known = knownTiles()
        val blood = bloodTiles(known)
        for (t in hintTiles(known)) if (t !in blood) plainTile(g, t)
        for (t in blood) bloodRoom(g, t)
        return true
    }

    /** Unknown tiles behind a door the map shows (ADM's "?" tiles), the instant the door is on the map. */
    private fun hintTiles(known: BooleanArray): Set<Int> {
        val out = HashSet<Int>()
        for (tile in DungeonScan.pathHints) {
            val t = tile.position.z * W + tile.position.x
            if (t in 0 until W * W && !known[t]) out += t
        }
        return out
    }

    /** ADM's plain "?" tile (LCM Style's plain unopened tile, no "?"). */
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

    /** A type's colour on a guess: ADM's darkened by half, or LCM Style's darkened as dtMap does. */
    private fun dim(c: Int): Int = custom(c) ?: if (lcm) LcmStyle.darker(lcmColor(c)) else colorOf(c).darker(0.5f).rgba

    private val SPECIALS = intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.BLOOD, Cls.FAIRY, Cls.RARE)

    /** Drawing in LCM Style's look (LcmStyle draws the map): its colours and guess tiles, chances where ADM puts a "?". */
    private var lcm = false

    /** Draws the prediction in ADM's map space (20 per tile), in ADM's look or LCM Style's; false when there is none to draw. */
    @JvmStatic
    @JvmOverloads
    fun render(g: GuiGraphicsExtractor, lcm: Boolean = false): Boolean {
        if (!off && !started()) return false
        this.lcm = lcm
        return try { draw(g) } catch (_: ConcurrentModificationException) { false }
    }

    private fun draw(g: GuiGraphicsExtractor): Boolean {
        if (off) return drawPlain(g)
        if (!replacesCore()) return drawWithoutPrediction(g)
        val p = shown ?: return drawWithoutPrediction(g)
        val v = shownView ?: return drawWithoutPrediction(g)
        val known = knownTiles()
        // behind a blood door is the blood room: drawn as itself, never as a guess
        val blood = bloodTiles(known)
        for (t in blood) bloodRoom(g, t)
        // blood and the fairy once the map shows where they are (or the blood door), and while the prediction up is the one
        // for the map before (what the map shows now can rule its spots out): guessed nowhere else
        val stale = shownKey != lastKey
        drop.fill(false)
        drop[Cls.BLOOD] = stale || blood.isNotEmpty() || DungeonScan.rooms.any { it.isViewable && it.type == RoomType.BLOOD }
        drop[Cls.FAIRY] = stale || DungeonScan.rooms.any { it.isViewable && it.type == RoomType.FAIRY }
        val deadEndAt = minOf(deadEndChance.value, ALWAYS_DEAD_END) / 100f
        val oneAt = oneChance.value / 100f
        val bloodFairyAt = bloodFairyChance.value / 100f
        // while a new prediction is still being worked out, the one shown is for the map before the last door:
        // every tile behind a door the map shows is drawn anyway, never left blank
        val hints = hintTiles(known)
        val splits = splitsOf(p)
        labelsFor(p, v)
        val dropBits = (if (drop[Cls.BLOOD]) 1 else 0) or (if (drop[Cls.FAIRY]) 2 else 0)
        // the last rooms the secret total leaves only one size and place for: in full, named when only one name fits
        val exact = exactNow
        exact.fill(false)
        // (every room, discovered ones nobody has opened too, once the map is known for sure)
        if (exactRooms.value) for (r in exactGroups(p, shownNames ?: p.namedRooms, dropBits, shownSteered)) {
            val tiles = r.tiles
            if (tiles.all { known[it] }) { if (!namedOnMap(tiles[0])) roomText(g, r); continue }
            if (tiles.any { known[it] || exact[it] || it in blood }) continue
            val types = r.types ?: continue
            for (t in tiles) exact[t] = true
            exactFill(g, tiles, types, r.lcmOrder)
            roomText(g, r)
        }
        tilesFor(p, dropBits, minChance.value)
        // anything shown at 100% (a room's size and place), named or not: drawn in full in its type's colour (or the
        // kinds a dead end can still be), keeping the split's label on its door's tile
        if (exactRooms.value) for (i in shapes.indices) {
            val tiles = shapes[i]
            if (tiles.any { known[it] || exact[it] || it in blood }) continue
            val types = shapeTypes[i] ?: continue
            for (t in tiles) exact[t] = true
            exactFill(g, tiles, types, shapeLcm[i])
            for (t in tiles) splits[t]?.let { overlay(g, (t % W) * TILE, (t / W) * TILE, it, Float.NaN, false) }
        }
        // the big rooms it's sure of, greyed out at their whole size (likeliest first, never two on one tile)
        val inBig = inBigNow
        inBig.fill(null)
        val bigAt = bigChance.value / 100f
        for (room in p.bigRooms) {
            if (room.chance < bigAt || room.chance <= 0f || room.tiles.any { known[it] || exact[it] || it in blood || inBig[it] != null }) continue
            for (t in room.tiles) inBig[t] = room
            greyRoom(g, room.tiles)
        }
        for (t in 0 until W * W) {
            if (known[t] || t in blood) continue
            // a door the guess up doesn't know yet: the plain "?" at once, the guess when it's worked out
            if (stale && t in hints && t !in splits) { plainTile(g, t); continue }
            if (exact[t]) continue
            val probs = keptAt[t]
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
            val shownCls = shownClsAt[t]!!
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
                // ADM's guess look in the room's colour, as ADM draws a "?" behind the blood door
                g.fill(x, y, x + ROOM, y + ROOM, dim(bfCls))
                overlay(g, x, y, null, bf, true)
            } else if (isDeadEnd && deadEnd >= best) {
                deadEndGuess(g, x, y, t)
                overlay(g, x, y, null, deadEnd, true)
            } else if (isOne) {
                // a normal 1x1, greyed out like the big rooms; a door's tile keeps the split label
                greyRoom(g, SINGLE[t])
                overlay(g, x, y, split, one, split != null)
            } else if (split != null || t in hints) {
                // the "?" tile: its chances as bands across it, the rest grey (a door the prediction doesn't know
                // yet: the chances it gave the tile so far, no split label)
                g.fill(x, y, x + ROOM, y + ROOM, if (lcm) LcmStyle.UNOPENED else DungeonMap.unknownRoomColor.rgba)
                var at = x.toFloat()
                for (c in shownCls) {
                    val wdt = probs[c] * ROOM
                    g.fill(at.roundToInt(), y, (at + wdt).roundToInt().coerceAtMost(x + ROOM), y + ROOM, dim(c))
                    at += wdt
                }
                overlay(g, x, y, split, if (shownCls.isEmpty()) Float.NaN else probs[shownCls[0]], true)
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

    /** A predicted door where ADM draws doors (renderDoors): in the gap between its tiles, in the colour of its kind. */
    private fun door(g: GuiGraphicsExtractor, d: PredictedDoor) {
        if (lcm) {
            // as LCM Style draws a door to rooms nobody has opened: blood and fairy darkened, wither black, else dark grey
            val color = when {
                !drop[Cls.BLOOD] && d.blood >= 0.5f * d.chance -> LcmStyle.darker(LcmStyle.BLOOD)
                !drop[Cls.FAIRY] && d.fairy >= 0.5f * d.chance -> LcmStyle.darker(LcmStyle.FAIRY)
                d.wither >= 0.5f * d.chance -> LcmStyle.WITHER
                else -> LcmStyle.UNOPENED
            }
            LcmStyle.doorAt(g, d.a % W, d.a / W, d.b == d.a + 1, color)
            return
        }
        val color = when {
            !drop[Cls.BLOOD] && d.blood >= 0.5f * d.chance -> DungeonMap.bloodDoorColor
            !drop[Cls.FAIRY] && d.fairy >= 0.5f * d.chance -> DungeonMap.fairyDoorColor
            d.wither >= 0.5f * d.chance -> DungeonMap.witherDoorColor
            else -> DungeonMap.normalDoorColor
        }
        val x = (d.a % W) * TILE; val y = (d.a / W) * TILE
        val width = com.engineerclient.mapicons.MapSizes.door()
        val half = (ROOM - width) / 2
        if (d.b == d.a + 1) g.fill(x + ROOM, y + half, x + TILE, y + half + width, color.rgba)
        else g.fill(x + half, y + ROOM, x + half + width, y + TILE, color.rgba)
    }

    private val SPLITTABLE = setOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.RARE, Cls.BLOOD)

    /** The kinds an exact 1x1 shows (likeliest first); null when it can still be more than one and one of them isn't a dead end or blood. */
    private fun splitTypes(byType: Map<Int, Double>): List<Int>? {
        val possible = byType.filter { it.value > 0.0 }
        if (possible.size > 1 && possible.keys.any { it !in SPLITTABLE }) return null
        val room = possible.values.sum()
        return possible.filter { it.value >= 0.05 * room }.entries.sortedByDescending { it.value }.take(3).map { it.key }
    }

    /** The classes not to guess anywhere while this frame is drawn ([draw]: blood and the fairy once the map shows them, or the prediction is the map before's). */
    private val drop = BooleanArray(Cls.COUNT)

    /** A tile's chances without the [drop]ped classes (the rest as they are: a tile that was mostly blood shows as little). */
    private fun kept(q: FloatArray): FloatArray = if (drop.none { it }) q else FloatArray(q.size) { if (drop[it]) 0f else q[it] }

    // What draw() works out from the prediction up: made again only when the prediction, its names, the drop flags or a setting it reads changes.
    private val exactNow = BooleanArray(W * W)
    private val inBigNow = arrayOfNulls<BigRoom>(W * W)
    private val SINGLE = Array(W * W) { intArrayOf(it) }
    private val PERCENT = Array(101) { "$it%" }
    private val guessColors = Array(5) { IntArray(it) }

    private var splitsFor: Prediction? = null
    private var splitsMap: Map<Int, Split> = emptyMap()

    private fun splitsOf(p: Prediction): Map<Int, Split> {
        if (p !== splitsFor) { splitsMap = p.splits.associateBy { it.tile }; splitsFor = p }
        return splitsMap
    }

    /** A split's labels: what's there with blood, without it, and its rooms (and secrets). */
    private val labels = HashMap<Split, Array<String>>()
    private var labelsP: Prediction? = null
    private var labelsV: MapView? = null
    private var labelsSecrets = false

    private fun labelsFor(p: Prediction, v: MapView) {
        val withSecrets = splitSecrets.value
        if (p === labelsP && v === labelsV && withSecrets == labelsSecrets) return
        val secretsAt = if (withSecrets) MapPredictor.splitSecrets(v, p.splits).let { s -> p.splits.indices.associate { p.splits[it].tile to s[it] } } else emptyMap()
        labels.clear()
        for (s in p.splits) {
            val sec = secretsAt[s.tile]?.let { " §b${it.roundToInt()}s" } ?: ""
            labels[s] = arrayOf(splitTop(s, true), splitTop(s, false), "§f~${s.rooms.roundToInt()}$sec")
        }
        labelsP = p; labelsV = v; labelsSecrets = withSecrets
    }

    private fun splitTop(s: Split, blood: Boolean): String {
        val parts = ArrayList<String>()
        val pz = s.expect[Cls.PUZZLE].roundToInt()
        if (pz > 0) parts += "§d${if (pz > 1) pz else ""}P"
        if (s.expect[Cls.TRAP] >= 0.5f) parts += "§6T"
        if (s.expect[Cls.YELLOW] >= 0.5f) parts += "§eY"
        if (blood && s.expect[Cls.BLOOD] >= 0.5f) parts += "§cB"
        return parts.joinToString(" ").ifEmpty { "§7-" }
    }

    /** An exact room's tiles, the kinds it shows (null: it can still be more than one), and its name text's lines. */
    private class ExactRoom(val tiles: IntArray, val types: List<Int>?, val lcmOrder: IntArray?, val sure: Boolean,
                            val sureLines: List<String>, val blankLines: List<String>, val askLines: List<String>)

    private var groupsP: Prediction? = null
    private var groupsNames: List<NamedRoom>? = null
    private var groupsDrop = -1
    private var groupsSteered: List<NamedRoom>? = null
    private var groups: List<ExactRoom> = emptyList()

    private fun exactGroups(p: Prediction, all: List<NamedRoom>, dropBits: Int, steered: List<NamedRoom>?): List<ExactRoom> {
        if (p === groupsP && all === groupsNames && dropBits == groupsDrop && steered === groupsSteered) return groups
        val guesses = steered?.groupBy { it.tiles.joinToString(",") }
        val out = ArrayList<ExactRoom>()
        for ((_, names) in all.filter { val c = typeOfPool(it.name.pool); c < 0 || !drop[c] }.groupBy { it.tiles.joinToString(",") }) {
            val tiles = names[0].tiles
            if (names.sumOf { it.chance.toDouble() } < SURE) continue
            val byType = names.groupBy { typeOfPool(it.name.pool) }.mapValues { (_, n) -> n.sumOf { it.chance.toDouble() } }
            val types = splitTypes(byType)?.ifEmpty { listOf(typeOfPool(names.maxBy { it.chance }.name.pool)) }
            // Lower and Higher Blaze are one puzzle: when it can be either, it's Blaze
            val eitherBlaze = names.filter { it.name.name in SecretNames.BLAZES && it.chance > 0f }.map { it.name.name }.distinct().size > 1
            val (bestName, bestChance) = names.groupBy { if (eitherBlaze && it.name.name in SecretNames.BLAZES) "Blaze" else it.name.name }
                .mapValues { (_, n) -> n.sumOf { it.chance.toDouble() } }.maxBy { it.value }.let { it.key to it.value }
            val secrets = names.map { it.name.secrets }.distinct().singleOrNull()
            val suffix = if (secrets != null && secrets > 0) " 0/$secrets" else ""
            fun lines(name: String) = (name + suffix).trim().split(" ").filter { it.isNotEmpty() }
            // a name it can't settle: the kind every name that fits has (Miniboss or Mob), and with Mimic Steers Names the likeliest
            val kind = if (names.any { typeOfPool(it.name.pool) >= 0 }) null else names.map { com.engineerclient.mimic.MimicFinder.clearOf(it.name.name) }.distinct().singleOrNull()
                ?.let { when (it) { "miniboss" -> "Miniboss"; "mob" -> "Mob"; else -> null } }
            val guess = guesses?.get(tiles.joinToString(","))?.groupBy { it.name.name }?.mapValues { (_, n) -> n.sumOf { it.chance.toDouble() } }
                ?.maxByOrNull { it.value }?.takeIf { it.value >= 0.5 }?.key
            val unsure = listOfNotNull(guess?.let { "$it?" }, kind).joinToString(" ")
            out += ExactRoom(tiles, types, types?.let { lcmOrder(tiles, it) }, bestChance >= SURE,
                if (bestChance >= SURE) lines(bestName) else emptyList(), lines(unsure), lines(unsure.ifEmpty { "?" }))
        }
        groups = out
        groupsP = p; groupsNames = all; groupsDrop = dropBits; groupsSteered = steered
        return out
    }

    private val LCM_ORDER = listOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.RARE, Cls.BLOOD)

    /** A 1x1 dead end's (or blood's) kinds in LCM Style's order; null when LCM Style draws it as a plain room. */
    private fun lcmOrder(tiles: IntArray, types: List<Int>): IntArray? =
        if (tiles.size == 1 && types.isNotEmpty() && types.all { it in SPLITTABLE }) types.sortedBy { LCM_ORDER.indexOf(it) }.toIntArray() else null

    private var tilesP: Prediction? = null
    private var tilesDrop = -1
    private var tilesMin = Float.NaN
    private var shapes: List<IntArray> = emptyList()
    private var shapeTypes: Array<List<Int>?> = emptyArray()
    private var shapeLcm: Array<IntArray?> = emptyArray()
    private val keptAt = arrayOfNulls<FloatArray>(W * W)
    private val shownClsAt = arrayOfNulls<IntArray>(W * W)
    private val deadEndAdm = arrayOfNulls<IntArray>(W * W)
    private val deadEndLcm = arrayOfNulls<IntArray>(W * W)

    private fun tilesFor(p: Prediction, dropBits: Int, min: Float) {
        if (p === tilesP && dropBits == tilesDrop && min == tilesMin) return
        tilesP = null
        shapes = MapPredictor.certainShapes(p, SURE)
        shapeTypes = Array(shapes.size) { i ->
            val tiles = shapes[i]
            val q = (p.probs[tiles[0]] ?: p.raw[tiles[0]])?.let(::kept)
            if (tiles.size > 1 || q == null) listOf(-1) else {
                val byType = HashMap<Int, Double>()
                for (c in 0 until Cls.COUNT) if (q[c] > 0f) byType.merge(if (c == Cls.NORMAL || c == Cls.BIG) -1 else c, q[c].toDouble(), Double::plus)
                splitTypes(byType)
            }
        }
        shapeLcm = Array(shapes.size) { i -> shapeTypes[i]?.let { lcmOrder(shapes[i], it) } }
        for (t in 0 until W * W) {
            val probs = p.probs[t]?.let(::kept)
            keptAt[t] = probs
            if (probs == null) { shownClsAt[t] = null; deadEndAdm[t] = null; deadEndLcm[t] = null; continue }
            shownClsAt[t] = SPECIALS.filter { probs[it] >= min }.sortedByDescending { probs[it] }.take(3).toIntArray()
            // the kinds a dead end here can be: yellow, trap, puzzle in ADM's order, a rare room on 5-puzzle maps; any at least 5%
            // of the dead end's chance, the likeliest three
            val deadEnd = probs[Cls.PUZZLE] + probs[Cls.TRAP] + probs[Cls.YELLOW] + probs[Cls.RARE]
            val types = intArrayOf(Cls.YELLOW, Cls.TRAP, Cls.PUZZLE, Cls.RARE).filter { probs[it] >= 0.05f * deadEnd }
            val keep = types.sortedByDescending { probs[it] }.take(3).toSet()
            deadEndAdm[t] = types.filter { it in keep }.toIntArray()
            // dtMap's order: puzzle on the outside, trap, yellow in the middle
            deadEndLcm[t] = intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.RARE).filter { it in keep }.toIntArray()
        }
        tilesP = p; tilesDrop = dropBits; tilesMin = min
    }

    private fun typeOfPool(pool: Int): Int = when (pool) {
        MapStats.POOL_PUZZLE -> Cls.PUZZLE
        MapStats.POOL_TRAP -> Cls.TRAP
        MapStats.POOL_YELLOW -> Cls.YELLOW
        MapStats.POOL_BLOOD -> Cls.BLOOD
        MapStats.POOL_FAIRY -> Cls.FAIRY
        MapStats.POOL_RARE -> Cls.RARE
        else -> -1
    }

    /** An exact room's colour for a type (-1: a normal room), as solid as Exact Last Rooms Opacity says. */
    private fun exactColor(type: Int): Int =
        if (lcm) (if (type < 0) LcmStyle.NORMAL else lcmColor(type)) and 0xFFFFFF or (exactOpacity.value * 255 / 100 shl 24)
        else Color((if (type < 0) DungeonMap.normalRoomColor else colorOf(type)).rgba, exactOpacity.value / 100f).rgba

    /**
     * An exact room's shape in its type's colour; a 1x1 that can still be more than one kind (likeliest first) as a
     * guess tile in their colours: dtMap's in LCM Style, else side by side as ADM's.
     */
    private fun exactFill(g: GuiGraphicsExtractor, tiles: IntArray, types: List<Int>, lcmOrder: IntArray?) {
        val x = (tiles[0] % W) * TILE; val y = (tiles[0] / W) * TILE
        if (lcm && lcmOrder != null) {
            // a dead end (or blood) as LCM Style draws ADM's guesses: dtMap's tile, puzzle outside, trap, yellow in the middle
            val colors = guessColors(lcmOrder.size)
            for (i in lcmOrder.indices) colors[i] = LcmStyle.darker(lcmColor(lcmOrder[i]))
            LcmStyle.guessTile(g, x, y, colors)
            return
        }
        if (types.size <= 1 || tiles.size > 1) { fillTiles(g, tiles, exactColor(types.firstOrNull() ?: -1)); return }
        val part = ROOM / types.size
        for (i in types.indices) g.fill(x + i * part, y, if (i == types.lastIndex) x + ROOM else x + (i + 1) * part, y + ROOM, exactColor(types[i]))
    }

    private fun guessColors(n: Int): IntArray = if (n < guessColors.size) guessColors[n] else IntArray(n)

    /** The map names the room on tile [t] itself (it's open: [LcmStyle.knownName]). */
    private fun namedOnMap(t: Int): Boolean =
        DungeonScan.rooms.any { r -> r.tiles.any { it.z * W + it.x == t } && LcmStyle.knownName(r) != null }

    /** ADM's name text over a room: its name when only one fits, else "?" (nothing in LCM Style), with the secrets when every name that fits has the same count. */
    private fun roomText(g: GuiGraphicsExtractor, r: ExactRoom) {
        val tiles = r.tiles
        var sx = 0.0; var sz = 0.0
        for (t in tiles) { sx += ((t % W) * TILE + ROOM / 2f).toDouble(); sz += ((t / W) * TILE + ROOM / 2f).toDouble() }
        val cx = (sx / tiles.size).toFloat()
        val cz = (sz / tiles.size).toFloat()
        val lines = if (r.sure) r.sureLines else if (lcm || tiles.all { knownTile(it) }) r.blankLines else r.askLines
        val fontH = mc.font.lineHeight
        val scale = DungeonMap.textScaling
        val totalH = (lines.size - 1) * fontH * scale
        for (i in lines.indices) {
            g.pose().pushMatrix()
            g.pose().translate(cx, cz - totalH / 2f + i * fontH * scale)
            g.pose().scale(scale)
            g.centeredText(mc.font, lines[i], 0, -fontH / 2, 0xFFB4B4B4.toInt())
            g.pose().popMatrix()
        }
    }

    /** A room's tiles and the gaps between them, as ADM draws a room (fillRoom), in grey (or Predicted Room). */
    private fun greyRoom(g: GuiGraphicsExtractor, tiles: IntArray) =
        fillTiles(g, tiles, if (customColors.value) predRoom.value.rgba else BIG_GREY.rgba)

    private fun fillTiles(g: GuiGraphicsExtractor, tiles: IntArray, c: Int) {
        for (t in tiles) {
            val x = (t % W) * TILE; val y = (t / W) * TILE
            val right = t % W + 1 < W && t + 1 in tiles; val down = t + W in tiles
            g.fill(x, y, x + ROOM, y + ROOM, c)
            if (right) g.fill(x + ROOM, y, x + TILE, y + ROOM, c)
            if (down) g.fill(x, y + ROOM, x + ROOM, y + TILE, c)
            if (right && down && t + W + 1 in tiles) g.fill(x + ROOM, y + ROOM, x + TILE, y + TILE, c)
        }
    }

    /**
     * A dead end's guess tile: ADM's (its renderGuess: one type in its colour, or two or three side by side
     * darkened) or, in LCM Style, dtMap's ([LcmStyle.guessTile], darkened as dtMap does). The types are the ones
     * a dead end here can be: yellow, trap, puzzle in ADM's order, a rare room on 5-puzzle maps; any at least 5%
     * of the dead end's chance, the likeliest three.
     */
    private fun deadEndGuess(g: GuiGraphicsExtractor, x: Int, y: Int, t: Int) {
        if (lcm) {
            val types = deadEndLcm[t]!!
            val colors = guessColors(types.size)
            for (i in types.indices) colors[i] = dim(types[i])
            LcmStyle.guessTile(g, x, y, colors)
            return
        }
        val types = deadEndAdm[t]!!
        if (customColors.value) {
            // ADM's layout in the chosen colours, as chosen (not darkened again)
            val part = ROOM / types.size.coerceAtLeast(1)
            for (i in types.indices) g.fill(x + i * part, y, if (i == types.lastIndex) x + ROOM else x + (i + 1) * part, y + ROOM, dim(types[i]))
            return
        }
        when (types.size) {
            1 -> g.fill(x, y, x + ROOM, y + ROOM, colorOf(types[0]).rgba)
            2 -> {
                val half = ROOM / 2
                g.fill(x, y, x + half, y + ROOM, colorOf(types[0]).darker(0.5f).rgba)
                g.fill(x + half, y, x + ROOM, y + ROOM, colorOf(types[1]).darker(0.5f).rgba)
            }
            else -> {
                val third = ROOM / 3
                g.fill(x, y, x + third, y + ROOM, colorOf(types[0]).darker(0.5f).rgba)
                g.fill(x + third, y, x + third * 2, y + ROOM, colorOf(types[1]).darker(0.5f).rgba)
                g.fill(x + third * 2, y, x + ROOM, y + ROOM, colorOf(types[2]).darker(0.5f).rgba)
            }
        }
    }

    /**
     * What goes on a predicted tile: the split's label (with Split Contents), else ADM's "?" where [question] says
     * ADM draws one, and the [chance] under them with Show Chances. LCM Style never draws the "?" and always
     * shows the chance, in the tile's middle when no label is over it.
     */
    private fun overlay(g: GuiGraphicsExtractor, x: Int, y: Int, split: Split?, chance: Float, question: Boolean) {
        val label = split != null && splitText.value
        if (label) splitLabel(g, x, y, split!!) else if (question && !lcm) icon(g, x, y)
        if (!chance.isNaN() && chances.value) {
            val pct = (chance * 100).roundToInt()
            small(g, if (pct in 0..100) PERCENT[pct] else "$pct%", x + ROOM / 2, if (label || !lcm) y + 13 else y + 8, -1)
        }
    }

    private fun icon(g: GuiGraphicsExtractor, x: Int, y: Int) {
        if (!com.engineerclient.mapicons.MapIcons.showQuestionMarks) return
        val size = ROOM - 4
        g.blit(RenderPipelines.GUI_TEXTURED, question, x + 2, y + 2, size.toFloat(), size.toFloat(), size, size, size, size)
    }

    /** "2P T Y B" (what's more likely there than not, puzzles by their expected count) over "~6" rooms (and "23s" secrets). */
    private fun splitLabel(g: GuiGraphicsExtractor, x: Int, y: Int, s: Split) {
        val l = labels[s] ?: return
        small(g, if (drop[Cls.BLOOD]) l[1] else l[0], x + ROOM / 2, y + 3, -1)
        val mimic = com.engineerclient.mimic.MimicFinder.splitChance(s.tile)
        small(g, if (mimic != null && mimic >= 0.01) l[2] + " §cM${(mimic * 100).roundToInt()}%" else l[2], x + ROOM / 2, y + 8, -1)
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
