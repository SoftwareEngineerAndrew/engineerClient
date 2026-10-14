package com.engineerclient.debug

import com.engineerclient.EngineerClient
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryUtil
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/ec memreport`: who holds LWJGL's native memory - Minecraft's MemoryUtil, backed here by LWJGL's
 * jemalloc, which rendering, fonts, textures and LWJGL's bundled C libraries (NanoVG, stb) all use.
 * Every allocation still live, grouped by the code that made it, largest total first, written to
 * `logs/engineerclient/memreport-<stamp>.txt`; the top few are said in chat.
 *
 * LWJGL only records allocations when the game was started with
 * `-Dorg.lwjgl.util.DebugAllocator=true` (add `-Dorg.lwjgl.util.DebugAllocator.internal=true` to
 * include LWJGL's own and its C libraries'). That records a stack trace per allocation and costs
 * frame time, so it is for a diagnostic session only.
 */
object MemReport {

    private const val FRAMES = 14

    /** Writes the report off the game thread; [say] gets the result lines. */
    fun write(say: (String) -> Unit) {
        if (!Configuration.DEBUG_MEMORY_ALLOCATOR.get(false)) {
            say("§cnot tracking native memory: start the game with §f-Dorg.lwjgl.util.DebugAllocator=true§c (and §f-Dorg.lwjgl.util.DebugAllocator.internal=true§c)")
            return
        }
        say("§7collecting LWJGL's live allocations...")
        Thread({
            EngineerClient.safely("memreport") {
                val sites = HashMap<String, LongArray>()     // call site -> [bytes, count]
                val threads = HashMap<String, Long>()
                var total = 0L
                var count = 0L
                MemoryUtil.memReport { _, memory, _, threadName, stacktrace ->
                    total += memory; count++
                    threads.merge(threadName ?: "?", memory, Long::plus)
                    val frames = stacktrace.orEmpty().dropWhile { it.className.startsWith("org.lwjgl.") }.take(FRAMES)
                    val key = if (frames.isEmpty()) "(no stack trace)" else frames.joinToString("\n") { "    at $it" }
                    val a = sites.getOrPut(key) { LongArray(2) }
                    a[0] += memory; a[1]++
                }
                val ranked = sites.entries.sortedByDescending { it.value[0] }
                val anon = runCatching {
                    Files.readAllLines(java.nio.file.Path.of("/proc/self/status")).firstOrNull { it.startsWith("RssAnon") }?.trim()
                }.getOrNull()
                val rt = Runtime.getRuntime()
                val out = StringBuilder()
                out.append("LWJGL native memory still allocated: ${mb(total)} in $count allocations, ${sites.size} call sites\n")
                out.append("process ${anon ?: "RssAnon ?"}; Java heap used ${mb(rt.totalMemory() - rt.freeMemory())} of ${mb(rt.maxMemory())}\n")
                out.append("by thread: " + threads.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${mb(it.value)}" } + "\n\n")
                for ((stack, a) in ranked) {
                    out.append("${mb(a[0])} in ${a[1]} allocations\n").append(stack).append("\n\n")
                }
                val dir = EngineerClient.mc.gameDirectory.toPath().resolve("logs").resolve("engineerclient")
                Files.createDirectories(dir)
                val file = dir.resolve("memreport-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
                Files.writeString(file, out)
                say("§7LWJGL native memory: §f${mb(total)}§7 in $count allocations. Top call sites:")
                for ((stack, a) in ranked.take(3)) {
                    val top = stack.lineSequence().firstOrNull()?.trim()?.removePrefix("at ") ?: "?"
                    say("§f ${mb(a[0])} §7(${a[1]}) §8$top")
                }
                say("§7full report: §f$file")
            }
        }, "engineerclient-memreport").apply { isDaemon = true }.start()
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
}
