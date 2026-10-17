package com.engineerclient.p3sim

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records every sim run (the menu's Record runs) to `config/engineerclient/p3sim-runs/`, one JSON
 * line a server tick: you (position, look, open window, held item), the section, what's left in it, every bot
 * (where, heading where, holding, its jobs) and the party's early-enter state; plus every chat line
 * the sim sent and the bots' debug lines as events. The last 20 runs are kept. Server thread only.
 */
object Recorder {
    /** Runs recorded in a row at most: then it turns itself off (and says so); turning it back on starts the count again. */
    private const val IN_A_ROW = 50
    private var inRow = 0
    private var out: BufferedWriter? = null
    private var file: File? = null
    private val events = ArrayList<String>()

    val dir: File get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-runs")

    fun begin(what: String) {
        finish()
        if (!P3Sim.record) { inRow = 0; return }
        if (inRow >= IN_A_ROW) {
            P3Sim.recordS.value = false
            inRow = 0
            Sim.note("§cRecord Runs is off: $IN_A_ROW runs recorded in a row (config/engineerclient/p3sim-runs). Turn it back on for $IN_A_ROW more.")
            return
        }
        inRow++
        runCatching {
            dir.mkdirs()
            val f = File(dir, "run-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"))}.jsonl")
            file = f
            out = f.bufferedWriter()
            line(JsonObject().apply {
                addProperty("start", what)
                addProperty("class", P3Sim.myClass.name)
                addProperty("skill", P3Plan.preset().name)
                add("mine", JsonArray().apply { P3Plan.mine().forEach { add(it) } })
                addProperty("ping", P3Sim.ping)
                addProperty("speed", P3Sim.speed)
            })
        }
    }

    /** A chat line, debug line or anything else worth seeing in order. */
    fun event(text: String) {
        if (out != null) events += text
    }

    fun tick(serverTick: Int) {
        val w = out ?: return
        runCatching {
            val o = JsonObject()
            o.addProperty("t", serverTick)
            val phase = Fight.phase
            o.addProperty("phase", phase?.name)
            o.addProperty("pt", phase?.t)
            Sim.player?.let { p ->
                o.add("you", JsonArray().apply { add(r(p.x)); add(r(p.y)); add(r(p.z)); add(r(p.yRot.toDouble())); add(r(p.xRot.toDouble())) })
                if (p.containerMenu !== p.inventoryMenu) o.addProperty("window", p.containerMenu.javaClass.simpleName)
                SimItems.idOf(p.mainHandItem)?.let { o.addProperty("held", it) }
            }
            if (phase is GoldorPhase) {
                o.addProperty("n", phase.n)
                o.addProperty("section", phase.section)
                o.add("left", JsonArray().apply { phase.stations.filter { it.section == phase.section && !it.done }.forEach { add(it.id) } })
                Party.record(o)
            }
            if (events.isNotEmpty()) { o.add("ev", JsonArray().apply { events.forEach { add(it) } }); events.clear() }
            w.write(o.toString()); w.newLine()
        }
    }

    fun finish() {
        val w = out ?: return
        out = null
        events.clear()
        runCatching { w.close() }
        file?.let { Sim.note("§7Run recorded: §f${it.name}") }
    }

    private fun line(o: JsonObject) { out?.apply { write(o.toString()); newLine() } }

    fun r(d: Double) = Math.round(d * 100) / 100.0
}
