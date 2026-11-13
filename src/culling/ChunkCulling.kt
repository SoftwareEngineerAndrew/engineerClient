package com.engineerclient.culling

import com.engineerclient.EngineerClient
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Chunk Culling (experimental): during a dungeon's clear, only the chunks that touch a tile that matters are drawn. The
 * tiles that matter, on the dungeon map ([TileRule]): the whole room the camera is in, and every tile north, east, south
 * and west of the camera's tile in a straight line, through doors, up to the first gap. What stands between two tiles is
 * read from the world's own blocks ([Links]), so it holds for rooms no map knows yet.
 *
 * Sodium only (0.9's render lists). Its own graph of sections barely notices a dungeon's walls: two rooms' walls and the
 * gap between them run through the middle of a 16-block section, which stays open from face to face, so it lists rooms
 * the camera can't see, next door and past them. Two hooks:
 *  - RenderSectionManager.prepareFrame, once a frame with the camera's position: the chunk columns to keep are worked out
 *    again ([frame]) and, when they changed, Sodium is told to make its render lists again that frame;
 *  - VisibleChunkCollector.visit, where a visible section goes onto a render list: one outside those columns doesn't
 *    ([CullState.culls]).
 * Only drawing changes. Sodium still finds, builds and updates every section as before, so a room is there, built, the
 * frame its tile starts to matter. A section left out takes its block entities with it (Sodium draws those per listed
 * section); entities are not touched.
 *
 * Off outside a dungeon, in the boss, and with the camera off the 6x6 grid: everything draws.
 */
object ChunkCulling : Module(
    name = "Chunk Culling",
    key = null,
    category = Category.custom("Engineer Client", 860, 10),
    description = "Experimental. In a dungeon, draws only the chunks of the room you are in and of the tiles in a straight line north, east, south and west of you, up to the first gap on the map. Needs Sodium.",
) {
    private val hud by HUD("Chunk Culling", "What Chunk Culling left out the last time the game listed what to draw: sections drawn of those in view, and chunk columns kept of the dungeon's 169.", true, 10, 120, 1f) { example ->
        val line = if (example) "Culling: 212/641 sections, 35/169 chunks" else statsLine() ?: return@HUD 0 to 0
        text(line, 0, 0, Colors.WHITE, shadow = true)
        mc.font.width(line) to mc.font.lineHeight
    }

    /**
     * How long a slot's link is good for before its columns are read again (about 20 microseconds a slot). A gap a second:
     * a door Hypixel builds after the chunk came is found. A door or a room's join doesn't go away: now and then, in case
     * it was read wrong. An unloaded one ten times a second (a chunk lookup). Each slot a little later than the one
     * before ([STAGGER_MS]), so they don't all come due in one frame.
     */
    private const val GAP_MS = 1000L
    private const val OPEN_MS = 10_000L
    private const val UNKNOWN_MS = 100L
    private const val STAGGER_MS = 16L

    private var links: LinkCache? = null
    /** After an error in here nothing is culled until the next world: the world it happened in. */
    private var brokenIn: ClientLevel? = null
    private var announced = false
    private var warned = false
    private var dungeonTicks = 0

    init {
        on<TickEvent.End> {
            // switched on, five seconds into a dungeon, and Sodium hasn't asked: a hook isn't there (no Sodium, or one
            // built differently from 0.9)
            if (!DungeonUtils.inDungeons) { dungeonTicks = 0; return@on }
            if (++dungeonTicks == 100 && !warned && !(CullState.frameHook && CullState.listHook)) {
                warned = true
                EngineerClient.logger.warn("[ec] chunk culling: Sodium hooks missing (frame ${CullState.frameHook}, render list ${CullState.listHook})")
                EngineerClient.msg("§eChunk Culling does nothing here: it needs Sodium 0.9 (its render lists weren't found).")
            }
        }
    }

    /**
     * Sodium is about to set a frame's terrain up, the camera at ([x], [y], [z]). True when the columns to draw changed:
     * the render lists have to be made again. Render thread.
     */
    @JvmStatic
    fun frame(x: Double, y: Double, z: Double): Boolean {
        CullState.newFrame()
        val level = mc.level
        val next = if (level == null || level === brokenIn) null else try {
            compute(level, Math.floor(x).toInt(), Math.floor(z).toInt())
        } catch (t: Throwable) {
            brokenIn = level
            EngineerClient.logger.warn("[ec] chunk culling: off for this world", t)
            null
        }
        if (CullState.mask === next) return false
        CullState.mask = next
        if (next != null && !announced) {
            announced = true
            EngineerClient.logger.info("[ec] chunk culling: on, ${java.lang.Long.bitCount(next.tiles)} tiles and ${next.kept} of ${CullMask.SPAN * CullMask.SPAN} chunk columns kept from block $x, $z")
        }
        return true
    }

    /** The columns to draw from a camera in block column ([bx], [bz]); the mask in use again when nothing changed. */
    private fun compute(level: ClientLevel, bx: Int, bz: Int): CullMask? {
        if (!enabled || !DungeonUtils.inDungeons || DungeonUtils.inBoss) return null
        val cache = links?.takeIf { it.level === level } ?: LinkCache(level).also { links = it; announced = false }
        val now = System.nanoTime() / 1_000_000
        val tiles = TileRule.relevantAt(bx, bz) { cache.get(it, now) }
        if (tiles == 0L) return null
        return CullState.mask?.takeIf { it.tiles == tiles } ?: CullMask(tiles)
    }

    private fun statsLine(): String? {
        val m = CullState.mask ?: return null
        return "Culling: ${CullState.lastOffered - CullState.lastDropped}/${CullState.lastOffered} sections, ${m.kept}/${CullMask.SPAN * CullMask.SPAN} chunks"
    }

    /** What stands in each slot of one world, read from its blocks and kept a while. Render thread. */
    private class LinkCache(val level: ClientLevel) : Columns {
        private val link = arrayOfNulls<Link>(Links.COUNT)
        private val readAt = LongArray(Links.COUNT)

        fun get(i: Int, now: Long): Link {
            val known = link[i]
            if (known != null) {
                val good = when (known) { Link.UNKNOWN -> UNKNOWN_MS; Link.GAP -> GAP_MS + STAGGER_MS * i; else -> OPEN_MS + STAGGER_MS * i }
                if (now - readAt[i] < good) return known
            }
            return Links.read(i, this).also { link[i] = it; readAt[i] = now }
        }

        override fun empty(x: Int, z: Int): Boolean? {
            val chunk = level.chunkSource.getChunk(x shr 4, z shr 4, ChunkStatus.FULL, false) ?: return null
            val lx = x and 15; val lz = z and 15
            for (section in chunk.sections) {
                if (section.hasOnlyAir()) continue
                for (y in 0..15) if (!section.getBlockState(lx, y, lz).isAir) return false
            }
            return true
        }
    }
}
