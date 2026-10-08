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
 * line a server tick: you (position, look, open window), the section, what's left in it, every bot
 * (where, heading where, holding, its jobs) and the party's early-enter state; plus every chat line
 * the sim sent and the bots' debug lines as events. The last 20 runs are kept. Server thread only.
 */
object Recorder {
    private const val KEEP = 20
    private var out: BufferedWriter? = null
    private var file: File? = null
    private val events = ArrayList<String>()

    val dir: File get() = File(Minecraft.getInstance().gameDirectory, "config/engineerclient/p3sim-runs")

    fun begin(what: String) {
        finish()
        if (!P3Sim.record) return
        runCatching {
            dir.mkdirs()
            dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.dropLast(KEEP - 1)?.forEach { it.delete() }
            val f = File(dir, "run-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.jsonl")
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
