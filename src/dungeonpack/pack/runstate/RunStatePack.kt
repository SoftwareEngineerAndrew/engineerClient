package com.engineerclient.dungeonpack.pack.runstate

import com.engineerclient.dungeonpack.pack.*
import com.engineerclient.dungeonpack.pack.rooms.checkHeader
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/*
 * The "Run-state tables" data pack, version 1 (FORMAT-runstate.md): door and key rules, room durations, the check-state
 * machine, score calibration, class roles, puzzle odds, bonus rates, SkyBlock stat presets, damage rules, lag presets and
 * the documented defaults for what is not measured.
 *
 * Every top-level section is independent: a section that is missing or malformed is null here, with one line in the
 * report naming it, and only the feature that needs it is disabled (FORMAT-runstate 1). Each typed section keeps the
 * parsed JSON in `raw` too, so a module can read a field this skeleton did not model. All durations are server ticks
 * with lag removed; the lag layer re-adds loss at run time and applying it twice is a bug.
 */

/** FORMAT-runstate 3: per-room secrets, crypts and durations. Key = the room's in-game name. */
class RoomRun(
    val name: String,
    val type: String,
    val shape: String,
    /** Tile count (a 1x4 is 4, a 2x2 is 4, an L is 3): the counter weight. */
    val tiles: Int,
    /** The number the action bar prints as M and the tab/Odin total use. Authoritative; never `secretsDb`. */
    val secretsMax: Int,
    val secretsMaxSource: String?,
    /** How many secrets the rooms pack can place for this design. When lower than [secretsMax] the rest are auto-credited. */
    val secretsDb: Int?,
    val cryptsPotential: Int?,
    /** `mob`, `miniboss`, `none` or null. */
    val clear: String?,
    /** First entry -> GREEN, all classes pooled; null = use [RunStatePack.roomTypeDur]. */
    val dur: Dist?,
    val byClass: Map<String, Dist>,
    val toWhite: Dist?,
    val toGreen: Dist?,
)

/**
 * FORMAT-runstate 6.4: the one chest whose opening finishes a trap room. A trap room has no mob to kill; its check mark turns white a few
 * ticks after this chest is opened. [secret] is the rooms-pack secret id and [cell] its canonical (x, y, z) in the design's own frame
 * (turn it with the room's placement); [afterOpen] is the measured gap from the first open to the white mark (0 to 5 ticks, median 2).
 */
class ChestClear(val room: String, val secret: String?, val cell: Pos?, val afterOpen: Dist?, val visits: Int)

/** FORMAT-runstate 4: door and key rules and tick constants. */
class DoorRules(
    val volumeW: Int, val volumeH: Int, val volumeD: Int, val volumeY0: Int,
    /** Ticks a door stays barrier before all 36 blocks turn to air (14). */
    val barrierTicks: Int,
    val barrierTicksQ: DoubleArray,
    /** Null when unmeasured: use `START_LEAD_TICKS` (20). */
    val startLeadTicks: Int?,
    val entranceFallAfterStart1: Dist?,
    val freeFairyDoorWithinTicks: Int,
    val witherPerLayoutCounts: Map<Int, Int>,
    val paidKeysPerRunCounts: Map<Int, Int>,
    val keyObtainToUse: Dist?,
    val pickupInstantFrac: Double,
    val pickupDelayed: Dist?,
    val autoCollectTicks: Int,
    val standWitherY: Double, val standBloodY: Double,
    val standNameWither: String, val standNameBlood: String,
    val standLifetime: Dist?,
    val noKeyChat: String,
    val noKeySoundId: String, val noKeySoundVolume: Double, val noKeySoundPitch: Double,
    val bloodKeyAfterStart: Dist?,
    val bloodDoorAfterKey: Dist?,
    val raw: JsonObject,
)

/** FORMAT-runstate 5: milestones, ticks since T0. */
class Splits(val dists: Map<String, Dist>, val raw: JsonObject) {
    val bloodOpen: Dist? get() = dists["bloodOpen"]
    val bloodKey: Dist? get() = dists["bloodKey"]
    val watcherDone: Dist? get() = dists["watcherDone"]
    val maxorEntry: Dist? get() = dists["maxorEntry"]
    val defeated: Dist? get() = dists["defeated"]
    val campTicks: Dist? get() = dists["campTicks"]
    val provenToMaxor: Dist? get() = dists["provenToMaxor"]
    val tilesDoneAtMaxor: Dist? get() = dists["tilesDoneAtMaxor"]
    val roomsGreenFracAtMaxor: Dist? get() = dists["roomsGreenFracAtMaxor"]
}

/** FORMAT-runstate 6.2: one room type's transition paths (`UNWG` = undiscovered, none, white, green) and tick distributions (`NtoW`, `WtoG`, `NtoG`). */
class TypeStates(val paths: Map<String, Int>, val ticks: Map<String, Dist>)

class CheckStates(
    val types: Map<String, TypeStates>,
    val greenNeedsAllSecrets: Boolean,
    val zeroSecretGoesStraightToGreen: Boolean,
    val raw: JsonObject,
)

/** FORMAT-runstate 6.3. */
class CounterRules(val totalUnits: Int, val excludeEntrance: Boolean, val tabRefreshTicks: Int, val raw: JsonObject)

/** FORMAT-runstate 7: the fitted team score. See `ScoreModule` for evaluation (7.3). */
class ScoreModel(
    val periodTicks: Int,
    val firstUpdateTick: Int,
    val endScreen: String?,
    val maxSecretPoints: Double,
    val secretsPctDecimals: Int,
    /** `table[c]` for `c` completed tiles (37 entries, c = 0..36), deaths 0 with secrets and bonus removed. */
    val roomsTable: DoubleArray,
    val roomsInterp: String?,
    val roomsRounding: String?,
    val deathsFormula: String?,
    /**
     * Points per unsolved puzzle, as the pack writes it: NEGATIVE when fitted (-9.04; the canonical value is -10). Null = not
     * fitted; the score module then uses `UNMEASURED_PUZZLE_PENALTY` (10 points, subtracted).
     */
    val puzzlePenaltyPerUnsolved: Double?,
    val cryptWeight: Double, val cryptCap: Int,
    val mimicWeight: Double, val princeWeight: Double, val batWeight: Double,
    val bonusWeightsFitted: Boolean,
    /** Speed term as `[seconds, points]` pairs, piecewise linear. */
    val speedPointsSec: List<Pair<Double, Double>>,
    val speedFitted: Boolean,
    val constant: Double,
    val raw: JsonObject,
    /** The fitted weight on the secrets term (0.991 in the built pack; 1.0 when the pack has none). */
    val secretWeight: Double = 1.0,
    /** `S` is recomputed at `T0 + clockOriginTicksAfterT0 + periodTicks * k` for k >= 1 (the built pack: 20, so the first value shows at T0 + 220). */
    val clockOriginTicksAfterT0: Int = 0,
    /** The pack's own statement of the exact formula (read it before writing the score function), or null. */
    val evaluation: String? = null,
)

/** FORMAT-runstate 9.2: per-class roles for the bots' ClearPlans. */
class ClassRole(
    val roomsOwnedMean: Double, val puzzlesOwnedMean: Double, val doorOpenerFrac: Double,
    val distanceBlocks: Dist?, val movingSpeedBpt: Double?,
)

class ClassRoles(val n: Int, val classes: Map<String, ClassRole>, val ownedRoomsSortedMean: DoubleArray, val standingFrac: Double?, val firstEntrantTopLeaderFrac: Double?, val raw: JsonObject)

/** FORMAT-runstate 10.1: how many puzzles a layout has and which ones can occur. */
class PuzzleOdds(val countOddsN: Int, val counts: Map<Int, Int>, val pool: List<PuzzleEntry>, val raw: JsonObject) {
    class PuzzleEntry(val code: String, val rooms: List<String>, val mapShare: Double, val exclusivePair: Boolean)
}

/** FORMAT-runstate 11.2: a SkyBlock stat preset. */
class SbStatPreset(val id: String, val label: String, val floor: Int, val maxHp: Int, val defense: Int, val maxMana: Int, val speed: Int, val vitality: Int?)

/** FORMAT-runstate 11.3. */
class DamageRules(val trapPctOfMaxHp: Double, val defenseFormula: String?, val raw: JsonObject)

/** FORMAT-runstate 12: lag presets and the freeze-then-burst shape. */
class LagTier(val good: Double, val typical: Double, val bad: Double, val mean: Double?, val slotOver200: Double?, val slotMultiplier: Double?)

class LagModel(
    val typicalLossPerMin: Double,
    val tiers: Map<String, LagTier>,
    val runLevelMedian: Double?, val runLevelSigma: Double?, val runLevelP10: Double?, val runLevelP90: Double?,
    /** `[gapMs, perHour]`: freezes at least this long per hour. */
    val freezeExceedPerHour: List<Pair<Double, Double>>,
    val tickIntervalMsPct: DoubleArray, val tickIntervalMsQ: DoubleArray,
    val recoverFracWithin5s: Double?, val burstRateTicksPerSec: Double?,
    val rttMedianMs: Double?,
    val raw: JsonObject,
)

/** The `constants.UNMEASURED` list: named defaults the pack could not measure. */
class NamedConstant(val name: String, val value: Any?, val why: String?)

/**
 * The loaded run-state tables. Typed sections are null when absent or malformed (see [problems]).
 * [raw] is the whole parsed file for fields this class does not model.
 */
class RunStatePack internal constructor(
    val version: Int,
    val pct: DoubleArray,
    val rooms: Map<String, RoomRun>,
    val doors: DoorRules?,
    val splits: Map<String, Splits>,
    val checkStates: CheckStates?,
    val counters: CounterRules?,
    val score: ScoreModel?,
    val roomTypeDur: Map<String, Dist>,
    val classRoles: ClassRoles?,
    val puzzleSolve: Map<String, Dist>,
    val puzzles: PuzzleOdds?,
    val chatFormats: List<String>,
    val sbStatPresets: List<SbStatPreset>,
    val damage: DamageRules?,
    val lag: LagModel?,
    val constants: List<NamedConstant>,
    /** One line per section that was missing or malformed. */
    val problems: List<String>,
    val raw: JsonObject,
    /** Trap rooms by in-game name: the chest that finishes them (FORMAT-runstate 6.4). Empty when the pack has no `chestClears` section. */
    val chestClears: Map<String, ChestClear> = emptyMap(),
) {
    /** The tables for a room (in-game name, spaces; U+200B stripped); null when the pack has none. */
    fun room(name: String): RoomRun? = rooms[name.replace("​", "")]

    /** The chest that finishes the trap room [name], or null (not a trap room, or the pack has no rule for it). */
    fun chestClear(name: String): ChestClear? = chestClears[name.replace("\u200b", "")]

    fun constant(name: String): NamedConstant? = constants.firstOrNull { it.name == name }

    /**
     * A top-level section this class does not model (`hud`, `loot`, `blessings`, `bonus`, `crypts`, `timing`...), as parsed JSON,
     * or null. The sidebar and tab templates (`hud`) and the loot and blessing tables are read this way.
     */
    fun section(name: String): JsonObject? = raw.obj(name)

    companion object {
        const val FORMAT = "ads-runstate"
        const val SUPPORTED_VERSION = 1

        /** Loads `runstate.json.gz` from [dir]. Null, with the reason in [report], when missing/unreadable/wrong format. Never throws. */
        fun load(dir: Path, report: PackReport = PackReport()): RunStatePack? = load(PackSource.ofDir(dir), report)

        /** [load] from a resolved [PackSource] (the jar's copy or the override folder's). */
        fun load(source: PackSource, report: PackReport = PackReport()): RunStatePack? {
            val path = source.file(PackFiles.RUNSTATE)
            if (path == null) { report.error("The run-state tables ${PackFiles.RUNSTATE} are missing from ${source.missingFrom}."); return null }
            val root = try { Files.newInputStream(path).use { readGzipJson(it) } } catch (e: Exception) {
                report.error("The run-state tables ${PackFiles.RUNSTATE} are unreadable${source.note(PackFiles.RUNSTATE)}: ${e.message}"); return null
            }
            return fromJson(root, report)
        }

        fun fromJson(root: JsonObject, report: PackReport = PackReport()): RunStatePack? {
            if (!checkHeader(root, FORMAT, SUPPORTED_VERSION, PackFiles.RUNSTATE, report)) return null
            val problems = ArrayList<String>()
            val pct = root.arr("pct")?.doubles()?.takeIf { it.isNotEmpty() } ?: Dist.DEFAULT_PCT

            /** A section: parsed when present; a missing or malformed one is reported by name and null. */
            fun <T> section(name: String, required: Boolean = true, parse: (JsonObject) -> T?): T? {
                val o = root.obj(name)
                if (o == null) { if (required) problems += "section '$name' is missing"; return null }
                return try { parse(o) ?: run { problems += "section '$name' is malformed"; null } } catch (e: Exception) {
                    problems += "section '$name' is malformed: ${e.message}"; null
                }
            }

            val rooms = section("rooms") { parseRooms(it, pct) }.orEmpty()
            val doors = section("doors") { parseDoors(it, pct) }
            val splits = section("splits") { s -> s.entrySet().mapNotNull { (k, v) -> v.asObj()?.let { k to parseSplits(it, pct) } }.toMap() }.orEmpty()
            val checkStates = section("checkStates") { parseCheckStates(it, pct) }
            val counters = section("counters") { CounterRules(it.int("totalUnits") ?: 36, it.bool("excludeEntrance") ?: true, it.int("tabRefreshTicks") ?: 20, it) }
            val score = section("score") { parseScore(it) }
            val timing = root.obj("timing")
            if (timing == null) problems += "section 'timing' is missing"
            val roomTypeDur = timing?.obj("roomTypeDur")?.entrySet()?.mapNotNull { (k, v) -> Dist.parse(v, pct)?.let { k to it } }?.toMap().orEmpty()
            val classRoles = timing?.obj("classRoles")?.let { try { parseClassRoles(it, pct) } catch (e: Exception) { problems += "timing.classRoles is malformed: ${e.message}"; null } }
            val puzzleSolve = timing?.obj("puzzleSolve")?.entrySet()?.mapNotNull { (k, v) -> Dist.parse(v, pct)?.let { k to it } }?.toMap().orEmpty()
            val puzzles = section("puzzles") { parsePuzzles(it) }
            val chatFormats = root.arr("chatFormats").strings()
            val sbPresets = root.obj("sbStats")?.arr("presets").objects().map { p ->
                SbStatPreset(p.str("id") ?: "?", p.str("label") ?: (p.str("id") ?: "?"), p.int("floor") ?: 7, p.int("maxHp") ?: 0, p.int("defense") ?: 0,
                    p.int("maxMana") ?: 0, p.int("speed") ?: 0, p.int("vitality"))
            }
            val damage = section("damage") { DamageRules(it.dbl("trapPctOfMaxHp") ?: 0.15, it.str("defenseFormula"), it) }
            val lag = section("lag") { parseLag(it) }
            val chestClears = section("chestClears", required = false) { parseChestClears(it, pct) }.orEmpty()
            val constants = root.obj("constants")?.arr("UNMEASURED").objects().map { c ->
                NamedConstant(c.str("name") ?: "?", primitive(c.get("value")), c.str("why"))
            }
            for (p in problems) report.warn("Run-state tables: $p. The feature that needs it uses its built-in defaults.")
            return RunStatePack(root.int("version") ?: 1, pct, rooms, doors, splits, checkStates, counters, score, roomTypeDur, classRoles, puzzleSolve, puzzles,
                chatFormats, sbPresets, damage, lag, constants, problems, root, chestClears)
        }

        private fun primitive(e: com.google.gson.JsonElement?): Any? {
            if (e == null || !e.isJsonPrimitive) return null
            val p = e.asJsonPrimitive
            return if (p.isNumber) p.asDouble else if (p.isBoolean) p.asBoolean else p.asString
        }

        private fun dists(o: JsonObject?, pct: DoubleArray): Map<String, Dist> =
            o?.entrySet()?.mapNotNull { (k, v) -> Dist.parse(v, pct)?.let { k to it } }?.toMap().orEmpty()

        private fun intMap(o: JsonObject?): Map<Int, Int> =
            o?.entrySet()?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { kk -> v.asIntOrNull()?.let { kk to it } } }?.toMap().orEmpty()

        private fun parseRooms(o: JsonObject, pct: DoubleArray): Map<String, RoomRun> =
            o.entrySet().mapNotNull { (name, v) ->
                val r = v.asObj() ?: return@mapNotNull null
                val key = name.replace("​", "")
                key to RoomRun(
                    key, r.str("type") ?: "normal", r.str("shape") ?: "1x1", r.int("tiles") ?: 1, r.int("secretsMax") ?: 0, r.str("secretsMaxSource"),
                    r.int("secretsDb"), r.int("cryptsPotential"), r.str("clear"),
                    Dist.parse(r.get("dur"), pct), dists(r.obj("byClass"), pct), Dist.parse(r.get("toWhite"), pct), Dist.parse(r.get("toGreen"), pct),
                )
            }.toMap()

        private fun parseChestClears(o: JsonObject, pct: DoubleArray): Map<String, ChestClear> =
            (o.obj("rooms") ?: return emptyMap()).entrySet().mapNotNull { (name, v) ->
                val r = v.asObj() ?: return@mapNotNull null
                val c = r.arr("cell").doubles()
                val key = name.replace("\u200b", "")
                key to ChestClear(key, r.str("secret"), if (c.size == 3) Pos(c[0].toInt(), c[1].toInt(), c[2].toInt()) else null, Dist.parse(r.get("afterOpenTicks"), pct), r.int("visitsWithWhite") ?: 0)
            }.toMap()

        private fun parseDoors(o: JsonObject, pct: DoubleArray): DoorRules {
            val vol = o.obj("volume"); val fall = o.obj("fall"); val ent = o.obj("entrance"); val pick = o.obj("pickup"); val stand = o.obj("stand")
            val nk = o.obj("noKeyMessage"); val snd = nk?.obj("sound"); val blood = o.obj("blood")
            return DoorRules(
                vol?.int("w") ?: 3, vol?.int("h") ?: 4, vol?.int("d") ?: 3, vol?.int("y0") ?: 69,
                fall?.int("barrierTicks") ?: 14, fall?.arr("barrierTicksQ").doubles(),
                ent?.int("startLeadTicks"), Dist.parse(ent?.get("fallAfterStart1Ticks"), pct), ent?.int("freeFairyDoorWithinTicks") ?: 2,
                intMap(o.obj("witherPerLayout")?.obj("counts")), intMap(o.obj("paidKeysPerRun")?.obj("counts")),
                Dist.parse(o.get("keyObtainToUseTicks"), pct), pick?.dbl("instantFrac") ?: 0.63, Dist.parse(pick?.get("delayedTicks"), pct), pick?.int("autoCollectTicks") ?: 200,
                stand?.dbl("witherY") ?: 68.531, stand?.dbl("bloodY") ?: 67.531, stand?.str("nameWither") ?: "§8Wither Key", stand?.str("nameBlood") ?: "§cBlood Key",
                Dist.parse(stand?.get("lifetimeTicks"), pct),
                nk?.str("chat") ?: "You do not have the key for this door!", snd?.str("id") ?: "entity.enderman.teleport", snd?.dbl("vol") ?: 8.0, snd?.dbl("pitch") ?: 0.0,
                Dist.parse(blood?.get("keyAfterStartTicks"), pct), Dist.parse(blood?.get("doorAfterKeyTicks"), pct), o,
            )
        }

        private fun parseSplits(o: JsonObject, pct: DoubleArray) = Splits(dists(o, pct), o)

        private fun parseCheckStates(o: JsonObject, pct: DoubleArray): CheckStates {
            val types = o.obj("types")?.entrySet()?.mapNotNull { (k, v) ->
                val t = v.asObj() ?: return@mapNotNull null
                k to TypeStates(intMapStr(t.obj("paths")), dists(t.obj("ticks"), pct))
            }?.toMap().orEmpty()
            return CheckStates(types, o.bool("greenNeedsAllSecrets") ?: true, o.bool("zeroSecretGoesStraightToGreen") ?: true, o)
        }

        private fun intMapStr(o: JsonObject?): Map<String, Int> = o?.entrySet()?.mapNotNull { (k, v) -> v.asIntOrNull()?.let { k to it } }?.toMap().orEmpty()

        private fun parseScore(o: JsonObject): ScoreModel {
            val terms = o.obj("terms")
            val secret = terms?.obj("secret"); val rooms = terms?.obj("rooms"); val bonus = terms?.obj("bonus"); val speed = terms?.obj("speed")
            return ScoreModel(
                o.int("periodTicks") ?: 200, o.int("firstUpdateTick") ?: 200, o.str("endScreen"),
                secret?.dbl("maxSecretPoints") ?: 40.0, secret?.int("secretsPctDecimals") ?: 1,
                rooms?.arr("table").doubles(), rooms?.str("interp"), rooms?.str("rounding"),
                terms?.obj("deaths")?.str("perDeathFormula"), terms?.obj("puzzles")?.dbl("perUnsolved"),
                bonus?.obj("crypts")?.dbl("w") ?: 1.0, bonus?.obj("crypts")?.int("cap") ?: 5,
                bonus?.obj("mimic")?.dbl("w") ?: 2.0, bonus?.obj("prince")?.dbl("w") ?: 1.0, bonus?.obj("bat")?.dbl("w") ?: 1.0,
                bonus?.bool("weightsFitted") ?: false,
                speed?.arr("pointsSec")?.mapNotNull { p -> val a = p.asArr(); if (a != null && a.size() >= 2) (a[0].asDoubleOrNull() ?: return@mapNotNull null) to (a[1].asDoubleOrNull() ?: return@mapNotNull null) else null }.orEmpty(),
                speed?.bool("fitted") ?: false, terms?.dbl("constant") ?: 0.0, o,
                secret?.dbl("weight") ?: 1.0, o.int("clockOriginTicksAfterT0") ?: 0, o.str("evaluation"),
            )
        }

        private fun parseClassRoles(o: JsonObject, pct: DoubleArray): ClassRoles {
            val classes = HashMap<String, ClassRole>()
            for (c in listOf("HEALER", "MAGE", "BERSERK", "ARCHER", "TANK")) {
                val r = o.obj(c) ?: continue
                classes[c] = ClassRole(r.dbl("roomsOwnedMean") ?: 0.0, r.dbl("puzzlesOwnedMean") ?: 0.0, r.dbl("doorOpenerFrac") ?: 0.0,
                    Dist.parse(r.get("distanceBlocksQ"), pct), r.dbl("movingSpeedBpt"))
            }
            return ClassRoles(o.int("n") ?: 0, classes, o.arr("ownedRoomsSortedMean").doubles(), o.dbl("standingFrac"), o.dbl("firstEntrantTopLeaderFrac"), o)
        }

        private fun parsePuzzles(o: JsonObject): PuzzleOdds {
            val odds = o.obj("countOdds")
            return PuzzleOdds(odds?.int("n") ?: 0, intMap(odds?.obj("counts")),
                o.arr("pool").objects().mapNotNull { p ->
                    PuzzleOdds.PuzzleEntry(p.str("code") ?: return@mapNotNull null, p.arr("rooms").strings(), p.dbl("mapShare") ?: 0.0, p.bool("exclusivePair") ?: false)
                }, o)
        }

        private fun parseLag(o: JsonObject): LagModel {
            val tiers = o.obj("tiers")?.entrySet()?.mapNotNull { (k, v) ->
                val t = v.asObj() ?: return@mapNotNull null
                k to LagTier(t.dbl("good") ?: 0.0, t.dbl("typical") ?: 0.0, t.dbl("bad") ?: 0.0, t.dbl("mean"), t.dbl("slotOver200"), t.dbl("slotMultiplier"))
            }?.toMap().orEmpty()
            val run = o.obj("runLevelModel"); val fr = o.obj("freezes"); val tick = fr?.obj("tickIntervalMsQ"); val catchup = fr?.obj("catchup")
            return LagModel(
                o.dbl("typicalLossPerMin") ?: 30.3, tiers, run?.dbl("median"), run?.dbl("sigma"), run?.dbl("p10"), run?.dbl("p90"),
                fr?.arr("exceedPerHour").objects().mapNotNull { e -> (e.dbl("gapMs") ?: return@mapNotNull null) to (e.dbl("perHour") ?: return@mapNotNull null) },
                tick?.arr("pct").doubles(), tick?.arr("q").doubles(), catchup?.dbl("recoverFracWithin5s"), catchup?.dbl("burstRateTicksPerSec"),
                o.obj("rttMs")?.dbl("median"), o,
            )
        }
    }
}
