package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.IVec2
import com.odtheking.odin.utils.customData
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/**
 * Legit Zpew: as you click an etherwarp or a blink (AOTE, AOTV, Hyperion), the camera goes to where the server will put
 * you, a round trip early. Only the view moves (ZpewCameraMixin); the player waits for the server's teleport.
 *
 * Every rule is alldata's (content/11-items): where it lands [TeleportLaw], the look it's cast with
 * (#items.teleport-look), clicks on blocks that act (#items.teleport-door-click), the rooms that refuse
 * (#items.room-refusals) and Wither Impact's 125 ms recast gate. Clicks waiting on the server are a
 * [ZpewQueue], so spam at high ping chains and corrects itself on each real landing.
 */
object Zpew : Module(
    name = "Legit Zpew",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Zero ping camera: your view jumps to where an etherwarp or AOTE/AOTV/Hyperion teleport lands as you click, before the server moves you.",
    key = null,
) {
    private val etherwarp by BooleanSetting("Etherwarp", true, desc = "Sneaking etherwarps.")
    private val aotv by BooleanSetting("AOTE / AOTV", true, desc = "Aspect of the End and Aspect of the Void teleports (unsneaked).")
    private val hyperion by BooleanSetting("Hyperion", true, desc = "Wither Impact teleports: Hyperion, Valkyrie, Astraea, Scylla, Necron's Blade with all three scrolls.")
    private val debug by BooleanSetting("Debug Log", false, desc = "Writes every click, teleport and camera jump to config/engineerclient/zpew-debug.log, for checking the predictions.")
    private val zeroPingSound by BooleanSetting("0 Ping Etherwarp Sound", true, desc = "Plays the etherwarp sound (Odin's Etherwarp sound when that is on) the moment your camera etherwarps, and not when the server's etherwarp arrives.")
    private val capture by BooleanSetting("Capture Misses", false, desc = "When a prediction is wrong (warps where the server refuses, lands elsewhere, or refuses where the server warps), saves the click and the blocks around it to config/engineerclient/zpew-misses.txt, to fix the law from.")

    private var logOut: java.io.Writer? = null
    private val startMs = System.currentTimeMillis()

    private fun log(line: String) {
        if (!debug) return
        try {
            val w = logOut ?: mc.gameDirectory.toPath().resolve("config/engineerclient/zpew-debug.log").let {
                java.nio.file.Files.createDirectories(it.parent)
                java.nio.file.Files.newBufferedWriter(it, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            }.also { logOut = it }
            w.write("${System.currentTimeMillis() - startMs} t=$ticks st=${serverTicks.get()} $line\n"); w.flush()
        } catch (_: Throwable) {}
    }

    private fun fmt(p: DoubleArray?) = p?.joinToString(",") { "%.3f".format(it) } ?: "-"
    private fun fmt(p: Vec3?) = p?.let { fmt(doubleArrayOf(it.x, it.y, it.z)) } ?: "-"

    /** [from]: where the chain had you when you clicked (the camera); [cells]: the blocks around the ray then, when Capture Misses is on. */
    private class Click(val ether: Boolean, val yaw: Float, val pitch: Float, val sneaking: Boolean, val distance: Double, val witherImpact: Boolean = false) {
        var from: DoubleArray? = null
        /** The eye the server casts from: its pose is the sneak before a change sent this tick (TeleportLaw.eye). */
        var eye: Double? = null
        var cells: String? = null
    }

    private val queue = ZpewQueue<Click>(::land)
    @Volatile private var camera: Vec3? = null
    private var ticks = 0L
    /** Server ticks as they reach us (counted on the network thread): they stop while the server lags. */
    private val serverTicks = java.util.concurrent.atomic.AtomicLong()
    private var lastWitherImpact = 0L
    /** The last sent sneak and the client tick it changed on (TeleportLaw.eye). */
    private var sentSneak = false
    private var sneakChangedAt = Long.MIN_VALUE / 2

    /** The last move packet's look: a block click is cast with it. */
    private var moveYaw = Float.NaN
    private var movePitch = Float.NaN
    /** This tick's block click: null, false (an ordinary block), or true (a block that acts, so no teleport). */
    private var blockClick: Boolean? = null

    private val WITHER_BLADES = setOf("NECRON_BLADE", "SCYLLA", "HYPERION", "VALKYRIE", "ASTRAEA")
    private val ACTS = Regex("^(chest|trapped_chest|ender_chest|lever)$|_button$|_sign$|_fence_gate$|(_head|_skull)$")
    private val NO_TELEPORT = setOf("Teleport Maze", "Boulder")
    /** A position packet this close to where you were is the server syncing you, not a teleport. */
    private const val NEAR = 1.0
    private const val TAB = "\t"
    private const val NL = "\n"
    private val REFUSALS = listOf(
        "You cannot use abilities in this room!",
        "You cannot do that in this room!",
        "A mystical force in this room prevents you from using that ability!",
    )

    /** Feet; ZpewCameraMixin adds the eye height. */
    @JvmStatic fun cameraPos(): Vec3? = if (enabled) camera else null

    /** ZpewTeleportMixin: the game has just moved you for a server teleport. */
    /** Where you were just before the server's position packet ([ZpewTeleportMixin] HEAD), or null. */
    private var before: DoubleArray? = null

    @JvmStatic fun beforeTeleport(x: Double, y: Double, z: Double) { before = doubleArrayOf(x, y, z) }

    @JvmStatic fun onTeleportApplied(x: Double, y: Double, z: Double) {
        val waited = queue.size
        val was = before.also { before = null }
        // Hypixel also sends position packets that leave you about where you are (often just before a teleport's landing).
        // Unless one is a waiting click's landing it answers nothing: taking it for the oldest click made the real landing look unasked.
        if (was != null && !queue.isEmpty && !queue.matches(doubleArrayOf(x, y, z)) &&
            (0..2).sumOf { (doubleArrayOf(x, y, z)[it] - was[it]).let { d -> d * d } } < NEAR * NEAR) {
            log("SYNC to=${fmt(doubleArrayOf(x, y, z))} was=${fmt(was)} waiting=$waited")
            queue.synced(doubleArrayOf(x, y, z))
            sync("server-sync")
            return
        }
        val oldest = queue.oldestCounting()
        if (queue.teleport(doubleArrayOf(x, y, z), serverTicks.get())) {
            if (queue.lastMatch < 0 && oldest != null) miss(if (queue.lastExpected == null) "law-refused" else "wrong-landing", oldest, queue.lastExpected, doubleArrayOf(x, y, z))
            val e = queue.lastExpected
            val off = e?.let { Math.sqrt((it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) + (it[2] - z) * (it[2] - z)) }
            log("TP to=${fmt(doubleArrayOf(x, y, z))} expected=${fmt(e)} off=${off?.let { "%.3f".format(it) } ?: "-"} match=${queue.lastMatch} waiting=$waited left=${queue.size}")
            sync("tp")
        } else log("TP-UNASKED to=${fmt(doubleArrayOf(x, y, z))}")
    }

    private fun sync(why: String = "") {
        val old = camera
        camera = queue.camera?.let { Vec3(it[0], it[1], it[2]) }
        if (debug) {
            // where the view was and is: the player when there's no camera
            val p = mc.player?.position()
            val a = old ?: p; val b = camera ?: p
            val jump = if (a != null && b != null) a.distanceTo(b) else 0.0
            log("CAM $why ${fmt(old)} -> ${fmt(camera)} player=${fmt(p)} jump=${"%.3f".format(jump)}")
        }
    }

    /**
     * Capture Misses: one line per wrong prediction, tab-separated: kind, ether|blink, sneaking, distance, from, yaw pitch, predicted,
     * actual, then the blocks around the ray as "x y z id top state" joined by ';'. The law's tests read these (test/resources/zpew-misses).
     */
    private fun miss(kind: String, c: Click, predicted: DoubleArray?, actual: DoubleArray?) {
        if (!capture) return
        val from = c.from ?: return
        val cells = c.cells ?: return
        try {
            val f = mc.gameDirectory.toPath().resolve("config/engineerclient/zpew-misses.txt")
            java.nio.file.Files.createDirectories(f.parent)
            val p = { a: DoubleArray? -> a?.joinToString(",") { it.toString() } ?: "-" }
            val line = listOf(kind, if (c.ether) "ether" else "blink", c.sneaking, c.distance, p(from), "${c.yaw} ${c.pitch}", p(predicted), p(actual), cells).joinToString(TAB)
            java.nio.file.Files.writeString(f, line + NL, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            mc.player?.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7Legit Zpew: saved a $kind miss to zpew-misses.txt"))
        } catch (_: Throwable) {}
    }

    /** Every block within a block of the ray (and up to 3 above it), as the law sees them plus the full state. */
    private fun snapshot(from: DoubleArray, c: Click): String? {
        val level = mc.level ?: return null
        val look = TeleportLaw.look(c.yaw, c.pitch)
        val ey = from[1] + if (c.ether || c.sneaking) TeleportLaw.SNEAK_EYE else TeleportLaw.STAND_EYE
        val seen = LinkedHashSet<Long>()
        val out = StringBuilder()
        val pos = BlockPos.MutableBlockPos()
        val cells = LevelCells(level)
        var t = 0.0
        while (t <= c.distance + 1.5) {
            val bx = floor(from[0] + look[0] * t).toInt(); val by = floor(ey + look[1] * t).toInt(); val bz = floor(from[2] + look[2] * t).toInt()
            for (dx in -1..1) for (dz in -1..1) for (dy in -2..3) {
                val x = bx + dx; val y = by + dy; val z = bz + dz
                if (!seen.add(BlockPos.asLong(x, y, z))) continue
                val state = level.getBlockState(pos.set(x, y, z))
                if (state.isAir) continue
                if (out.isNotEmpty()) out.append(';')
                out.append("$x $y $z ${cells.id(x, y, z)} ${cells.top(x, y, z)} ${state.toString().substringAfter('{').substringBefore('}')}${state.toString().substringAfter('}', "")}")
            }
            t += 0.25
        }
        return out.toString()
    }

    private fun reset() {
        queue.clear()
        camera = null
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private class LevelCells(val level: ClientLevel) : TeleportLaw.Cells {
        private val pos = BlockPos.MutableBlockPos()
        override fun id(x: Int, y: Int, z: Int): String = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.set(x, y, z)).block).path
        override fun top(x: Int, y: Int, z: Int): Double {
            val shape = level.getBlockState(pos.set(x, y, z)).getCollisionShape(level, pos)
            return if (shape.isEmpty) 0.0 else shape.max(Direction.Axis.Y)
        }
    }

    /** Where [c] lands cast from [from], or null when the server won't move you. */
    private fun land(from: DoubleArray, c: Click): DoubleArray? {
        val cells = LevelCells(mc.level ?: return null)
        if (nearRoom(from) { it.type == RoomType.TRAP || it.data?.name in NO_TELEPORT }) return null
        val to = if (c.ether) TeleportLaw.etherwarp(from[0], from[1], from[2], c.yaw, c.pitch, c.distance, cells, c.eye ?: TeleportLaw.SNEAK_EYE)
        else TeleportLaw.blink(from[0], from[1], from[2], c.yaw, c.pitch, c.sneaking, c.distance.toInt(), cells, c.eye)
        return to?.takeUnless { nearRoom(it) { r -> r.data?.name in NO_TELEPORT } }
    }

    /** In, or a block from, a scanned room [test] picks: the server's room reaches into the doorway. Tile t spans -200 + 32t, 31 wide. */
    private fun nearRoom(p: DoubleArray, test: (DungeonRoom) -> Boolean): Boolean {
        val tiles = HashSet<Long>()
        for (dx in -1..1) for (dz in -1..1)
            tiles += IVec2(Math.floorDiv(floor(p[0]).toInt() + dx + 200, 32), Math.floorDiv(floor(p[2]).toInt() + dz + 200, 32)).data
        return DungeonScan.rooms.any { room -> test(room) && room.tiles.any { it.data in tiles } }
    }

    /** The click as the server will cast it, or null when it isn't a teleport we show. */
    private fun click(packet: ServerboundUseItemPacket): Click? {
        val p = mc.player ?: return null
        if (blockClick == true || (DungeonUtils.inBoss && DungeonUtils.isFloor(7))) return null
        val stack = p.getItemInHand(packet.hand)
        val sneaking = p.lastSentInput.shift()
        val (yaw, pitch) = if (blockClick == false && !moveYaw.isNaN()) moveYaw to movePitch else packet.yRot to packet.xRot
        // a sneak change sent this tick reaches the server with the click: it casts from the pose before it
        val eye = TeleportLaw.eye(sneaking, ticks - sneakChangedAt <= 1)
        if (sneaking) stack.isEtherwarpItem()?.let { return if (etherwarp) Click(true, yaw, pitch, true, Etherwarp.getEtherwarpDistance(it)).also { c -> c.eye = eye } else null }
        val id = stack.itemId
        val distance = when {
            id == "ASPECT_OF_THE_END" || id == "ASPECT_OF_THE_VOID" -> if (aotv) 8 + stack.customData.getInt("tuned_transmission").orElse(0) else return null
            id in WITHER_BLADES && stack.customData.getListOrEmpty("ability_scroll").size == 3 -> if (hyperion) 10 else return null
            else -> return null
        }
        return Click(false, yaw, pitch, sneaking, distance.toDouble(), id in WITHER_BLADES).also { it.eye = eye }
    }

    init {
        queue.onDrop = { c ->
            // the law warped you but the server never did: the screenshot case
            val from = c.from
            if (capture && from != null) land(from, c)?.let { miss("server-refused", c, it, null) }
        }

        on<LevelEvent.Load> { reset(); EtherSound.clear() }

        on<MessageEvent> {
            if (!queue.isEmpty && REFUSALS.any { message.contains(it) }) { log("REFUSED $message"); queue.refuse(); sync("refuse") }
            if (!queue.isEmpty && message.contains("There are blocks in the way!")) {
                val mark = { log("BLOCKED"); queue.blocked(serverTicks.get()) }
                if (mc.isSameThread) mark() else mc.execute { mark() }
            }
        }

        on<TickEvent.Server> { serverTicks.incrementAndGet() }

        on<TickEvent.End> {
            blockClick = null
            ticks++
            if (queue.expire(serverTicks.get(), ticks)) sync("expire")
            queue.oldestCounting()?.let { c ->
                if (queue.settleBlocked(serverTicks.get()) { if (it.witherImpact) 4 else 0 }) {
                    // the law warped you but the server said blocks in the way: a miss to learn from
                    c.from?.let { from -> if (capture) land(from, c)?.let { miss("server-refused", c, it, null) } }
                    sync("blocked")
                }
            }
        }

        onSend<net.minecraft.network.protocol.game.ServerboundPlayerInputPacket> {
            val s = input().shift()
            if (s != sentSneak) { sentSneak = s; sneakChangedAt = ticks }
        }

        onSend<ServerboundMovePlayerPacket> {
            if (hasRotation()) { moveYaw = getYRot(0f); movePitch = getXRot(0f) }
        }

        onSend<ServerboundUseItemOnPacket> {
            val level = mc.level ?: return@onSend
            blockClick = ACTS.containsMatchIn(BuiltInRegistries.BLOCK.getKey(level.getBlockState(hitResult.blockPos).block).path)
        }

        onSend<ServerboundUseItemPacket> {
            val c = click(this) ?: return@onSend
            val p = mc.player ?: return@onSend
            var counts = true
            if (c.witherImpact) {
                val now = System.currentTimeMillis()
                counts = now - lastWitherImpact >= 125
                if (counts) lastWitherImpact = now
            }
            if (capture && counts) {
                val from = camera?.let { doubleArrayOf(it.x, it.y, it.z) } ?: doubleArrayOf(p.x, p.y, p.z)
                c.from = from
                c.cells = snapshot(from, c)
            }
            if (zeroPingSound && counts && c.ether) {
                val from = camera?.let { doubleArrayOf(it.x, it.y, it.z) } ?: doubleArrayOf(p.x, p.y, p.z)
                if (land(from, c) != null) EtherSound.played()
            }
            queue.click(c, serverTicks.get(), doubleArrayOf(p.x, p.y, p.z), counts, ticks)
            log("CLICK ${if (c.ether) "ether" else if (c.witherImpact) "hype" else "aotv"} yaw=${c.yaw} pitch=${c.pitch} sneak=${c.sneaking} dist=${c.distance} counts=$counts block=$blockClick player=${fmt(doubleArrayOf(p.x, p.y, p.z))} ping=${com.odtheking.odin.utils.ServerUtils.currentPing}")
            sync("click")
        }
    }
}
