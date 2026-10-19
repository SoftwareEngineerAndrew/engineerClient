package com.engineerclient.p3sim.run

import com.google.gson.JsonObject

/** Where the layout of a run comes from (FORMAT-layouts 11.2): any playable recorded layout, a seed, or a chosen recorded run. */
sealed interface LayoutSource {
    /** A random layout from the default pool (a fresh seed is drawn and shown, so the run can be repeated). */
    data object Random : LayoutSource
    /** The layout `LayoutPack.pick(seed)` gives: the same seed and pack always give the same layout. */
    data class Seed(val seed: Long) : LayoutSource
    /** A recorded run's layout by id (the run file name without extension). A layout that is not `playable` warns. */
    data class Recorded(val id: String) : LayoutSource
    /**
     * Practice: one room alone in the middle of the map (its in-game name, "Blue Skulls"), as a recorded run had it (its build, turn
     * and puzzle state), entered through the doorway the party came in by, with bedrock behind you and every other side a wall.
     */
    data class Practice(val room: String) : LayoutSource
}

/**
 * The lag presets (FORMAT-runstate 12): net ticks lost per minute. The numbers here are the measured defaults; the
 * run-state pack's `lag.tiers` overrides them when it is loaded. [OFF] is no lag layer at all.
 */
enum class LagPreset(val label: String, val lossPerMinute: Double) {
    OFF("Off", 0.0), GOOD("Good", 14.5), TYPICAL("Typical", 30.3), BAD("Bad", 56.6),
    /** An instance slot above 200: 1.8 times the typical loss. */
    HIGH_SLOT("High slot", 54.6);
}

/**
 * Everything the player chooses before a run (the start screen, or `/ads run ...`). Plain data: it travels from the
 * client to the server as JSON ([toJson]/[fromJson]).
 */
data class RunConfig(
    /** `F7`. (`M7` values exist in the tables but nothing M7-specific is built.) */
    val floor: String = "F7",
    val layout: LayoutSource = LayoutSource.Random,
    /** The run's own random seed (mob rolls, builds, bot timing, HP scale). Always set; drawn at random when the player gave none. */
    val seed: Long = System.nanoTime(),
    /** Teammate bots on or off. Off: you clear alone and the party list is just you. */
    val bots: Boolean = true,
    /** Party size including you, 1..5. */
    val partySize: Int = 5,
    val lag: LagPreset = LagPreset.OFF,
    /** The player cannot die in the clear (the ledger is clamped at 1 hp). */
    val noDeaths: Boolean = false,
    /** Mob HP multiplier; null = draw from the pack's per-run table (FORMAT-mobs 4); 1.0 = base HP. */
    val mobHpScale: Double? = 1.0,
    /** A SkyBlock stat preset id from the run-state pack (`dev-p50`...), or null for the default. */
    val statsPreset: String? = null,
    /** A party strategy id (see the plug-in seam); null or "neutral" = the built-in neutral default. */
    val strategy: String? = null,
) {
    fun toJson(): JsonObject = JsonObject().also { o ->
        o.addProperty("floor", floor)
        when (layout) {
            LayoutSource.Random -> o.addProperty("layout", "random")
            is LayoutSource.Seed -> { o.addProperty("layout", "seed"); o.addProperty("layoutSeed", layout.seed) }
            is LayoutSource.Recorded -> { o.addProperty("layout", "recorded"); o.addProperty("layoutId", layout.id) }
            is LayoutSource.Practice -> { o.addProperty("layout", "practice"); o.addProperty("practiceRoom", layout.room) }
        }
        o.addProperty("seed", seed)
        o.addProperty("bots", bots)
        o.addProperty("partySize", partySize)
        o.addProperty("lag", lag.name)
        o.addProperty("noDeaths", noDeaths)
        // null is written (not left out): it means "draw from the pack", while a missing field means "the default".
        if (mobHpScale != null) o.addProperty("mobHpScale", mobHpScale) else o.add("mobHpScale", com.google.gson.JsonNull.INSTANCE)
        statsPreset?.let { o.addProperty("statsPreset", it) }
        strategy?.let { o.addProperty("strategy", it) }
    }

    companion object {
        /** Reads what [toJson] wrote; unknown or missing fields keep their defaults. */
        fun fromJson(o: JsonObject): RunConfig {
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            val layout = when (str("layout")) {
                "seed" -> o.get("layoutSeed")?.takeIf { it.isJsonPrimitive }?.asLong?.let { LayoutSource.Seed(it) } ?: LayoutSource.Random
                "recorded" -> str("layoutId")?.let { LayoutSource.Recorded(it) } ?: LayoutSource.Random
                "practice" -> str("practiceRoom")?.let { LayoutSource.Practice(it) } ?: LayoutSource.Random
                else -> LayoutSource.Random
            }
            val d = RunConfig()
            return RunConfig(
                floor = str("floor") ?: d.floor,
                layout = layout,
                seed = o.get("seed")?.takeIf { it.isJsonPrimitive }?.asLong ?: d.seed,
                bots = o.get("bots")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: d.bots,
                partySize = (o.get("partySize")?.takeIf { it.isJsonPrimitive }?.asInt ?: d.partySize).coerceIn(1, 5),
                lag = LagPreset.entries.firstOrNull { it.name == str("lag") } ?: d.lag,
                noDeaths = o.get("noDeaths")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: d.noDeaths,
                mobHpScale = if (o.has("mobHpScale")) o.get("mobHpScale").takeIf { it.isJsonPrimitive }?.asDouble else d.mobHpScale,
                statsPreset = str("statsPreset"),
                strategy = str("strategy"),
            )
        }
    }
}
