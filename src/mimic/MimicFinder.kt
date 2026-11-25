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
import com.engineerclient.mappred.MapPrediction
import com.engineerclient.mappred.MapStats
import com.engineerclient.mappred.MapView
import com.engineerclient.mappred.RoomName
import com.engineerclient.mappred.Shape
import com.engineerclient.pov.PoseShare
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
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

    private const val KIND = 16
    private const val PLAIN: Byte = 1
    private const val TRAPPED: Byte = 2
    private const val OPENED: Byte = 3
    private const val MOB: Byte = 4
    private const val RANGE = 80.0

    /** Chest sites and what was seen there; a found Mimic's spot. */
    private val seen = ConcurrentHashMap<BlockPos, Byte>()
    @Volatile private var found: BlockPos? = null
    @Volatile private var foundByMe = false
    private var mortTick = -1L
    private var sent = false
    private var ticks = 0
    private var linked: Set<String> = emptySet()

    private var roomOdds: Map<DungeonRoom, Double> = IdentityHashMap()
    private var splitOdds: Map<Int, Double> = emptyMap()
    @Volatile var ruledLocked = 0; private set
    @Volatile var ruledPlain = 0; private set
    @Volatile var totalLocked = 0.0; private set
    @Volatile var totalPlain = 0.0; private set

    fun install() {
        for (s in listOf(dropdown, enabled, threshold, message, steerNames)) DungeonMap.registerSetting(s)
        PoseShare.onExtra(KIND) { sender, b -> EngineerClient.safely("mimic link") { heard(sender, b) } }
        on<LevelEvent.Load> { reset() }
        on<MessageEvent.Chat> { if (MORT_REGEX.matches(message) && mortTick < 0) mortTick = mc().level?.gameTime ?: 0L }
        on<TickEvent.End> { EngineerClient.safely("mimic finder") { tick() } }
    }

    private fun mc() = EngineerClient.mc

    private fun reset() {
        seen.clear(); found = null; foundByMe = false; mortTick = -1L; sent = false
        roomOdds = IdentityHashMap(); splitOdds = emptyMap(); linked = emptySet()
        ruledLocked = 0; ruledPlain = 0; totalLocked = 0.0; totalPlain = 0.0
    }

    val active: Boolean get() = enabled.value && DungeonUtils.inClear && (DungeonUtils.floor?.floorNumber ?: 0) >= 6 && !DungeonUtils.mimicKilled

    /** The Mimic Room colour when [room] is at least Mimic Chance likely (or certain) to hold it. */
    fun roomColor(room: DungeonRoom): Color? {
        if (!active) return null
        val p = roomOdds[room] ?: return null
        return if (p * 100 >= threshold.value - 1e-6) DungeonMap.mimicRoomColor else null
    }

    fun chanceOf(room: DungeonRoom): Double? = if (active) roomOdds[room] else null

    /** The split behind the unknown tile [tile]'s chance. */
    fun splitChance(tile: Int): Double? = if (active) splitOdds[tile] else null

    private fun tick() {
        if (!active) return
        if (++ticks % 5 != 0) return
        val level = mc().level ?: return
        // joined mid-run: someone has already opened a room past the entrance
        if (mortTick < 0) { if (DungeonScan.rooms.any { it.isViewable && it.type != RoomType.ENTRANCE }) mortTick = level.gameTime - 200 else return }
        val player = mc().player ?: return
        val rooms = DungeonScan.rooms.filter { it.isViewable && it.type != RoomType.TRAP }
        val sites = rooms.associateWith { SecretWaypoints.chestSites(it) }
        look(level, player.eyePosition, sites, level.gameTime - mortTick)
        val now = PoseShare.linkedNames.toSet()
        if (now != linked) { if ((now - linked).isNotEmpty()) for ((p, s) in seen) share(p, s); linked = now }
        if (ticks % 10 == 0) work(rooms, sites)
    }

    /** What this player can see of the chest sites now. */
    private fun look(level: net.minecraft.client.multiplayer.ClientLevel, eye: Vec3, sites: Map<DungeonRoom, List<SecretWaypoints.ChestSite>?>, sinceMort: Long) {
        if (sinceMort < 10) return
        for (list in sites.values) for (s in list ?: continue) {
            val known = seen[s.pos]
            if (known == TRAPPED || known == OPENED) continue
            if (eye.distanceToSqr(Vec3.atCenterOf(s.pos)) > RANGE * RANGE || !level.isLoaded(s.pos) || !visible(level, eye, s.pos)) continue
            val st = level.getBlockState(s.pos)
            val what = when {
                st.`is`(Blocks.TRAPPED_CHEST) -> TRAPPED
                st.`is`(Blocks.CHEST) -> PLAIN
                // the Mimic's chest turns to air when it comes out; a locked chest is there from Mort's line, a plain one only once spawned
                st.isAir && (s.locked || known == TRAPPED) -> OPENED
                else -> continue
            }
            if (known == what) continue
            mark(s.pos, what, true)
        }
        if (found != null) return
        for (e in level.entitiesForRendering()) {
            val name = e.customName?.string ?: continue
            if (!name.contains("Mimic") || name.contains(" 0❤")) continue
            val at = e.blockPosition()
            if (eye.distanceToSqr(e.position()) > RANGE * RANGE || !visible(level, eye, at, e.position().add(0.0, 0.5, 0.0))) continue
            mark(at, MOB, true)
            break
        }
    }

    private fun mark(pos: BlockPos, what: Byte, mine: Boolean) {
        val p = pos.immutable()
        seen[p] = what
        if (what != PLAIN && found == null) { found = p; foundByMe = mine }
        if (mine) share(p, what)
    }

    private fun share(pos: BlockPos, what: Byte) {
        val b = ByteBuffer.allocate(14)
        b.put(KIND.toByte()).put(what).putInt(pos.x).putInt(pos.y).putInt(pos.z)
        PoseShare.shareReliable(b.array())
    }

    private fun heard(@Suppress("UNUSED_PARAMETER") sender: String, b: ByteBuffer) {
        if (b.remaining() != 13) return
        val what = b.get()
        if (what !in PLAIN..MOB) return
        val pos = BlockPos(b.int, b.int, b.int)
        val known = seen[pos]
        if (known == what || (known != null && known != PLAIN)) return
        mc().execute { mark(pos, what, false) }
    }

    /** A clear line from [eye] to [target] (a few points on its block): glass, panes, bars, barriers and the like let it through. */
    private fun visible(level: Level, eye: Vec3, target: BlockPos, point: Vec3? = null): Boolean {
        if (point != null) return ray(level, eye, point, target)
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
            if (!st.isAir && !st.`is`(Blocks.BARRIER)) {
                if (st.canOcclude()) return false
                val shape = st.getVisualShape(level, pos, CollisionContext.empty())
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

    private fun work(rooms: List<DungeonRoom>, sites: Map<DungeonRoom, List<SecretWaypoints.ChestSite>?>) {
        val f = found
        if (f != null) {
            val room = tileOf(f)?.let(::roomAt)
            roomOdds = IdentityHashMap<DungeonRoom, Double>().also { m -> room?.let { m[it] = 1.0 } }
            splitOdds = emptyMap()
            room?.name?.let { announce(it, foundByMe) }
            return
        }
        val avg = PoolSites.get() ?: return
        val parts = ArrayList<MimicOdds.Part>()
        var n = 0.0; var m = 0.0; var rl = 0; var rp = 0
        var unplaced = false
        val knownTiles = HashSet<Int>()
        for (room in rooms) {
            for (t in room.tiles) knownTiles += t.z * 6 + t.x
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
            val l = (0 until Cls.COUNT).sumOf { s.expect[it] * avg.byCls(it, true) }
            val pl = (0 until Cls.COUNT).sumOf { s.expect[it] * avg.byCls(it, false) }
            if (l + pl <= 0.0) continue
            unknown = true
            n += l; m += pl
            parts += MimicOdds.Part(s.tile, l, pl)
        } else {
            val rules = com.engineerclient.mappred.FloorRules.of(DungeonUtils.floor?.floorNumber ?: 0)
            val left = (rules?.let { it.w * it.h } ?: 36) - knownTiles.size
            if (left > 0) {
                unknown = true
                n += left * avg.perTileLocked; m += left * avg.perTilePlain
                parts += MimicOdds.Part(-1, left * avg.perTileLocked, left * avg.perTilePlain)
            }
        }
        val odds = MimicOdds.odds(parts, n, m)
        roomOdds = IdentityHashMap<DungeonRoom, Double>().also { r -> for ((k, v) in odds) if (k is DungeonRoom) r[k] = v }
        splitOdds = odds.filterKeys { it is Int && it >= 0 }.mapKeys { it.key as Int }
        ruledLocked = rl; ruledPlain = rp; totalLocked = n; totalPlain = m
        if (!unknown && !unplaced) {
            val left = odds.filterValues { it > 0.0 }.keys
            val only = left.singleOrNull() as? DungeonRoom
            if (only != null) only.name?.let { announce(it, null) }
        }
    }

    /** Sends the message once: the player who saw it, or by elimination the first linked name. */
    private fun announce(room: String, byMe: Boolean?) {
        if (sent) return
        val me = mc().user.name.lowercase()
        val sender = when (byMe) {
            true -> true
            false -> false
            null -> (PoseShare.linkedNames + me).minOrNull() == me
        }
        sent = true
        if (sender && message.value) sendCommand("pc Mimic is in $room")
    }

    private fun poolOf(room: DungeonRoom): Int? {
        val tiles = room.tiles.map { it.z * 6 + it.x }.distinct().toIntArray()
        return when (room.type) {
            RoomType.NORMAL, RoomType.RARE -> {
                val shape = MapView.shapeOf(tiles, 6) ?: return null
                if (shape == Shape.ONE) MapStats.POOL_1X1 else shape.ordinal
            }
            RoomType.PUZZLE -> MapStats.POOL_PUZZLE
            RoomType.CHAMPION -> MapStats.POOL_YELLOW
            RoomType.BLOOD -> MapStats.POOL_BLOOD
            RoomType.FAIRY -> MapStats.POOL_FAIRY
            RoomType.ENTRANCE -> MapStats.POOL_ENTRANCE
            else -> null
        }
    }

    /** For the name guesses: how much a room with [locked] and [plain] chest sites fits the Mimic not being found yet. */
    fun nameFactor(name: RoomName): Double {
        if (!steerNames.value || !active || found != null) return 1.0
        val avg = PoolSites.get() ?: return 1.0
        val s = avg.byName[name.name] ?: return 1.0
        val base = MimicOdds.notFound(totalLocked, totalPlain, ruledLocked.toDouble(), ruledPlain.toDouble())
        if (base <= 0.0) return 1.0
        return MimicOdds.notFound(totalLocked + s.first, totalPlain + s.second, ruledLocked.toDouble(), ruledPlain.toDouble()) / base
    }

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
            // a map's chests over its 36 tiles, from the share of each pool on the recorded maps
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
