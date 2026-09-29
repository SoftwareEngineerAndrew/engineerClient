package com.engineerclient.bossrecorder

import com.engineerclient.EngineerClient
import com.google.gson.JsonPrimitive
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * One world's boss recording (format: docs/boss-recorder.md). Nothing is written until the first
 * time the recording is in focus - the Watcher's camp or the boss - so a world without a boss fight
 * leaves no file. From then on: the boss log, chat and your own movement, until the world unloads.
 *
 * The game thread builds the lines; a background thread writes them.
 */
class BossRecording(private val dir: Path) {

    private var tick = 0
    private var open = false
    private val io = Executors.newSingleThreadExecutor { Thread(it, "ec-bossrecorder-writer").apply { isDaemon = true } }
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
        if (!open) { io.shutdown(); return }
        flushNet()
        emit("""{"k":"end","t":$tick,"ms":${System.currentTimeMillis()}}""")
        val temp = tempFile ?: return
        val finalName = "${startedAt.format(STAMP)}_${(floorName ?: "unknown").replace(Regex("[^A-Za-z0-9]+"), "")}.jsonl.gz"
        val lines = linesWritten
        io.execute {
            try {
                writer?.close()
                val target = temp.resolveSibling(finalName)
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                val mb = Files.size(target) / 1_000_000.0
                if (BossRecorder.savedMessage) EngineerClient.msg("§7Boss Recorder: saved §f$finalName §7(${String.format(Locale.ROOT, "%.1f", mb)} MB, $lines lines)")
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] boss recorder: failed to finish file", t)
            }
        }
        io.shutdown()
    }

    // ------------------------------------------------------------------ internals

    private fun openFile() {
        open = true
        Files.createDirectories(dir)
        val temp = dir.resolve("bosses-${startedAt.format(STAMP)}.jsonl.gz.part")
        tempFile = temp
        writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(temp)), Charsets.UTF_8), 1 shl 16)
        val player = EngineerClient.mc.player
        val version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("engineerclient")
            .map { it.metadata.version.friendlyString }.orElse("?")
        emit("""{"k":"meta","format":"bosses-1","mod":${js(version)},"mc":"26.1.2","self":${js(player?.name?.string ?: "?")},"selfId":${player?.id ?: -1},"startMs":${System.currentTimeMillis()},"t":$tick,"n":$serverTicks}""")
        if (BossRecorder.startMessage) EngineerClient.msg("§7Boss Recorder: recording")
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
            try { w.write(line); w.newLine() } catch (t: Throwable) { EngineerClient.logger.error("[ec] boss recorder write failed", t) }
        }
    }

    private fun js(s: String) = JsonPrimitive(s).toString()

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
