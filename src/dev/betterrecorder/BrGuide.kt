package com.devgineerclient.betterrecorder

import com.devgineerclient.DevgineerClient
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.MapCheckmark
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.Properties

/**
 * The guide: where to go in this dungeon for what neither the Dungeon Recorder (286 F7 clears, with every chunk, block change and
 * mob it was sent) nor this recorder catches by just playing. That is now only the rare rooms (8 seen in all the DR runs): when this
 * run has one, a HUD line and a pillar point at it and say to walk through it. Each catch is counted across runs and it stops asking
 * once there are enough. Puzzles, the trap and the miniboss rooms are not asked for: the DR has 12 to 62 runs of each watched
 * (the room loaded while it was done). Game thread only.
 */
object BrGuide {
    /** A kind of thing wanted: rooms whose Odin name or type match, how many runs of it are wanted, what to do there. */
    private class Want(val id: String, val goal: Int, val doThere: String, val match: (DungeonRoom) -> Boolean)


    private val WANTS = listOf(
        Want("rare", 10, "walk all through it", { it.type == RoomType.RARE || it.data?.type == RoomType.RARE }),
    )

    // ------------------------------------------------------------------ counts across runs

    private val countsFile get() = BetterRecorder.dir.resolve("guide.properties")
    private val counts: Properties by lazy { Properties().also { p -> runCatching { Files.newInputStream(countsFile).use { p.load(it) } } } }
    private fun count(id: String) = counts.getProperty(id)?.toIntOrNull() ?: 0
    private fun bump(id: String, by: Int = 1): Int {
        val n = count(id) + by
        if (BetterRecorder.session?.where != "HYP") return n
        counts.setProperty(id, n.toString())
        runCatching { Files.createDirectories(BetterRecorder.dir); Files.newOutputStream(countsFile).use { counts.store(it, "Better Recorder guide: how many of each have been caught") } }
        return n
    }

    // ------------------------------------------------------------------ this run

    private class Task(val want: Want, val room: DungeonRoom) {
        var visited = false
        var done = false
        var missed = false
    }

    private val tasks = LinkedHashMap<String, Task>()
    private var target: Task? = null
    private val roomsEntered = HashSet<Int>()
    private var lastHurt = 0
    private var lastHp = 0f

    fun newRun() {
        tasks.clear(); target = null
    }

    /** `/brec guidetest`: the guide in ADS runs too (nothing it catches there is counted). */
    var inAds = false

    private fun on(s: BrSession) = (s.where == "HYP" || inAds) && !DungeonUtils.inBoss

    private fun isDone(r: DungeonRoom) = r.checkmark == MapCheckmark.WHITE || r.checkmark == MapCheckmark.GREEN || r.checkmark == MapCheckmark.RED

    /** Every 5 ticks while recording a Hypixel dungeon (ADS runs are not asked of). */
    fun tick(s: BrSession) {
        if (!on(s)) { target = null; return }
        val me = DevgineerClient.mc.player ?: return
        val rooms = runCatching { DungeonScan.rooms.toList() }.getOrNull() ?: return
        val here = BrRooms.current()

        // New wanted rooms on the map.
        for (r in rooms) for (w in WANTS) {
            if (!w.match(r) || count(w.id) >= w.goal) continue
            val key = w.id + "@" + System.identityHashCode(r)
            if (key !in tasks) {
                // A room already done when first seen is of no use (the recording would miss how it went).
                val t = Task(w, r); if (isDone(r)) t.missed = true
                tasks[key] = t
            }
        }

        for (t in tasks.values) {
            if (t.done || t.missed) continue
            if (here === t.room) t.visited = true
            if (isDone(t.room)) {
                if (t.visited) {
                    t.done = true
                    val n = bump(t.want.id)
                    s.line("guide") { j -> j.s("got", t.want.id).i("n", n).i("goal", t.want.goal) }
                    DevgineerClient.msg("§aBetter Recorder got it: §f${label(t)}§7 ($n/${t.want.goal}). ${next()?.let { "Next: §e${label(it)}" } ?: "§7Nothing else to go to: play normally."}")
                } else t.missed = true
            }
        }

        target = next()
    }

    /** The open task to go to: the most wanted, then the nearest. */
    private fun next(): Task? {
        val me = DevgineerClient.mc.player ?: return null
        return tasks.values.filter { !it.done && !it.missed }.minByOrNull { t ->
            val c = center(t.room) ?: return@minByOrNull Double.MAX_VALUE
            val need = 1.0 - count(t.want.id).toDouble() / t.want.goal
            Math.hypot(c.x - me.x, c.z - me.z) * (1.5 - need)
        }
    }

    private fun label(t: Task) = t.room.data?.name ?: t.want.id.substringAfter(':')

    private fun center(r: DungeonRoom): Vec3? {
        val b = BrRooms.box(r) ?: return null
        return Vec3((b[0] + b[2] + 1) / 2.0, 70.0, (b[1] + b[3] + 1) / 2.0)
    }

    private fun direction(dx: Double, dz: Double): String {
        val a = Math.toDegrees(Math.atan2(dx, -dz)).let { if (it < 0) it + 360 else it }
        return arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((a + 22.5) / 45).toInt() % 8]
    }

    // ------------------------------------------------------------------ drawing

    fun lines(example: Boolean): List<String> {
        if (example) return listOf("§b§lBetter Recorder", "§fGo to §eSand Dragon §7(42 blocks NE)", "§7Then: walk all through it")
        val s = BetterRecorder.session ?: return emptyList()
        if (!on(s)) return emptyList()
        val me = DevgineerClient.mc.player ?: return emptyList()
        val out = mutableListOf("§b§lBetter Recorder")
        val t = target ?: return emptyList()
        val here = BrRooms.current()
        if (here === t.room) out += "§aYou're there: §f${t.want.doThere}"
        else {
            val c = center(t.room)
            val far = if (c == null) "" else " §7(${Math.hypot(c.x - me.x, c.z - me.z).toInt()} blocks ${direction(c.x - me.x, c.z - me.z)})"
            out += "§fGo to §e${label(t)}$far"
            out += "§7Then: ${t.want.doThere}"
        }
        return out
    }

    fun draw(gfx: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val ls = lines(example)
        if (ls.isEmpty()) return 0 to 0
        var w = 0
        ls.forEachIndexed { i, l -> w = maxOf(w, gfx.textDim(l, 0, i * 10, Colors.WHITE).first); gfx.text(l, 0, i * 10, Colors.WHITE) }
        return w to ls.size * 10
    }

    private val PILLAR = Color(80, 220, 255, 0.9f)

    fun render(ev: RenderExtractEvent) {
        val t = target ?: return
        if (BrRooms.current() === t.room) return
        val c = center(t.room) ?: return
        ev.drawWireFrameBox(AABB(c.x - 1.5, 60.0, c.z - 1.5, c.x + 1.5, 110.0, c.z + 1.5), PILLAR, 3f, false)
        ev.drawText("§e${label(t)}", Vec3(c.x, 112.0, c.z), 3f, false)
    }

    /** For `/brec guide`: each wanted thing with its count. */
    fun summary(): List<String> =
        WANTS.map { "§7- ${it.id} rooms: §f${count(it.id)}/${it.goal}" }
}
