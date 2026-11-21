package com.engineerclient.skyblock

import com.engineerclient.EngineerClient
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** Today's runs read back from ADR's own recordings (config/engineerclient/adr/runs), for /runslog ... today. */
object AdrDayRuns {
    private val dir: Path get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("adr").resolve("runs")

    private val SCORE = Regex("""^\s*Team Score: (\d+) \((.{1,2})\)""")
    private val DEFEATED = Regex("""^\s*☠ Defeated .+ in (?:(\d+)h )?(?:(\d+)m )?(\d+)s( \(NEW RECORD!\))?$""")
    private val SECRETS = Regex("""^\s*Secrets Found: (\d+)""")
    private val DIED = Regex("""^ ☠ .+ (?:died|was killed)""")

    /** Parsed files by name and size, so a recording still being written is read again. */
    private val cache = ConcurrentHashMap<String, Pair<Long, RunsLogger.Run?>>()

    /** Today's finished runs, oldest first. Reads the files; call off the game thread. */
    fun today(): List<RunsLogger.Run> {
        if (!Files.isDirectory(dir)) return emptyList()
        val day = LocalDate.now().toString()
        val files = Files.list(dir).use { s -> s.filter { val n = it.fileName.toString(); n.startsWith(day) && n.endsWith(".jsonl.gz") }.sorted().toList() }
        return files.mapNotNull { f ->
            val size = Files.size(f)
            val name = f.fileName.toString()
            val c = cache[name]
            if (c != null && c.first == size) c.second else read(f, day).also { cache[name] = size to it }
        }
    }

    private fun read(file: Path, day: String): RunsLogger.Run? = runCatching {
        val floor = file.fileName.toString().removeSuffix(".jsonl.gz").substringAfterLast('_')
        var start = 0L
        var score = -1
        var rank = ""
        var seconds = 0
        var pb = false
        var secrets = 0
        var deaths = 0
        var party = emptyList<String>()
        GZIPInputStream(Files.newInputStream(file)).bufferedReader().useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("{\"k\":\"chat\"") -> {
                        val m = JsonParser.parseString(line).asJsonObject["m"]?.asString ?: continue
                        SCORE.find(m)?.let { if (score < 0) { score = it.groupValues[1].toInt(); rank = it.groupValues[2] } }
                        DEFEATED.find(m)?.let { r ->
                            val (h, min, s) = (1..3).map { r.groupValues[it].toIntOrNull() ?: 0 }
                            seconds = h * 3600 + min * 60 + s; pb = r.groupValues[4].isNotEmpty()
                        }
                        SECRETS.find(m)?.let { secrets = it.groupValues[1].toInt() }
                        if (score < 0 && DIED.containsMatchIn(m)) deaths++
                    }
                    line.startsWith("{\"k\":\"party\"") -> {
                        val names = JsonParser.parseString(line).asJsonObject.getAsJsonArray("m")?.mapNotNull { it.asJsonArray.firstOrNull()?.asString }.orEmpty()
                        if (names.size >= party.size) party = names
                    }
                    line.startsWith("{\"k\":\"meta\"") -> start = JsonParser.parseString(line).asJsonObject["startMs"]?.asLong ?: 0L
                }
            }
        }
        if (score < 0) null
        else RunsLogger.Run(date = day, at = start, floor = floor, seconds = seconds, score = score, rank = rank, deaths = deaths, secrets = secrets, pb = pb, party = party)
    }.getOrElse {
        EngineerClient.logger.warn("[ec] runslog: couldn't read ${file.fileName}", it)
        null
    }
}
