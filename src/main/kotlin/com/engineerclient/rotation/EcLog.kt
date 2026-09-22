package com.engineerclient.rotation

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * EC's own log: one file per game session under `logs/engineerclient/`, meant to be sent as-is when a run
 * goes wrong. It is self-contained on purpose — every chat line the mod looked at is in it RAW, in
 * order, next to every decision the engine made from it, so a run can be replayed through the
 * engine offline and the first wrong decision found without a clip.
 *
 * One event per line: `HH:mm:ss.SSS <TAG> <payload>`, tab-separated.
 *
 *   SESSION  mod/spec/Odin versions, who I am, my role
 *   SETUP    the setup check, one line per item
 *   CHAT     a raw chat line, exactly as received (only while in a dungeon)
 *   SECTION  phase-3 section transitions
 *   ENGINE   the rotation engine's decision trail
 *   LEAP     leap cue: target chosen, became ready, and by which signal
 *   MASK     invincibility procs, mine and the party's
 *   ROLE     starting-role announcements heard
 *   MARK     a note typed by the player with /brw log mark
 *   WARN     anything the mod could not make sense of
 *
 * Free of Minecraft so the engine can write to it; with no file open every call is a no-op.
 */
object EcLog {

    private const val KEEP = 15
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var out: PrintWriter? = null

    var path: Path? = null
        private set

    /** Open a fresh session file under [gameDir]/logs/brw, keeping only the last [KEEP]. */
    @Synchronized
    fun open(gameDir: Path, header: List<String>) {
        close()
        try {
            val dir = gameDir.resolve("logs").resolve("engineerclient")
            Files.createDirectories(dir)
            val file = dir.resolve("ec-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log")
            out = PrintWriter(Files.newBufferedWriter(file), true)
            path = file
            header.forEach { log("SESSION", it) }
            prune(dir)
        } catch (t: Throwable) {
            out = null
            path = null
        }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val w = out ?: return
        w.println(LocalTime.now().format(stamp) + "\t" + tag + "\t" + message.replace('\n', ' '))
    }

    @Synchronized
    fun close() {
        out?.flush()
        out?.close()
        out = null
    }

    private fun prune(dir: Path) {
        try {
            Files.list(dir).use { s ->
                s.filter { it.fileName.toString().startsWith("ec-") && it.fileName.toString().endsWith(".log") }
                    .sorted { a, b -> b.fileName.toString().compareTo(a.fileName.toString()) }
                    .skip(KEEP.toLong())
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (_: Throwable) {
        }
    }
}
