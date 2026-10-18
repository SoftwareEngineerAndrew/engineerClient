package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.SimNet
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The wire form of the run's stage, server to client (SimNet kind [KIND]). Pure; tested. */
object RunWire {
    const val KIND = "runstage"

    /** A decoded stage message. `stage` null = no run (the boss-only practice, or the run ended). */
    data class Msg(val runId: String?, val floor: String?, val stage: RunStage?)

    fun encode(runId: String?, floor: String?, stage: RunStage?): String = JsonObject().also {
        runId?.let { v -> it.addProperty("run", v) }
        floor?.let { v -> it.addProperty("floor", v) }
        stage?.let { v -> it.addProperty("stage", v.name) }
    }.toString()

    fun decode(text: String): Msg? = try {
        val o = JsonParser.parseString(text).asJsonObject
        Msg(o.get("run")?.takeIf { it.isJsonPrimitive }?.asString, o.get("floor")?.takeIf { it.isJsonPrimitive }?.asString,
            RunStage.entries.firstOrNull { e -> e.name == o.get("stage")?.takeIf { it.isJsonPrimitive }?.asString })
    } catch (e: Exception) { null }

    /**
     * How the server sends a message to every game in the sim. Installed by [AdsBootstrap] (`SimNet.sendAll`); a no-op until
     * then, so headless tests never touch Minecraft.
     */
    @Volatile var sender: (kind: String, data: String) -> Unit = { _, _ -> }

    /** Server side: tells every game in the sim the run's stage (or that there is no run). */
    fun broadcast(run: Run?) {
        sender(KIND, encode(run?.id, run?.config?.floor, if (run == null || run.isOver) null else run.stage))
    }
}

/**
 * What the CLIENT thread knows about the run: only the stage, which is what Odin's bridge needs (`P3Sim.bridge()` forces "in
 * boss" only when [stage] is `BOSS_ENTRY` or `BOSS`, or when there is no run at all, i.e. today's boss practice). Everything
 * else about the run is drawn by the server (sidebar, tab, map, chat) and arrives as the real packets. Volatile fields,
 * written on the client thread from the "runstage" message.
 */
object ClientRun {
    @Volatile var runId: String? = null
        private set
    @Volatile var floor: String? = null
        private set
    @Volatile var stage: RunStage? = null
        private set

    /** A full run is on (the clear surfaces are the server's, and Odin must scan the dungeon). */
    val inRun: Boolean get() = stage != null

    /**
     * True when Odin should believe it is in the boss: no run at all (today's boss practice) or the run is in its boss stages. At [RunStage.END]
     * it stays as it was in the stage before (the table in ARCHITECTURE 3: END is "unchanged"), so a run that ended in the boss arena keeps
     * Odin's boss features for the end-of-run lines.
     */
    val bridgeInBoss: Boolean get() = when (val s = stage) { null -> true; RunStage.END -> lastInBoss; else -> s.inBoss }

    @Volatile private var lastInBoss = true

    /**
     * The bridge leaves Odin to derive the dungeon itself: a run is on and it is not in the boss, which covers the clear stages and the END of a run
     * that ended in the clear (stopped, aborted). Boss mode is forced only for BOSS_ENTRY, BOSS, END after the boss, and no run at all.
     */
    val bridgeAsClear: Boolean get() = inRun && !bridgeInBoss

    /** The run is in its clear (COUNTDOWN, CLEAR, BLOOD): Odin derives the dungeon, the party, the map and the rooms itself from what the server sends. */
    val inClear: Boolean get() = stage?.inDungeon == true

    fun register() {
        SimNet.onClient(RunWire.KIND) { data -> apply(RunWire.decode(data)) }
    }

    internal fun apply(m: RunWire.Msg?) {
        runId = m?.runId; floor = m?.floor; stage = m?.stage
        m?.stage?.takeIf { it != RunStage.END }?.let { lastInBoss = it.inBoss }
    }

    /** The world was left: forget the run. */
    fun reset() = apply(null)
}
