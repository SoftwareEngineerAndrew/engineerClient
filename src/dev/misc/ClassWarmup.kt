package com.devgineerclient.misc

import com.devgineerclient.DevgineerClient
import net.fabricmc.loader.api.FabricLoader
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.management.ObjectName

/**
 * Loads, on a background thread, the classes the last launch first needed between this client's start and a few
 * seconds into Skyblock, so the game thread finds them ready: loading a class means reading it from its jar and
 * applying every mod's mixins to it, which is most of each stall on the way in. Classes are only loaded, never
 * initialised, so no code of theirs runs early.
 *
 * Every launch also records the list for the next one: the JVM's class-load log is switched on here and off a
 * few seconds after Skyblock is drawn. A class the warmup couldn't load is left out of the next list.
 */
object ClassWarmup {
    private const val STOP_AFTER_MS = 10_000L
    /** Fewer than this: the launch never got far (or the log failed), so the old list is kept. */
    private const val MIN_CLASSES = 1000

    private val listFile: Path = FabricLoader.getInstance().configDir.resolve("devgineerclient-warmup.txt")
    private val missing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var logFile: Path? = null

    fun install() {
        if (!FastLaunch.classWarmup) return
        val names = runCatching { Files.readAllLines(listFile) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (names.isNotEmpty()) Thread({ warm(names) }, "dc-warmup").apply { isDaemon = true; start() }
        // A temp file: the class-load log's file name can't hold spaces, and instance paths can.
        val log = Files.createTempFile("dc-warmup", ".log")
        vmLog("output=file=$log", "what=class+load=info", "decorators=none")
        logFile = log
    }

    private fun warm(names: List<String>) {
        val loader = ClassWarmup::class.java.classLoader
        val t0 = System.nanoTime()
        var n = 0
        for (name in names) {
            try { Class.forName(name, false, loader); n++ } catch (_: Throwable) { missing += name }
        }
        DevgineerClient.logger.info("[dc] class warmup: {} classes in {} ms, {} missing", n, (System.nanoTime() - t0) / 1_000_000, missing.size)
    }

    /** LaunchTimeline, once Skyblock is drawn (or the launch gave up): a few more seconds, then the next list is saved. */
    fun finish() {
        val log = logFile ?: return
        logFile = null
        Thread({ Thread.sleep(STOP_AFTER_MS); save(log) }, "dc-warmup-save").apply { isDaemon = true; start() }
    }

    private fun save(log: Path) = DevgineerClient.safely("class warmup save") {
        try {
            vmLog("output=file=$log", "what=all=off")
            val names = LinkedHashSet<String>()
            Files.readAllLines(log).forEach { line ->
                val name = line.substringBefore(' ')
                // Hidden classes (lambdas, method handles) have a '/' and can't be loaded by name.
                if (name.isNotEmpty() && '/' !in name && name !in missing) names += name
            }
            if (names.size < MIN_CLASSES) return@safely
            val tmp = listFile.resolveSibling(listFile.fileName.toString() + ".tmp")
            Files.write(tmp, names)
            Files.move(tmp, listFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            DevgineerClient.logger.info("[dc] class warmup: {} classes saved for the next launch", names.size)
        } finally {
            Files.deleteIfExists(log)
        }
    }

    /** The JVM's own log settings (jcmd VM.log), changed from inside. */
    private fun vmLog(vararg args: String) {
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"), "vmLog",
            arrayOf<Any>(arrayOf(*args)), arrayOf(Array<String>::class.java.name),
        )
    }
}
