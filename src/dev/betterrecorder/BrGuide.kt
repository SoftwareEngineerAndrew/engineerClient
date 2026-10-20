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
 * The guide: where to go in this dungeon so the recording catches what ADS still lacks. It reads Odin's map for the rooms this run
 * has that are still wanted (puzzles, the trap, the miniboss room, rare rooms), points at the best one (a HUD line and a pillar in the
 * world), says what to do there (always just "stay until it is done" or "stand still"), and counts each one caught across runs, so
 * it stops asking once there is enough. Everything else is recorded whatever you do. Game thread only.
 */
object BrGuide {
    /** A kind of thing wanted: rooms whose Odin name or type match, how many runs of it are wanted, what to do there. */
    private class Want(val id: String, val goal: Int, val doThere: String, val match: (DungeonRoom) -> Boolean)

    private fun named(n: String): (DungeonRoom) -> Boolean = { it.data?.name == n }

    private val WANTS = buildList {
        // Puzzles: the ones least seen and with the most layouts want the most visits.
        val puzzleGoals = linkedMapOf("Water Board" to 20, "Tic Tac Toe" to 10, "Boulder" to 12, "Ice Fill" to 10, "Teleport Maze" to 6,
            "Three Weirdos" to 6, "Quiz" to 6, "Creeper Beams" to 6, "Ice Path" to 6, "Higher Blaze" to 6, "Lower Blaze" to 6)
        for ((n, g) in puzzleGoals) add(Want("puzzle:$n", g, "stay in it until it is solved", named(n)))
        add(Want("trap:New Trap", 8, "go through it to the end chest", named("New Trap")))
        add(Want("trap:Old Trap", 8, "go through it to the end chest", named("Old Trap")))
        for (n in listOf("Shadow Assassin", "King Midas", "Dragon")) add(Want("mini:$n", 6, "stay until the miniboss is dead", named(n)))
        add(Want("rare", 10, "walk all through it", { it.type == RoomType.RARE || it.data?.type == RoomType.RARE }))
    }

    /** Hits taken from mobs wanted in all (melee reach and swing rate), and per run at most. */
    private const val HITS_GOAL = 300
    private const val HITS_PER_RUN = 15
    /** Rooms walked into and stood still in for [STILL_TICKS] (when and how mobs notice you), in all and per run. */
    private const val STILL_GOAL = 60
    private const val STILL_PER_RUN = 3
    private const val STILL_TICKS = 80

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
    private var hitsThisRun = 0
    private var stillThisRun = 0
    private var stillRoom: DungeonRoom? = null
    private var stillLeft = 0
    private var stillAt: Vec3? = null
    private val roomsEntered = HashSet<Int>()
    private var lastHurt = 0
    private var lastHp = 0f

    fun newRun() {
        tasks.clear(); target = null; hitsThisRun = 0; stillThisRun = 0; stillRoom = null; stillLeft = 0; roomsEntered.clear()
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

        // Standing still on entering a room with mobs (aggro), a few times a run.
        if (here != null && System.identityHashCode(here) !in roomsEntered) {
            roomsEntered += System.identityHashCode(here)
            val normal = here.type == RoomType.NORMAL || here.data?.type == RoomType.NORMAL
            if (normal && !isDone(here) && stillThisRun < STILL_PER_RUN && count("still") < STILL_GOAL) {
                stillRoom = here; stillLeft = STILL_TICKS; stillAt = me.position()
                s.line("guide") { j -> j.s("still", here.data?.name) }
            }
        }
        if (stillRoom != null) {
            if (here !== stillRoom) { stillRoom = null; stillLeft = 0 }
            else {
                // Moving restarts the count (a step is fine).
                if ((stillAt?.distanceTo(me.position()) ?: 0.0) > 1.5) { stillLeft = STILL_TICKS; stillAt = me.position() }
                stillLeft -= 5
                if (stillLeft <= 0) {
                    stillThisRun++; stillRoom = null
                    val n = bump("still")
                    s.line("guide") { j -> j.s("got", "still").i("n", n).i("goal", STILL_GOAL) }
                }
            }
        }

        target = next()
    }

    /** Each tick: hits taken from mobs (the melee capture writes the line; this counts it). */
    fun hit() {
        hitsThisRun++
        if (hitsThisRun <= HITS_PER_RUN) bump("hits")
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

    private fun mobsNear(me: Player) = DevgineerClient.mc.level?.entitiesForRendering()?.count {
        it is LivingEntity && it !is ArmorStand && it !is Player && it.isAlive && it.distanceTo(me) < 10f
    } ?: 0

    // ------------------------------------------------------------------ drawing

    fun lines(example: Boolean): List<String> {
        if (example) return listOf("§b§lBetter Recorder", "§fGo to §eWater Board §7(42 blocks NE)", "§7Then: stay in it until it is solved")
        val s = BetterRecorder.session ?: return emptyList()
        if (!on(s)) return emptyList()
        val me = DevgineerClient.mc.player ?: return emptyList()
        val out = mutableListOf("§b§lBetter Recorder")
        val sl = stillRoom
        if (sl != null) out += "§6Stand still here §f${(stillLeft + 19) / 20}s§7 (watching the mobs notice you)"
        val t = target
        val here = BrRooms.current()
        if (t != null) {
            if (here === t.room) out += "§aYou're there: §f${t.want.doThere}"
            else {
                val c = center(t.room)
                val far = if (c == null) "" else " §7(${Math.hypot(c.x - me.x, c.z - me.z).toInt()} blocks ${direction(c.x - me.x, c.z - me.z)})"
                out += "§fGo to §e${label(t)}$far"
                out += "§7Then: ${t.want.doThere}"
            }
        } else if (sl == null) out += "§7Nothing to go to: play normally"
        if (hitsThisRun < HITS_PER_RUN && count("hits") < HITS_GOAL && here != null && !isDone(here) && mobsNear(me) > 0 && me.health > me.maxHealth / 2)
            out += "§7While here: let a mob hit you §f($hitsThisRun/$HITS_PER_RUN)"
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
        WANTS.map { "§7- ${it.id.substringAfter(':')}${if (it.id.startsWith("mini:")) " room" else ""}: §f${count(it.id)}/${it.goal}" } +
            listOf("§7- mob hits: §f${count("hits")}/$HITS_GOAL", "§7- standing still in a new room: §f${count("still")}/$STILL_GOAL")
}
