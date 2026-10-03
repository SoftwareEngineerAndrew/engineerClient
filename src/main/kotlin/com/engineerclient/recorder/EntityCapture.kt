package com.engineerclient.recorder

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.entity.EntityRenderDispatcher
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.phys.Vec3
import java.util.Optional

/**
 * Every entity as the client holds it, on the game thread: what packets alone cannot say.
 *
 *  - espawn: an entity entering the client level (from a packet or made by the client), in full,
 *    then once more at the tick's end, after the rest of its spawn bundle (data, equipment) applied.
 *  - ent: per tick, a row for every rendered entity whose numbers changed: position, previous
 *    position (render lerps between them), rotations, motion, the codec base, health, interpolation.
 *  - emove: each move the client applied (moveOrInterpolateTo, and the handlers' direct snaps and
 *    teleports), whatever asked for it, and whether it snaps (items, arrows) or interpolates.
 *  - egone: removal with its reason; edata / eeq: data and equipment after the game applied them.
 *  - kfent: at each keyframe, every entity in full, spread over ticks (about a millisecond a tick); it also seeds
 *    [EntityMirror] with the bases of entities that spawned before the recording.
 *  - drawn: which entities were actually rendered this tick, with name tags and outlines as drawn.
 *
 * Everything is frozen to a string here, on the thread that owns the entities. Nothing runs when
 * the recorder is off: each hook checks a flag first.
 */
object EntityCapture {

    /** Hot-path switches, refreshed every tick (the move and render hooks fire thousands of times a second). */
    @JvmField @Volatile var movesOn = false
    @JvmField @Volatile var drawnOn = false

    private val rows = EntRows()
    private val drawn = DrawnTracker<Component>()
    /** This tick's applied moves (game thread), as numbers: formatted on the writer thread. */
    private val moves = MoveBuffer()
    /** This tick's changed entity rows (game thread): ids and [EntRows.WIDTH] numbers each. */
    private var entIds = IntArray(256)
    private var entData = DoubleArray(256 * EntRows.WIDTH)
    private val resnap = ArrayList<Entity>()
    private var lastSession: Any? = null
    /** The id ClientLevel.removeEntity is removing right now: its unload event is the same removal. */
    private var levelRemoving = Int.MIN_VALUE

    // Keyframe in progress: the entities still to write, a few each tick (a time budget, at most 100 a line).
    private var kfList: List<Entity> = emptyList()
    private var kfIndex = 0
    private var kfChunk = 0
    private var kfId = 0L
    private const val KF_PER_TICK = 100
    private const val KF_MIN_PER_TICK = 8
    private const val KF_BUDGET_NS = 1_000_000L

    fun install() {
        ClientEntityEvents.ENTITY_LOAD.register { e, _ -> EngineerClient.safely("recorder espawn") { onLoad(e) } }
        ClientEntityEvents.ENTITY_UNLOAD.register { e, _ -> EngineerClient.safely("recorder egone") { onUnload(e) } }
        Rec.onKeyframe("entities") { startKeyframe() }
        EventBus.subscribe(this)
    }

    init {
        on<TickEvent.End> { EngineerClient.safely("recorder entities") { onTick() } }
        on<EntityEvent.SetData>(priority = Int.MIN_VALUE) {
            if (!Rec.active) return@on
            EngineerClient.safely("recorder edata") {
                val e = entity
                val sb = StringBuilder(128).append("\"id\":").append(e.id).append(",\"etype\":").append(RecorderFiles.q(typeId(e)))
                sb.append(",\"vals\":"); PacketJson.write(sb, synchedDataValues, 0)
                Rec.emit("edata", sb.toString())
            }
        }
        on<EntityEvent.SetItemSlot>(priority = Int.MIN_VALUE) {
            if (!Rec.active) return@on
            EngineerClient.safely("recorder eeq") {
                Rec.emit("eeq", "\"id\":${entity.id},\"slot\":${RecorderFiles.q(slot.getName())},\"item\":${RichJson.itemNow(stack)}")
            }
        }
    }

    // ------------------------------------------------------------------ spawn and removal

    private fun onLoad(e: Entity) {
        if (!Rec.active) return
        val src = if (EntityMirror.packetSpawned.remove(e.id)) "packet" else "client"
        val sb = StringBuilder(512)
        entity(sb, e, full = false)
        sb.append(",\"src\":\"").append(src).append("\",\"at\":\"load\"")
        Rec.emit("espawn", sb.toString())
        resnap.add(e)
    }

    /** ClientLevel.removeEntity (EntityRemoveTapMixin), before the entity is gone: game thread. */
    @JvmStatic
    fun onLevelRemove(level: ClientLevel, id: Int, reason: Entity.RemovalReason?) {
        if (!Rec.active) return
        try {
            val e = level.getEntity(id)
            levelRemoving = if (e != null) id else Int.MIN_VALUE
            forget(id)
            Rec.emit("egone", "\"id\":$id,\"reason\":${RecorderFiles.q(reason?.name)},\"src\":\"level\",\"found\":${e != null}" +
                (e?.let { ",\"etype\":${RecorderFiles.q(typeId(it))}" } ?: ""))
        } catch (t: Throwable) {
            EngineerClient.logger.error("[ec] recorder egone failed", t)
        }
    }

    private fun onUnload(e: Entity) {
        if (!Rec.active) return
        val id = e.id
        if (id == levelRemoving) { levelRemoving = Int.MIN_VALUE; return }
        forget(id)
        Rec.emit("egone", "\"id\":$id,\"reason\":${RecorderFiles.q(e.removalReason?.name)},\"src\":\"unload\",\"etype\":${RecorderFiles.q(typeId(e))}")
    }

    private fun forget(id: Int) {
        rows.remove(id)
        drawn.forget(id)
    }

    // ------------------------------------------------------------------ applied moves

    /**
     * Entity.moveOrInterpolateTo (EntityMoveTapMixin, HEAD): every move the client applies, from any
     * packet or mod. A row appended to one buffer, flushed as a single emove line at the tick's end.
     */
    @JvmStatic
    fun onMove(e: Entity, pos: Optional<Vec3>, yRot: Optional<Float>, xRot: Optional<Float>) {
        if (!movesOn) return
        try {
            val p = pos.orElse(null); val y = yRot.orElse(null); val x = xRot.orElse(null)
            var flags = 0
            if (p != null) flags = flags or MoveBuffer.POS
            if (y != null) flags = flags or MoveBuffer.YROT
            if (x != null) flags = flags or MoveBuffer.XROT
            if (e.interpolation != null) flags = flags or MoveBuffer.INTERP
            record(e.id, p?.x ?: 0.0, p?.y ?: 0.0, p?.z ?: 0.0, y ?: 0f, x ?: 0f, flags)
        } catch (t: Throwable) {
            EngineerClient.logger.error("[ec] recorder emove failed", t)
        }
    }

    /**
     * A move a packet handler applied without moveOrInterpolateTo (EntitySnapTapMixin, after it): a
     * far or non-ticking position sync's snap, a teleport set directly. Where it landed, snap flag on.
     */
    @JvmStatic
    fun onSnap(e: Entity) {
        if (!movesOn) return
        try {
            record(e.id, e.x, e.y, e.z, e.yRot, e.xRot, MoveBuffer.POS or MoveBuffer.YROT or MoveBuffer.XROT or MoveBuffer.SNAP)
        } catch (t: Throwable) {
            EngineerClient.logger.error("[ec] recorder emove failed", t)
        }
    }

    private fun record(id: Int, x: Double, y: Double, z: Double, yRot: Float, xRot: Float, flags: Int) {
        if (Thread.currentThread() !== Rec.gameThread) {
            // Not the game thread (another mod): its own line, never the shared buffer.
            val one = MoveBuffer().also { it.add(id, x, y, z, yRot, xRot, flags) }
            val sb = StringBuilder(96).append("\"d\":[")
            one.appendRows(sb)
            Rec.emit("emove", sb.append("],\"thread\":").append(RecorderFiles.q(Thread.currentThread().name)).toString())
            return
        }
        moves.add(id, x, y, z, yRot, xRot, flags)
    }

    // ------------------------------------------------------------------ rendered set

    /** EntityRenderDispatcher.extractEntity (EntityRenderTapMixin, RETURN): an entity about to be drawn. */
    @JvmStatic
    fun onExtract(dispatcher: EntityRenderDispatcher, e: Entity, state: EntityRenderState?) {
        if (!drawnOn || state == null) return
        try {
            // Other passes (the POV preview) render from another camera; only the player's view counts.
            val cam = dispatcher.camera ?: return
            if (cam.entity() !== EngineerClient.mc.player) return
            drawn.add(e.id, state.nameTag, state.outlineColor, state.appearsGlowing())
        } catch (t: Throwable) {
            EngineerClient.logger.error("[ec] recorder drawn failed", t)
        }
    }

    // ------------------------------------------------------------------ the tick

    private fun onTick() {
        val active = Rec.active
        movesOn = active && DungeonRecorder.entityTicks
        drawnOn = active && DungeonRecorder.renderedEntities
        if (!active) { clearAll(); return }
        val s = Rec.session
        if (s !== lastSession) { clearAll(); lastSession = s }

        if (moves.count > 0) {
            // The numbers are copied out here; turning them into text is the writer's job.
            val taken = moves.take()
            Rec.emitLazy("emove", 32 + taken.count * 90, "emove") {
                val sb = StringBuilder(taken.count * 90 + 16).append("\"d\":[")
                taken.appendRows(sb)
                sb.append(']').toString()
            }
        }

        if (resnap.isNotEmpty()) {
            for (e in resnap) {
                if (e.isRemoved) continue
                val sb = StringBuilder(512)
                entity(sb, e, full = false)
                sb.append(",\"at\":\"tick\"")
                Rec.emit("espawn", sb.toString())
            }
            resnap.clear()
        }

        if (kfIndex < kfList.size) keyframeChunk()

        val level = EngineerClient.mc.level ?: return
        if (DungeonRecorder.entityTicks) entityRows(level)
        if (DungeonRecorder.renderedEntities) drawnLine()
    }

    private fun clearAll() {
        rows.clear(); drawn.clear(); resnap.clear()
        moves.clear()
        kfList = emptyList(); kfIndex = 0
    }

    private val row = DoubleArray(EntRows.WIDTH)

    /**
     * One ent line for all rendered entities whose numbers moved since the last row written. The
     * comparison is here; the changed rows' numbers are copied out and written as text on the writer thread.
     */
    private fun entityRows(level: ClientLevel) {
        var n = 0
        val w = EntRows.WIDTH
        for (e in level.entitiesForRendering()) {
            val id = e.id
            try { fill(e, row) } catch (t: Throwable) { continue }
            if (!rows.changed(id, row)) continue
            if (n == entIds.size) { entIds = entIds.copyOf(n * 2); entData = entData.copyOf(n * 2 * w) }
            entIds[n] = id
            row.copyInto(entData, n * w)
            n++
        }
        if (n == 0) return
        val ids = entIds.copyOf(n)
        val data = entData.copyOf(n * w)
        if (entIds.size > 4096) { entIds = IntArray(256); entData = DoubleArray(256 * w) }
        Rec.emitLazy("ent", 32 + n * 220, "ent") {
            val sb = StringBuilder(n * 220 + 8).append("\"d\":[")
            for (k in 0 until n) { if (k > 0) sb.append(','); EntRows.append(sb, ids[k], data, k * w) }
            sb.append(']').toString()
        }
    }

    private fun fill(e: Entity, r: DoubleArray) {
        val living = e as? LivingEntity
        r[0] = e.x; r[1] = e.y; r[2] = e.z
        r[3] = e.xo; r[4] = e.yo; r[5] = e.zo
        r[6] = e.yRot.toDouble(); r[7] = e.xRot.toDouble()
        r[8] = e.yHeadRot.toDouble(); r[9] = living?.yBodyRot?.toDouble() ?: Double.NaN
        val v = e.deltaMovement
        r[10] = v.x; r[11] = v.y; r[12] = v.z
        r[13] = if (e.onGround()) 1.0 else 0.0
        val interp = e.interpolation
        val active = e.isInterpolating
        r[14] = if (active) 1.0 else 0.0
        val b = e.positionCodec.base
        r[15] = b.x; r[16] = b.y; r[17] = b.z
        r[18] = living?.health?.toDouble() ?: Double.NaN
        val target = if (active && interp != null) interp.position() else null
        r[19] = target?.x ?: Double.NaN; r[20] = target?.y ?: Double.NaN; r[21] = target?.z ?: Double.NaN
    }

    private fun drawnLine() {
        val sb = StringBuilder(512)
        val ids = drawn.flush { id, c ->
            if (sb.isEmpty()) sb.append(",\"tags\":[") else sb.append(',')
            sb.append('[').append(id).append(',')
            if (c == null) sb.append("null") else RichJson.component(sb, c)
            sb.append(']')
        } ?: return
        if (sb.isNotEmpty()) sb.append(']')
        val outlines = StringBuilder()
        drawn.flushOutlines { id, rgb, glow ->
            outlines.append(if (outlines.isEmpty()) ",\"outline\":[" else ",").append('[').append(id).append(',').append(rgb).append(',').append(glow).append(']')
        }
        if (outlines.isNotEmpty()) outlines.append(']')
        val head = StringBuilder(ids.size * 6 + 16).append("\"ids\":[")
        ids.forEachIndexed { i, id -> if (i > 0) head.append(','); head.append(id) }
        Rec.emit("drawn", head.append(']').append(sb).append(outlines).toString())
    }

    // ------------------------------------------------------------------ keyframes

    /** Keyframe contributor: every entity the level holds, written over the next ticks. */
    private fun startKeyframe() {
        // An unfinished one is closed (the entities it did not reach listed as "cut"; the new one
        // has them all again) rather than written out at once, which could stall the tick.
        if (kfIndex < kfList.size) {
            val cut = (kfIndex until kfList.size).joinToString(",", "[", "]") { kfList[it].id.toString() }
            Rec.emit("kfent", "\"kf\":$kfId,\"i\":$kfChunk,\"total\":${kfList.size},\"last\":true,\"d\":[],\"cut\":$cut")
        }
        val level = EngineerClient.mc.level ?: return
        lastSession = Rec.session
        kfList = level.entitiesForRendering().toList()
        kfIndex = 0
        kfChunk = 0
        kfId = Rec.keyframeId
        // The next tick writes every row in full and the drawn set's tags and outlines again.
        rows.clear(); drawn.clear()
        if (kfList.isEmpty()) Rec.emit("kfent", "\"kf\":$kfId,\"i\":0,\"total\":0,\"last\":true,\"d\":[]")
        else keyframeChunk()
    }

    /** The next line of a keyframe: entities until about a millisecond is spent (at least a few, at most [KF_PER_TICK]). */
    private fun keyframeChunk() {
        val i = kfChunk++
        val t0 = System.nanoTime()
        val sb = StringBuilder(32 * 1024).append("\"kf\":").append(kfId).append(",\"i\":").append(i).append(",\"total\":").append(kfList.size).append(",\"d\":[")
        val skipped = IntArrayList()
        var n = 0
        var k = kfIndex
        while (k < kfList.size && k - kfIndex < KF_PER_TICK && (k - kfIndex < KF_MIN_PER_TICK || System.nanoTime() - t0 < KF_BUDGET_NS)) {
            val e = kfList[k++]
            if (e.isRemoved) { skipped.add(e.id); continue }
            val mark = sb.length
            try {
                if (n > 0) sb.append(',')
                sb.append('{'); entity(sb, e, full = true); sb.append('}')
                n++
                val b = e.positionCodec.base
                EntityMirror.seed(EntityMirror.Seed(e.id, typeId(e), b.x, b.y, b.z))
            } catch (t: Throwable) {
                sb.setLength(mark); skipped.add(e.id)
            }
        }
        sb.append(']')
        if (!skipped.isEmpty) sb.append(",\"skipped\":").append(skipped.toIntArray().joinToString(",", "[", "]"))
        kfIndex = k
        val last = kfIndex >= kfList.size
        sb.append(",\"last\":").append(last)
        if (last) { kfList = emptyList(); kfIndex = 0 }
        Rec.emit("kfent", sb.toString())
    }

    // ------------------------------------------------------------------ one entity in full

    private fun typeId(e: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()

    /**
     * The members describing [e] (no braces), starting with "id". [full] adds what only keyframes
     * carry: effects and attributes. Each member is guarded on its own, so one odd getter (a modded
     * entity) costs only that member, written as an error.
     */
    private fun entity(sb: StringBuilder, e: Entity, full: Boolean) {
        sb.append("\"id\":").append(e.id)
        RichJson.member(sb, "uuid") { PacketJson.str(sb, e.uuid.toString()); true }
        RichJson.member(sb, "type") { PacketJson.str(sb, typeId(e)); true }
        RichJson.member(sb, "pos") { vec(sb, e.x, e.y, e.z); true }
        RichJson.member(sb, "base") { val b = e.positionCodec.base; vec(sb, b.x, b.y, b.z); true }
        val living = e as? LivingEntity
        RichJson.member(sb, "rot") {
            sb.append('['); PacketJson.num(sb, e.yRot); sb.append(','); PacketJson.num(sb, e.xRot); sb.append(','); PacketJson.num(sb, e.yHeadRot); sb.append(',')
            if (living == null) sb.append("null") else PacketJson.num(sb, living.yBodyRot)
            sb.append(']'); true
        }
        RichJson.member(sb, "vel") { val v = e.deltaMovement; vec(sb, v.x, v.y, v.z); true }
        RichJson.member(sb, "bb") {
            val b = e.boundingBox
            sb.append('['); PacketJson.num(sb, b.minX); sb.append(','); PacketJson.num(sb, b.minY); sb.append(','); PacketJson.num(sb, b.minZ)
            sb.append(','); PacketJson.num(sb, b.maxX); sb.append(','); PacketJson.num(sb, b.maxY); sb.append(','); PacketJson.num(sb, b.maxZ); sb.append(']'); true
        }
        RichJson.member(sb, "pose") { PacketJson.str(sb, e.pose.name); true }
        RichJson.member(sb, "name") { val c = e.customName; if (c == null) sb.append("null") else RichJson.component(sb, c); true }
        RichJson.member(sb, "display") { RichJson.component(sb, e.displayName); true }
        RichJson.member(sb, "data") { val d = e.entityData.nonDefaultValues; if (d == null) sb.append("null") else PacketJson.write(sb, d, 0); true }
        if (living != null) RichJson.member(sb, "eq") {
            sb.append('{')
            var first = true
            for (slot in EquipmentSlot.values()) {
                val stack = living.getItemBySlot(slot)
                if (stack.isEmpty) continue
                if (!first) sb.append(',')
                first = false
                PacketJson.str(sb, slot.getName()); sb.append(':'); RichJson.item(sb, stack)
            }
            sb.append('}'); true
        }
        RichJson.member(sb, "vehicle") { val v = e.vehicle; if (v == null) sb.append("null") else sb.append(v.id); true }
        RichJson.member(sb, "pass") { sb.append(e.passengers.joinToString(",", "[", "]") { it.id.toString() }); true }
        if (living != null) {
            RichJson.member(sb, "hp") { PacketJson.num(sb, living.health); true }
            RichJson.member(sb, "maxHp") { PacketJson.num(sb, living.maxHealth); true }
            RichJson.member(sb, "hurt") { sb.append('[').append(living.hurtTime).append(',').append(living.deathTime).append(']'); true }
        }
        RichJson.member(sb, "alive") { sb.append(e.isAlive); true }
        RichJson.member(sb, "flags") {
            sb.append("{\"glow\":").append(e.isCurrentlyGlowing).append(",\"invis\":").append(e.isInvisible)
                .append(",\"nameVisible\":").append(e.isCustomNameVisible).append(",\"silent\":").append(e.isSilent)
                .append(",\"noGravity\":").append(e.isNoGravity).append('}'); true
        }
        RichJson.member(sb, "age") { sb.append(e.tickCount); true }
        when (e) {
            is ItemFrame -> RichJson.member(sb, "extra") { sb.append("{\"item\":"); RichJson.item(sb, e.item); sb.append(",\"rotation\":").append(e.rotation).append('}'); true }
            is FallingBlockEntity -> RichJson.member(sb, "extra") { sb.append("{\"state\":"); PacketJson.str(sb, BlockStateParser.serialize(e.blockState)); sb.append('}'); true }
        }
        if (full && living != null) {
            RichJson.member(sb, "effects") {
                val fx = living.activeEffects
                if (fx.isEmpty()) false else {
                    sb.append('[')
                    fx.forEachIndexed { i, m ->
                        if (i > 0) sb.append(',')
                        sb.append('['); PacketJson.write(sb, m.effect, 0); sb.append(',').append(m.amplifier).append(',').append(m.duration)
                            .append(',').append(m.isAmbient).append(',').append(m.isVisible).append(',').append(m.showIcon()).append(']')
                    }
                    sb.append(']'); true
                }
            }
            RichJson.member(sb, "attrs") {
                sb.append('[')
                living.attributes.syncableAttributes.forEachIndexed { i, a ->
                    if (i > 0) sb.append(',')
                    sb.append('['); PacketJson.write(sb, a.attribute, 0); sb.append(','); PacketJson.num(sb, a.baseValue); sb.append(','); PacketJson.num(sb, a.value)
                    sb.append(','); PacketJson.write(sb, a.modifiers, 0); sb.append(']')
                }
                sb.append(']'); true
            }
        }
    }

    private fun vec(sb: StringBuilder, x: Double, y: Double, z: Double) {
        sb.append('['); PacketJson.num(sb, x); sb.append(','); PacketJson.num(sb, y); sb.append(','); PacketJson.num(sb, z); sb.append(']')
    }
}

/**
 * The per-tick entity rows: the last row written per id, so only entities whose numbers changed get
 * a new one. Compared bit for bit (NaN equals NaN, -0.0 differs from 0.0), never rounded.
 *
 * Row: `[id, x,y,z, xo,yo,zo, yRot,xRot,yHeadRot,yBodyRot, vx,vy,vz, onGround, interp, baseX,baseY,baseZ, hp]`
 * plus `ix,iy,iz` (the interpolation target) when interp is 1. Not-living entities have null body
 * rotation and hp.
 */
internal class EntRows {
    private val cache = Int2ObjectOpenHashMap<DoubleArray>()

    /** True (and remembered) when [r] differs from the last row of [id]. */
    fun changed(id: Int, r: DoubleArray): Boolean {
        val last = cache.get(id)
        if (last != null) {
            var same = true
            for (i in r.indices) if (java.lang.Double.doubleToRawLongBits(last[i]) != java.lang.Double.doubleToRawLongBits(r[i])) { same = false; break }
            if (same) return false
            r.copyInto(last)
        } else cache.put(id, r.copyOf())
        return true
    }

    fun remove(id: Int) { cache.remove(id) }
    fun clear() = cache.clear()
    val size: Int get() = cache.size

    companion object {
        const val WIDTH = 22
        /** Columns that hold floats (rotations, health): written as the float they came from. */
        private val FLOAT_COLS = booleanArrayOf(false, false, false, false, false, false, true, true, true, true, false, false, false, false, false, false, false, false, true)

        /** Row [id]'s numbers, [r] from [off] on (several rows may share one array). */
        fun append(sb: StringBuilder, id: Int, r: DoubleArray, off: Int = 0) {
            sb.append('[').append(id)
            for (i in 0 until 19) {
                sb.append(',')
                val v = r[off + i]
                when {
                    i == 13 || i == 14 -> sb.append(v.toInt())
                    v.isNaN() -> sb.append("null")
                    FLOAT_COLS[i] -> PacketJson.num(sb, v.toFloat())
                    else -> PacketJson.num(sb, v)
                }
            }
            if (r[off + 14] == 1.0 && !r[off + 19].isNaN()) {
                for (i in 19 until 22) { sb.append(','); PacketJson.num(sb, r[off + i]) }
            }
            sb.append(']')
        }
    }
}

/**
 * The entities drawn since the last tick (several frames per tick are one set), and their name tags
 * and outlines, written only when they change. A tag or outline stays known while the entity is
 * out of view; drawn again without one, it is written as null / NO_OUTLINE.
 */
internal class DrawnTracker<T : Any> {
    private val ids = IntArrayList()
    private val seen = IntOpenHashSet()
    private val tagNow = Int2ObjectOpenHashMap<T>()
    private val outlineNow = Int2LongOpenHashMap()
    private val tagLast = Int2ObjectOpenHashMap<T>()
    private val outlineLast = Int2LongOpenHashMap().also { it.defaultReturnValue(Long.MIN_VALUE) }

    /** One draw of [id]; the last draw in a tick wins. */
    fun add(id: Int, tag: T?, outline: Int, glowing: Boolean) {
        if (seen.add(id)) ids.add(id)
        if (tag != null) tagNow.put(id, tag) else tagNow.remove(id)
        outlineNow.put(id, (outline.toLong() shl 1) or (if (glowing) 1L else 0L))
    }

    /** The ids drawn since the last flush (null: none), reporting changed tags; call [flushOutlines] after. */
    fun flush(onTag: (Int, T?) -> Unit): IntArray? {
        if (ids.isEmpty) return null
        val out = ids.toIntArray()
        for (id in out) {
            val now = tagNow.get(id)
            val last = tagLast.get(id)
            if (now != null) { if (now != last) { tagLast.put(id, now); onTag(id, now) } }
            else if (last != null) { tagLast.remove(id); onTag(id, null) }
        }
        return out
    }

    /** Changed outlines of the ids drawn since the last flush, as (id, rgb, glowing); then starts the next tick. */
    fun flushOutlines(onOutline: (Int, Int, Boolean) -> Unit) {
        for (i in 0 until ids.size) {
            val id = ids.getInt(i)
            val now = outlineNow.get(id)
            if (outlineLast.get(id) != now) { outlineLast.put(id, now); onOutline(id, (now shr 1).toInt(), (now and 1L) == 1L) }
        }
        ids.clear(); seen.clear(); tagNow.clear(); outlineNow.clear()
    }

    /** An entity gone: its tag and outline are no longer known. */
    fun forget(id: Int) { tagLast.remove(id); outlineLast.remove(id) }

    fun clear() {
        ids.clear(); seen.clear(); tagNow.clear(); outlineNow.clear(); tagLast.clear(); outlineLast.clear()
    }
}

/**
 * Applied moves as numbers (game thread): `[id,x,y,z,yRot,xRot,interp]` rows, and a trailing 1
 * for a snap, written as text only when [appendRows] runs (on the writer thread, after [take]).
 */
internal class MoveBuffer {
    var count = 0
        private set
    private var ids = IntArray(64)
    private var pos = DoubleArray(64 * 3)
    private var rot = FloatArray(64 * 2)
    private var flags = ByteArray(64)

    fun add(id: Int, x: Double, y: Double, z: Double, yRot: Float, xRot: Float, f: Int) {
        if (count == ids.size) {
            val n = count * 2
            ids = ids.copyOf(n); pos = pos.copyOf(n * 3); rot = rot.copyOf(n * 2); flags = flags.copyOf(n)
        }
        ids[count] = id
        pos[count * 3] = x; pos[count * 3 + 1] = y; pos[count * 3 + 2] = z
        rot[count * 2] = yRot; rot[count * 2 + 1] = xRot
        flags[count] = f.toByte()
        count++
    }

    /** A copy holding this buffer's rows (for the writer thread); this one starts over. */
    fun take(): MoveBuffer {
        val out = MoveBuffer()
        out.count = count
        out.ids = ids.copyOf(count); out.pos = pos.copyOf(count * 3); out.rot = rot.copyOf(count * 2); out.flags = flags.copyOf(count)
        clear()
        return out
    }

    fun clear() {
        count = 0
        if (ids.size > 1 shl 14) { ids = IntArray(64); pos = DoubleArray(64 * 3); rot = FloatArray(64 * 2); flags = ByteArray(64) }
    }

    /** The rows, comma-separated: null where the move left that part alone. */
    fun appendRows(sb: StringBuilder) {
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            val f = flags[i].toInt()
            sb.append('[').append(ids[i]).append(',')
            if (f and POS == 0) sb.append("null,null,null")
            else { PacketJson.num(sb, pos[i * 3]); sb.append(','); PacketJson.num(sb, pos[i * 3 + 1]); sb.append(','); PacketJson.num(sb, pos[i * 3 + 2]) }
            sb.append(',')
            if (f and YROT == 0) sb.append("null") else PacketJson.num(sb, rot[i * 2])
            sb.append(',')
            if (f and XROT == 0) sb.append("null") else PacketJson.num(sb, rot[i * 2 + 1])
            sb.append(',').append(if (f and INTERP != 0) 1 else 0)
            if (f and SNAP != 0) sb.append(",1")
            sb.append(']')
        }
    }

    companion object {
        const val POS = 1
        const val YROT = 2
        const val XROT = 4
        const val INTERP = 8
        const val SNAP = 16
    }
}
