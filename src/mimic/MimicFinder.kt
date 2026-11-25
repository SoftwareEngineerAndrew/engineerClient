package com.engineerclient.mimic

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.features.impl.dungeon.map.DungeonMap
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.Color
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.MORT_REGEX
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.engineerclient.EngineerClient
import com.engineerclient.dungeon.SecretWaypoints
import com.engineerclient.dungeonpack.pack.secrets.SecretPack
import com.engineerclient.dungeonpack.pack.secrets.SecretType
import com.engineerclient.mappred.Cls
import com.engineerclient.mappred.FloorRules
import com.engineerclient.mappred.MapPrediction
import com.engineerclient.mappred.MapStats
import com.engineerclient.mappred.MapView
import com.engineerclient.mappred.Shape
import com.engineerclient.pov.PoseShare
import com.engineerclient.terminals.TermDoneWarning
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * The Mimic on F6, F7, M6 and M7: each room's and split's chance from the chest sites nobody has ruled out, chests seen by
 * any linked player (through glass, bars and the like), and the room once it's certain.
 */
object MimicFinder {
    private val dropdown = DropdownSetting("Mimic Prediction", desc = "The chance each room and split holds the Mimic.")
    private val enabled = BooleanSetting("Mimic Chances", true, desc = "Colours a room likely to hold the Mimic and adds its chance to split labels.").withDependency { dropdown.value }
    private val threshold = NumberSetting("Mimic Chance", 50, 1..100, 1, unit = "%", desc = "A room this likely to hold the Mimic gets the Mimic Room colour.").withDependency { dropdown.value && enabled.value }
    private val message = BooleanSetting("Mimic Message", true, desc = "Says the Mimic's room in party chat once it's certain.").withDependency { dropdown.value && enabled.value }
    val steerNames = BooleanSetting("Mimic Steers Names", false, desc = "Exact Last Rooms guesses names with chests the Mimic could still be in.").withDependency { dropdown.value && enabled.value }

    private const val PLAIN: Byte = 1
    private const val TRAPPED: Byte = 2
    private const val MOB: Byte = 4
    private const val SENT: Byte = 5
    private const val RANGE = 80.0
    /** Each sender in line waits this long for the one before (party chat or a linked "sent"). */
    private const val TURN_TICKS = 30
    /** Rooms with a trapped chest of their own design. */
    private val OWN_TRAPPED = setOf("Buttons", "Dueces", "Slime", "Redstone Key")

    private val seen = ConcurrentHashMap<BlockPos, Byte>()
    @Volatile private var found: BlockPos? = null
    @Volatile private var run = 0
    private var mortTick = -1L
    @Volatile private var sent = false
    private var certainAt = -1L
    private var ticks = 0
    private var linked: Set<String> = emptySet()

    private var roomOdds: Map<DungeonRoom, Double> = IdentityHashMap()
    private var splitOdds: Map<Int, Double> = emptyMap()
    @Volatile var ruledLocked = 0; private set
    @Volatile var ruledPlain = 0; private set
    @Volatile private var totals = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    fun install() {
        for (s in listOf(dropdown, enabled, threshold, message, steerNames)) DungeonMap.registerSetting(s)
        on<LevelEvent.Load> { reset() }
        on<MessageEvent.Chat> {
            if (MORT_REGEX.matches(message) && mortTick < 0) mortTick = mc().level?.gameTime ?: 0L
            if (message.startsWith("Party > ") && message.contains(": Mimic is in ")) sent = true
        }
        on<TickEvent.End> { EngineerClient.safely("mimic finder") { tick() } }
    }

    private fun mc() = EngineerClient.mc

    private fun reset() {
        run++
        seen.clear(); found = null; mortTick = -1L; sent = false; certainAt = -1L
        roomOdds = IdentityHashMap(); splitOdds = emptyMap(); linked = emptySet()
        ruledLocked = 0; ruledPlain = 0; totals = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    }

    val active: Boolean get() = enabled.value && DungeonUtils.inClear && (DungeonUtils.floor?.floorNumber ?: 0) >= 6 && !DungeonUtils.mimicKilled

    /** The Mimic Room colour when [room] is at least Mimic Chance likely (or certain) to hold it. */
    fun roomColor(room: DungeonRoom): Color? {
        if (!active) return null
        val p = roomOdds[room] ?: return null
        return if (p * 100 >= threshold.value - 1e-6) DungeonMap.mimicRoomColor else null
    }

    /** The split behind the unknown tile [tile]'s chance. */
    fun splitChance(tile: Int): Double? = if (active) splitOdds[tile] else null

    private fun tick() {
        if (!active) return
        if (++ticks % 5 != 0) return
        val level = mc().level ?: return
        val player = mc().player ?: return
        // joined mid-run: someone has already walked into a room past the entrance
        if (mortTick < 0) { if (DungeonScan.rooms.any { it.walkedInto && it.type != RoomType.ENTRANCE }) mortTick = level.gameTime else return }
        val rooms = DungeonScan.rooms.filter { it.isViewable && it.type != RoomType.TRAP }
        val sites = rooms.associateWith { SecretWaypoints.chestSites(it) }
        if (level.gameTime - mortTick >= 10) look(level, player.eyePosition, sites)
        val now = PoseShare.linkedNames.toSet()
        if (now != linked) { if ((now - linked).isNotEmpty()) for ((p, s) in seen) share(p, s); linked = now }
        if (ticks % 10 == 0) work(level, rooms, sites)
    }

    /** What this player can see of the chest sites now: a plain chest rules its site out, a trapped chest is the Mimic. */
    private fun look(level: ClientLevel, eye: Vec3, sites: Map<DungeonRoom, List<SecretWaypoints.ChestSite>?>) {
        for ((room, list) in sites) for (s in list ?: continue) {
            if (seen.containsKey(s.pos)) continue
            if (eye.distanceToSqr(Vec3.atCenterOf(s.pos)) > RANGE * RANGE || !level.isLoaded(s.pos)) continue
            val st = level.getBlockState(s.pos)
            val what = when {
                st.`is`(Blocks.TRAPPED_CHEST) -> if (s.extra && room.name in OWN_TRAPPED) continue else TRAPPED
                st.`is`(Blocks.CHEST) -> PLAIN
                else -> continue
            }
            if (!visible(level, eye, s.pos)) continue
            mark(s.pos, what, true)
        }
        if (found != null) return
        for (e in level.entitiesForRendering()) {
            val name = e.customName?.string ?: continue
            if (!name.contains("Mimic") || name.contains(" 0❤")) continue
            val point = e.position().add(0.0, 0.5, 0.0)
            if (eye.distanceToSqr(point) > RANGE * RANGE || !ray(level, eye, point, BlockPos.containing(point))) continue
            // it chases players: only where it stands by a chest site it could have come out of
            val site = sites.values.asSequence().filterNotNull().flatten().filter { seen[it.pos] != PLAIN }
                .minByOrNull { Vec3.atCenterOf(it.pos).distanceToSqr(e.position()) }
                ?.takeIf { Vec3.atCenterOf(it.pos).distanceToSqr(e.position()) <= 16.0 } ?: continue
            mark(site.pos, MOB, true)
            break
        }
    }

    private fun mark(pos: BlockPos, what: Byte, mine: Boolean) {
        val p = pos.immutable()
        seen[p] = what
        if (what != PLAIN && found == null) found = p
        if (mine) share(p, what)
    }

    private fun share(pos: BlockPos, what: Byte) {
        val b = ByteBuffer.allocate(15)
        b.put(TermDoneWarning.KIND.toByte()).put(TermDoneWarning.MIMIC.toByte()).put(what).putInt(pos.x).putInt(pos.y).putInt(pos.z)
        PoseShare.shareReliable(b.array())
    }

    /** A linked teammate's frame (after its kind and subtype). */
    fun heard(b: ByteBuffer) {
        if (b.remaining() != 13) return
        val what = b.get()
        val pos = BlockPos(b.int, b.int, b.int)
        if (what == SENT) { sent = true; return }
        if (what != PLAIN && what != TRAPPED && what != MOB || tileOf(pos) == null) return
        val r = run
        mc().execute {
            if (run != r || !active || seen.containsKey(pos)) return@execute
            mark(pos, what, false)
        }
    }

    /** A clear line from [eye] to [target] (a few points on its block): glass, panes, bars, barriers and the like let it through. */
    private fun visible(level: Level, eye: Vec3, target: BlockPos): Boolean {
        val x = target.x.toDouble(); val y = target.y.toDouble(); val z = target.z.toDouble()
        for (p in arrayOf(Vec3(x + .5, y + .5, z + .5), Vec3(x + .15, y + .8, z + .15), Vec3(x + .85, y + .8, z + .85), Vec3(x + .15, y + .8, z + .85), Vec3(x + .85, y + .8, z + .15)))
            if (ray(level, eye, p, target)) return true
        return false
    }

    private fun ray(level: Level, from: Vec3, to: Vec3, target: BlockPos): Boolean {
        val d = to.subtract(from)
        var x = floor(from.x).toInt(); var y = floor(from.y).toInt(); var z = floor(from.z).toInt()
        val sx = sign(d.x).toInt(); val sy = sign(d.y).toInt(); val sz = sign(d.z).toInt()
        fun first(o: Double, v: Double, s: Int) = if (s == 0) Double.MAX_VALUE else ((if (s > 0) floor(o) + 1 else floor(o)) - o) / v
        var tx = first(from.x, d.x, sx); var ty = first(from.y, d.y, sy); var tz = first(from.z, d.z, sz)
        val dx = if (sx == 0) Double.MAX_VALUE else abs(1 / d.x); val dy = if (sy == 0) Double.MAX_VALUE else abs(1 / d.y); val dz = if (sz == 0) Double.MAX_VALUE else abs(1 / d.z)
        val pos = BlockPos.MutableBlockPos()
        repeat(400) {
            pos.set(x, y, z)
            if (pos == target) return true
            val st = level.getBlockState(pos)
            if (st.fluidState.`is`(FluidTags.LAVA)) return false
            if (!st.isAir && !st.`is`(Blocks.BARRIER)) {
                val shape = st.getVisualShape(level, pos, CollisionContext.empty())
                if (shape === Shapes.block()) return false
                if (!shape.isEmpty && shape.clip(from, to, pos) != null) return false
            }
            if (tx >= 1 && ty >= 1 && tz >= 1) return false
            when {
                tx <= ty && tx <= tz -> { x += sx; tx += dx }
                ty <= tz -> { y += sy; ty += dy }
                else -> { z += sz; tz += dz }
            }
        }
        return false
    }

    private fun tileOf(pos: BlockPos): Int? {
        val tx = Math.floorDiv(pos.x + 200, 32); val tz = Math.floorDiv(pos.z + 200, 32)
        return if (tx in 0..5 && tz in 0..5) tz * 6 + tx else null
    }

    private fun roomAt(tile: Int): DungeonRoom? = DungeonScan.rooms.firstOrNull { r -> r.tiles.any { it.z * 6 + it.x == tile } }

    private fun work(level: Level, rooms: List<DungeonRoom>, sites: Map<DungeonRoom, List<SecretWaypoints.ChestSite>?>) {
        val f = found
        if (f != null) {
            val room = tileOf(f)?.let(::roomAt)
            roomOdds = IdentityHashMap<DungeonRoom, Double>().also { m -> room?.let { m[it] = 1.0 } }
            splitOdds = emptyMap()
            room?.name?.let { announce(level, it) }
            return
        }
        val avg = PoolSites.get() ?: return
        val parts = ArrayList<MimicOdds.Part>()
        var n = 0.0; var m = 0.0; var rl = 0; var rp = 0
        var unplaced = false
        for (room in rooms) {
            val list = sites[room]
            if (list != null) {
                var ol = 0; var op = 0
                for (s in list) {
                    if (s.locked) n++ else m++
                    if (seen[s.pos] == PLAIN) { if (s.locked) rl++ else rp++ } else if (s.locked) ol++ else op++
                }
                parts += MimicOdds.Part(room, ol.toDouble(), op.toDouble())
            } else {
                unplaced = true
                val pool = poolOf(room) ?: continue
                n += avg.locked[pool]; m += avg.plain[pool]
                parts += MimicOdds.Part(room, avg.locked[pool], avg.plain[pool])
            }
        }
        var unknown = false
        val p = MapPrediction.current()
        if (p != null) for (s in p.splits) {
            if (roomAt(s.tile)?.isViewable == true) continue
            val l = (0 until Cls.COUNT).sumOf { s.expect[it] * avg.byCls(it, true) }
            val pl = (0 until Cls.COUNT).sumOf { s.expect[it] * avg.byCls(it, false) }
            if (l + pl <= 0.0) continue
            unknown = true
            n += l; m += pl
            parts += MimicOdds.Part(s.tile, l, pl)
        } else {
            val known = DungeonScan.rooms.filter { it.isViewable }.flatMap { r -> r.tiles.map { it.z * 6 + it.x } }.toSet()
            val left = (FloorRules.of(DungeonUtils.floor?.floorNumber ?: 0)?.let { it.w * it.h } ?: 36) - known.size
            if (left > 0) {
                unknown = true
                n += left * avg.perTileLocked; m += left * avg.perTilePlain
                parts += MimicOdds.Part(-1, left * avg.perTileLocked, left * avg.perTilePlain)
            }
        }
        val odds = MimicOdds.odds(parts, n, m)
        roomOdds = IdentityHashMap<DungeonRoom, Double>().also { r -> for ((k, v) in odds) if (k is DungeonRoom) r[k] = v }
        splitOdds = odds.filterKeys { it is Int && it >= 0 }.mapKeys { it.key as Int }
        ruledLocked = rl; ruledPlain = rp; totals = doubleArrayOf(n, m, rl.toDouble(), rp.toDouble())
        if (!unknown && !unplaced) {
            val only = odds.filterValues { it > 0.0 }.keys.singleOrNull() as? DungeonRoom
            only?.name?.let { announce(level, it) }
        }
    }

    /**
     * Once certain: the linked players in name order each wait their turn for the message from the one before, so one
     * says it even when someone has Mimic Message off.
     */
    private fun announce(level: Level, room: String) {
        if (sent) return
        if (certainAt < 0) certainAt = level.gameTime
        val me = mc().user.name.lowercase()
        val rank = (PoseShare.linkedNames + me).distinct().sorted().indexOf(me)
        if (level.gameTime - certainAt < rank * TURN_TICKS || !message.value) return
        sent = true
        sendCommand("pc Mimic is in $room")
        found?.let { share(it, SENT) } ?: share(BlockPos.ZERO, SENT)
    }

    private fun poolOf(room: DungeonRoom): Int? {
        val tiles = room.tiles.map { it.z * 6 + it.x }.distinct().toIntArray()
        return when (room.type) {
            RoomType.NORMAL, RoomType.RARE -> {
                val shape = MapView.shapeOf(tiles, 6) ?: return null
                if (shape != Shape.ONE) shape.ordinal else if (room.type == RoomType.RARE && room.data != null) MapStats.POOL_RARE else MapStats.POOL_1X1
            }
            RoomType.PUZZLE -> MapStats.POOL_PUZZLE
            RoomType.CHAMPION -> MapStats.POOL_YELLOW
            RoomType.BLOOD -> MapStats.POOL_BLOOD
            RoomType.FAIRY -> MapStats.POOL_FAIRY
            RoomType.ENTRANCE -> MapStats.POOL_ENTRANCE
            else -> null
        }
    }

    /**
     * For the name guesses, a snapshot: how much each name's chest sites fit the Mimic not being found yet (1 for no
     * change); null when Mimic Steers Names has nothing to say.
     */
    fun nameFactors(): Map<String, Double>? {
        if (!steerNames.value || !active || found != null) return null
        val avg = PoolSites.get() ?: return null
        val (n, m, rl, rp) = totals
        if (rl + rp <= 0.0) return null
        val base = MimicOdds.notFound(n, m, rl, rp)
        if (base <= 0.0) return null
        return avg.byName.mapValues { (_, s) -> MimicOdds.notFound(n + s.first, m + s.second, rl, rp) / base }
    }

    private operator fun DoubleArray.component4() = this[3]

    /** "mob", "miniboss" or "none" for a room name, from the rooms pack. */
    fun clearOf(name: String): String? = SecretWaypoints.pack?.designsByName(name)?.firstOrNull()?.clear

    /** Each name pool's chest sites, weighted by how often each name comes up. */
    class PoolSites(val locked: DoubleArray, val plain: DoubleArray, val byName: Map<String, Pair<Int, Int>>) {
        private val bigLocked: Double
        private val bigPlain: Double
        val perTileLocked: Double
        val perTilePlain: Double

        init {
            val big = MapStats.NAMES.filter { it.pool < MapStats.POOL_1X1 }
            val w = big.sumOf { it.seen.toDouble() }.coerceAtLeast(1.0)
            bigLocked = big.sumOf { it.seen * locked[it.pool] } / w
            bigPlain = big.sumOf { it.seen * plain[it.pool] } / w
            val maps = MapStats.NAMES.filter { it.pool == MapStats.POOL_ENTRANCE }.sumOf { it.seen }.coerceAtLeast(1)
            perTileLocked = MapStats.NAMES.sumOf { it.seen.toDouble() * (byName[it.name]?.first ?: 0) } / maps / 36
            perTilePlain = MapStats.NAMES.sumOf { it.seen.toDouble() * (byName[it.name]?.second ?: 0) } / maps / 36
        }

        fun byCls(c: Int, lockedSites: Boolean): Double {
            val a = if (lockedSites) locked else plain
            return when (c) {
                Cls.NORMAL -> a[MapStats.POOL_1X1]
                Cls.BIG -> if (lockedSites) bigLocked else bigPlain
                Cls.PUZZLE -> a[MapStats.POOL_PUZZLE]
                Cls.YELLOW -> a[MapStats.POOL_YELLOW]
                Cls.RARE -> a[MapStats.POOL_RARE]
                Cls.BLOOD -> a[MapStats.POOL_BLOOD]
                Cls.FAIRY -> a[MapStats.POOL_FAIRY]
                else -> 0.0
            }
        }

        companion object {
            @Volatile private var made: PoolSites? = null

            fun get(): PoolSites? {
                made?.let { return it }
                val pack = SecretWaypoints.pack ?: return null
                val secrets = SecretPack.bundled
                val byName = HashMap<String, Pair<Int, Int>>()
                for (n in MapStats.NAMES) {
                    if (n.pool == MapStats.POOL_TRAP) continue
                    val d = pack.designsByName(n.name).firstOrNull()?.let { secrets.design(it.id) } ?: continue
                    val puzzle = n.pool == MapStats.POOL_PUZZLE
                    val chests = d.sites.filter { (it.type == SecretType.CHEST || it.type == SecretType.LOCKED_CHEST) && !(puzzle && it.id.startsWith('x')) }
                    byName[n.name] = chests.count { it.type == SecretType.LOCKED_CHEST } to chests.count { it.type == SecretType.CHEST }
                }
                val locked = DoubleArray(MapStats.POOLS); val plain = DoubleArray(MapStats.POOLS)
                for (p in 0 until MapStats.POOLS) {
                    val names = MapStats.NAMES.filter { it.pool == p }
                    val w = names.sumOf { it.seen.toDouble() }
                    if (w <= 0) continue
                    locked[p] = names.sumOf { it.seen * (byName[it.name]?.first ?: 0).toDouble() } / w
                    plain[p] = names.sumOf { it.seen * (byName[it.name]?.second ?: 0).toDouble() } / w
                }
                return PoolSites(locked, plain, byName).also { made = it }
            }
        }
    }
}
