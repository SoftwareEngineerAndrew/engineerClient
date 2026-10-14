package com.devgineerclient.bossrecorder

import com.devgineerclient.DevgineerClient
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.SharedConstants
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One world's boss recording, gzipped JSON lines. Nothing is written until the first
 * time the recording is in focus - the Watcher's camp or the boss - so a world without a boss fight
 * leaves no file. From then on: the boss log, chat and your own movement, until the world unloads.
 *
 * The game thread builds the lines; a background thread writes them.
 */
class BossRecording(private val dir: Path) {

    private var tick = 0
    private var open = false
    private val io = Executors.newSingleThreadExecutor { Thread(it, "dc-bossrecorder-writer").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var tempFile: Path? = null
    private var linesWritten = 0L
    private val startedAt = LocalDateTime.now()
    private var floorName: String? = null
    private var partyKey = ""

    /** Server ticks: counted by [BossLog] on the network thread, one a ping. */
    @Volatile var serverTicks = 0

    /** Every wither the server has added and not removed: its packets are kept even out of focus. */
    val bossIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * Whether every entity's packets are kept, not only the withers': in the boss, and from the blood
     * door opening until the Watcher is done (the camp starts before anyone is in the Blood room).
     */
    @Volatile var focus = false
        private set
    private var bloodOpen = false
    private var watcherDone = false

    private val netLog = StringBuilder()
    private val palette = HashMap<BlockState, Int>()

    // ------------------------------------------------------------------ inputs (game thread)

    fun onTick() {
        flushNet()
        tick++
        focus = DungeonUtils.inBoss ||
            (BossRecorder.watcherCamp && (DungeonUtils.currentRoomName == "Blood" || (bloodOpen && !watcherDone)))
        if (focus && !open) openFile()
        if (!open) return
        if (tick % 20 == 0) emit("""{"k":"time","t":$tick,"ms":${System.currentTimeMillis()}}""")
        recordFloorAndParty()
    }

    /** One boss-log entry: [n] the server tick it arrived on, [entry] the rest of the tuple. */
    fun net(n: Int, entry: String) {
        if (netLog.isNotEmpty()) netLog.append(',')
        netLog.append('[').append(n).append(',').append(entry).append(']')
    }

    fun netBlock(n: Int, pos: BlockPos, state: BlockState) = net(n, "\"b\",${pos.x},${pos.y},${pos.z},${paletteIndex(state)}")

    fun onChat(message: String, n: Int) {
        if (message.startsWith("The BLOOD DOOR has been opened!")) bloodOpen = true
        if (message.startsWith("[BOSS] The Watcher: You have proven yourself")) watcherDone = true
        emit("""{"k":"chat","t":$tick,"n":$n,"m":${js(message)}}""")
    }

    fun finish() {
        // Once: the writer is shut down after it, and a second call would queue onto a dead thread.
        if (io.isShutdown) return
        if (!open) { io.shutdown(); return }
        flushNet()
        emit("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        val w = writer
        val temp = tempFile
        writer = null
        // The file never opened (said so in chat then): nothing to close or name.
        if (w == null || temp == null) { io.shutdown(); return }
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        io.execute {
            try {
                w.close()
                val target = temp.resolveSibling(finalName)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                val mb = Files.size(target) / 1_000_000.0
                if (BossRecorder.savedMessage) DevgineerClient.msg("§7Boss Recorder: saved §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines)")
            } catch (t: Throwable) {
                DevgineerClient.logger.error("[dc] boss recorder: failed to finish file", t)
            }
        }
        io.shutdown()
    }

    /** Waits up to [ms] for the file [finish] queued to be closed and named: the game is closing. */
    fun awaitFinished(ms: Long) {
        runCatching { io.awaitTermination(ms, TimeUnit.MILLISECONDS) }
    }

    // ------------------------------------------------------------------ internals

    private fun openFile() {
        // Once, even when it fails: a file that could not be made is not tried again every tick.
        open = true
        val temp = dir.resolve("bosses-${startedAt.format(STAMP)}.jsonl.gz.part")
        try {
            Files.createDirectories(dir)
            val out = Files.newOutputStream(temp)
            writer = try {
                BufferedWriter(OutputStreamWriter(GZIPOutputStream(out), Charsets.UTF_8), 1 shl 16)
            } catch (t: Throwable) { runCatching { out.close() }; throw t }
        } catch (t: Throwable) {
            // Said, not silent: without a file nothing of this world is kept.
            DevgineerClient.logger.error("[dc] boss recorder: could not open file", t)
            DevgineerClient.msg("§cBoss Recorder: could not create the recording file (${t.javaClass.simpleName}), so this world is not recorded.")
            runCatching { Files.deleteIfExists(temp) }
            return
        }
        tempFile = temp
        val player = DevgineerClient.mc.player
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("devgineerclient")
            .map { it.metadata.version.friendlyString }.orElse("?")
        val mcVersion = runCatching { SharedConstants.getCurrentVersion().name() }.getOrDefault("?")
        emit("""{"k":"meta","format":"bosses-1","mod":${js(version)},"mc":${js(mcVersion)},"self":${js(player?.name?.string ?: "?")},"selfId":${player?.id ?: -1},"startMs":${System.currentTimeMillis()},"t":$tick,"n":$serverTicks}""")
        if (BossRecorder.startMessage) DevgineerClient.msg("§7Boss Recorder: recording")
    }

    private fun flushNet() {
        if (netLog.isEmpty()) return
        emit("""{"k":"net","t":$tick,"ms":${System.currentTimeMillis()},"d":[$netLog]}""")
        netLog.setLength(0)
    }

    private fun recordFloorAndParty() {
        val floor = DungeonUtils.floor?.name
        if (floor != null && floor != floorName) {
            floorName = floor
            emit("""{"k":"floor","t":$tick,"floor":${js(floor)}}""")
        }
        val party = DungeonUtils.dungeonTeammates
        val key = party.joinToString("|") { "${it.name}:${it.clazz.name}" }
        if (key != partyKey && party.isNotEmpty()) {
            partyKey = key
            emit("""{"k":"party","t":$tick,"m":[${party.joinToString(",") { "[${js(it.name)},${js(it.clazz.name)}]" }}]}""")
        }
    }

    private fun paletteIndex(state: BlockState): Int = palette.getOrPut(state) {
        val i = palette.size
        emit("""{"k":"pal","i":$i,"s":${js(BlockStateParser.serialize(state))}}""")
        i
    }

    /** Lines before the file opens are dropped: only what comes after the first focus is kept. */
    private fun emit(line: String) {
        val w = writer ?: return
        linesWritten++
        io.execute {
            try { w.write(line); w.newLine() } catch (t: Throwable) { DevgineerClient.logger.error("[dc] boss recorder write failed", t) }
        }
    }

    private fun js(s: String) = JsonPrimitive(s).toString()

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        private val PART_NAME = Regex("""^bosses-(.+)\.jsonl\.gz\.part$""")

        /**
         * The recordings an earlier game never finished (a crash): list them before this game opens one.
         * Only those untouched for half an hour: another game in this folder may still be writing one
         * (one still being written is touched every few seconds). A part skipped now is kept for a later launch.
         */
        fun leftovers(dir: Path): List<Path> {
            if (!Files.isDirectory(dir)) return emptyList()
            val now = System.currentTimeMillis()
            return Files.list(dir).use { s ->
                s.filter { PART_NAME.matches(it.fileName.toString()) && now - Files.getLastModifiedTime(it).toMillis() > 30 * 60_000L }.toList()
            }
        }

        /** An unfinished recording: its whole lines into a file named as a finished one would be, then the .part goes. */
        fun salvage(part: Path) {
            val stamp = PART_NAME.find(part.fileName.toString())?.groupValues?.get(1) ?: return
            val temp = part.resolveSibling("salvage-$stamp.tmp")
            var lines = 0
            var floor = "unknown"
            var meta = false
            BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16).use { w ->
                try {
                    BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(part)), Charsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (lines == 0) meta = line.startsWith("{\"k\":\"meta\"")
                            if (line.startsWith("{\"k\":\"floor\"")) runCatching { floor = JsonParser.parseString(line).asJsonObject["floor"].asString }
                            w.write(line); w.newLine(); lines++
                        }
                    }
                } catch (_: java.io.IOException) {
                    // (where the game stopped writing it)
                }
            }
            if (!meta) { Files.deleteIfExists(temp); Files.deleteIfExists(part); return }
            val target = part.resolveSibling("${stamp}_${floor.replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz")
            // Already there: an earlier salvage moved it and stopped before deleting the part.
            if (Files.exists(target)) { Files.deleteIfExists(temp); Files.deleteIfExists(part); return }
            Files.move(temp, target)
            Files.deleteIfExists(part)
            DevgineerClient.logger.info("[dc] boss recorder: saved $lines lines of an unfinished recording as ${target.fileName}")
        }
    }
}
