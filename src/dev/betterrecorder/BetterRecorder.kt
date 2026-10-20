package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import com.mojang.brigadier.arguments.StringArgumentType
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.network.protocol.Packet
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Better Recorder: everything ADS needs to copy the clear 1:1, in one small file per dungeon.
 *
 * Where the Dungeon Recorder keeps every packet's bytes (gigabytes, read back with a decoder), this keeps what the clear is made of,
 * already decoded and placed: every mob and its gear, skin, name tag and path; every block change, sound, particle, chat, title,
 * action bar, tab, sidebar, boss bar and map change; your position, input, items and every click with what it was on; Odin's rooms,
 * doors and puzzles; and a block snapshot of each room the first time it is seen whole. Positions in rooms also come in the room's own
 * frame (Odin's rotation and corner), so a recording lines up with the ADS room library directly. A run is a few MB, so it can stay
 * on all the time (and in a party, everyone can run it: the files line up by the server's tick count).
 *
 * Probes (`/brec probes`) are the open questions: turn one on, do what it says in a run, and its moments are marked (some add capture).
 * Files: <game dir>/devgineerclient-recordings/better/, gzipped JSON Lines; the format is docs/better-recorder.md.
 */
object BetterRecorder : Module(
    name = "Better Recorder",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Records what ADS needs to copy the clear 1:1 - mobs, blocks, sounds, chat, HUD, your clicks, Odin's rooms, room snapshots - in a few MB a run, with positions in each room's own frame. Probes (/brec probes) are the open questions to play through.",
) {
    private enum class Where(private val label: String) {
        DUNGEONS("Dungeons"), DUNGEONS_AND_ADS("Dungeons + ADS"), EVERYWHERE("Everywhere");
        override fun toString() = label
    }
    private enum class Snaps(private val label: String) {
        OFF("Off"), SPECIAL("Puzzles, traps, blood, fairy"), ALL("Every room");
        override fun toString() = label
    }

    private val where by SelectorSetting("Where", Where.DUNGEONS_AND_ADS, desc = "Hypixel dungeons only, also ADS (the same format, so a sim run compares straight against a real one), or always.")
    private val snaps by SelectorSetting("Room Snapshots", Snaps.ALL, desc = "Every block of a room the first time it is seen with its rotation known (and puzzle and trap rooms again when done). About 20 KB a room.")
    private val hidePrivate by BooleanSetting("Hide Private Chats", true, desc = "Leaves private messages, guild, officer and co-op chat out (a line says one was there).")
    private val announce by BooleanSetting("Announce Sightings", true, desc = "Says in chat when a wanted sighting is caught for the first time (Wandering Soul, Crypt Undead...).")
    private val openFolder by ActionSetting("Open Folder", desc = "Opens the folder the recordings are saved in.") {
        DevgineerClient.safely("better recorder folder") { Files.createDirectories(dir); com.mojang.blaze3d.Blaze3D.openPath(dir) }
    }

    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    /** On by default, existing installs included (once: turning it off sticks). */
    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    val dir get() = DevgineerClient.mc.gameDirectory.toPath().resolve("devgineerclient-recordings").resolve("better")

    @Volatile var session: BrSession? = null
        private set
    @Volatile var clientTick = 0
        private set

    val snapshotMode: Int get() = when (snaps) { Snaps.OFF -> 0; Snaps.SPECIAL -> 1; else -> 2 }

    private val PRIVATE_CHAT = Regex("""^(?:(?:From|To) (?:\[[^\]]+] )?\w{1,16}: |(?:Guild|Officer|Co-op|Friend) > |\w{1,16} whispers to you: |You whisper to \w{1,16}: )""")
    private val COLOR = Regex("§(?:#[0-9a-fA-F]{6}|.)")
    fun privateText(text: String): Boolean = hidePrivate && PRIVATE_CHAT.containsMatchIn(COLOR.replace(text, ""))

    private val counts = HashMap<String, Long>()
    fun lineWritten(kind: String) { counts.merge(kind, 1L, Long::plus) }

    init {
        on<TickEvent.End> { DevgineerClient.safely("better recorder tick") { tick() } }
        on<LevelEvent.Load> { DevgineerClient.safely("better recorder world") { stop("world change") } }
        onReceive<Packet<*>>(priority = Int.MIN_VALUE) { DevgineerClient.safely("better recorder in") { BrNet.inbound(this) } }
        onSend<Packet<*>>(priority = Int.MIN_VALUE) { DevgineerClient.safely("better recorder out") { BrNet.outbound(this) } }
        ClientLifecycleEvents.CLIENT_STOPPING.register { DevgineerClient.safely("better recorder exit") { stop("game closed"); } }
        ClientCommandRegistrationCallback.EVENT.register { d, _ ->
            d.register(literal("brec")
                .executes { DevgineerClient.msg(status()); 1 }
                .then(literal("mark").executes { mark(null); 1 }.then(argument("note", StringArgumentType.greedyString()).executes { c -> mark(StringArgumentType.getString(c, "note")); 1 }))
                .then(literal("probes").executes { listProbes(); 1 })
                .then(literal("probe").then(argument("id", StringArgumentType.word()).executes { c -> DevgineerClient.msg(BrProbes.start(StringArgumentType.getString(c, "id"))); 1 }))
                .then(literal("stop").executes { DevgineerClient.msg(BrProbes.stop(null)); 1 }.then(argument("id", StringArgumentType.word()).executes { c -> DevgineerClient.msg(BrProbes.stop(StringArgumentType.getString(c, "id"))); 1 }))
                .then(literal("wanted").executes { listWanted(); 1 })
                .then(literal("folder").executes { Files.createDirectories(dir); com.mojang.blaze3d.Blaze3D.openPath(dir); 1 }))
        }
    }

    override fun onDisable() {
        stop("turned off")
        super.onDisable()
    }

    private fun shouldRecord(): Boolean {
        val mc = DevgineerClient.mc
        if (mc.player == null || mc.level == null) return false
        val ads = mc.isLocalServer
        return when (where) {
            Where.EVERYWHERE -> true
            Where.DUNGEONS -> !ads && DungeonUtils.inDungeons
            Where.DUNGEONS_AND_ADS -> DungeonUtils.inDungeons
        }
    }

    private fun tick() {
        clientTick++
        val s = session
        if (s == null) {
            if (enabled && shouldRecord()) start()
            return
        }
        s.tick()
    }

    private fun start() {
        val mc = DevgineerClient.mc
        val floor = runCatching { DungeonUtils.floor?.name }.getOrNull() ?: "X"
        val tag = if (mc.isLocalServer) "ADS" else "HYP"
        val name = "${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}_${tag}_$floor.brec.jsonl.gz"
        val s = BrSession(dir.resolve(name), tag)
        counts.clear()
        session = s
        s.start()
        BrProbes.newRun()
    }

    private fun stop(why: String) {
        val s = session ?: return
        session = null
        s.end(why)
    }

    private fun mark(note: String?) {
        val s = session ?: run { DevgineerClient.msg("§7Better Recorder: not recording."); return }
        s.line("mark") { it.s("note", note) }
        DevgineerClient.msg("§aMarked§7 at tick ${s.t}${if (note != null) ": $note" else ""}")
    }

    private fun status(): String {
        val s = session ?: return "§7Better Recorder: not recording${if (!enabled) " (off)" else ""}. /brec probes lists the open questions."
        val top = counts.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" }
        return "§aBetter Recorder§7: ${s.path.fileName}, tick ${s.t}, ${s.file.lines.get()} lines, ${s.file.bytes.get() / 1024} KB raw. $top. Probes: ${BrProbes.active().ifEmpty { listOf("none") }.joinToString()}"
    }

    private fun listProbes() {
        DevgineerClient.msg("§aProbes§7 (/brec probe <id>, /brec stop):")
        for (p in BrProbes.PROBES) DevgineerClient.chat("§e${p.id}§7 ${p.title}${if (p.id in BrProbes.active()) " §a(on)" else ""}§8 - ${p.how}")
    }

    // ------------------------------------------------------------------ wanted sightings, counted across recordings

    private val wantedFile get() = dir.resolve("wanted.properties")

    private fun wantedCounts(): java.util.Properties {
        val p = java.util.Properties()
        runCatching { Files.newInputStream(wantedFile).use { p.load(it) } }
        return p
    }

    fun caught(id: String, title: String) {
        val p = wantedCounts()
        val n = (p.getProperty(id)?.toIntOrNull() ?: 0) + 1
        p.setProperty(id, n.toString())
        runCatching { Files.createDirectories(dir); Files.newOutputStream(wantedFile).use { p.store(it, "Better Recorder: runs each wanted sighting was caught in") } }
        if (announce) DevgineerClient.msg("§aBetter Recorder caught: §f$title§7 (${if (n == 1) "first time" else "$n runs"})")
    }

    private fun listWanted() {
        val p = wantedCounts()
        DevgineerClient.msg("§aWanted sightings§7 (runs caught in):")
        for (w in BrProbes.WANTED) DevgineerClient.chat("§7- ${w.title}: §f${p.getProperty(w.id) ?: "0"}")
    }
}
