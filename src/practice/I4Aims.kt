package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.engineerclient.misc.RandomStuff
import com.engineerclient.p3sim.ShotPlan
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.itemId
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.phys.Vec3

/**
 * Odin's Arrows Device (the i4 / sharpshooter solver) shows "aim positions": where to put your crosshair so one
 * volley hits the lit target and as many unhit cells as it can (a cell hit while it's the next, not yet lit target
 * counts too: tools/p3sim/research/devices.md §1). Odin's aims assume a Terminator: two side arrows, so it aims
 * between two cells. This works them out for the bow you hold, from Hypixel's measured arrows
 * (tools/p3sim/research/terror-mosquito.md, [ShotPlan]): the Terminator's ±5.5° pair, the Mosquito's single arrow
 * and Terror's two Hydra Strike arrows at ±8° at 10 stacks - with which a cell in the middle column covers its row.
 *
 * Odin's module draws them (its "Show Aim Positions" on); ArrowsDeviceAimMixin hands Odin these instead of its
 * own, and they're redone whenever your bow, Hydra stacks or stance change. Random Stuff's "i4 Bow Aims" toggle.
 */
object I4Aims {
    /** Its handlers are on Odin's bus from here on; they do nothing while the toggle is off. */
    fun register() = EventBus.subscribe(this)

    private val BOWS = setOf("TERMINATOR", "MOSQUITO_BOW", "ITEM_SPIRIT_BOW", "JUJU_SHORTBOW", "ARTISANAL_SHORTBOW", "LAST_BREATH")
    private val STACKS = Regex("(\\d+)⁑")
    private val CODES = Regex("§.")

    /** Hydra Strike stacks, from the action bar (Hypixel's and P3 Sim's: `N⁑`, nothing at 0). */
    @Volatile private var stacks = 0
    private var lastKey: String? = null

    /** One aim: crosshair at [at], the unhit cells its volley would hit. */
    private class Aim(val at: Vec3, val covers: Set<BlockPos>)

    private val odin by lazy { Class.forName("com.odtheking.odin.features.impl.boss.ArrowsDevice") }
    private val aimClass by lazy { Class.forName("com.odtheking.odin.features.impl.boss.ArrowsDevice\$AimPosition") }
    private val aimCtor by lazy { aimClass.getDeclaredConstructor(Vec3::class.java, Set::class.java, Double::class.javaPrimitiveType).apply { isAccessible = true } }
    private fun field(name: String) = odin.getDeclaredField(name).apply { isAccessible = true }

    /** The bow in your hand, if it's a shortbow. */
    private fun bow(): String? = mc.player?.mainHandItem?.itemId?.takeIf { it in BOWS }

    /**
     * Odin's aim positions for [target] (Odin's private AimPosition objects), for the bow in your hand; null when you
     * hold none (Odin's own then). Called by ArrowsDeviceAimMixin on Odin's block-update thread.
     */
    @JvmStatic
    fun aimsFor(target: BlockPos): List<Any>? {
        val bow = bow() ?: return null
        @Suppress("UNCHECKED_CAST")
        val marked = (field("markedPositions").get(null) as? Set<BlockPos>) ?: emptySet()
        val unmarked = I4Geometry.CELLS.filter { it !in marked }.toSet()
        val aims = candidates(bow, unmarked)
        val first = aims.filter { target in it.covers }.maxByOrNull { it.covers.size } ?: return emptyList<Any>()
        // Odin's pick: then two more, each the most new cells, ties to the nearest to the last.
        val result = arrayListOf(first)
        val covered = first.covers.toMutableSet()
        val rest = aims.filter { target !in it.covers }
        repeat(2) {
            val next = rest.filter { it !in result }.maxWithOrNull(compareBy<Aim>({ a -> a.covers.count { c -> c !in covered } }, { a -> -a.at.distanceTo(result.last().at) }))
            if (next != null && next.covers.any { it !in covered }) { result += next; covered += next.covers }
        }
        return result.mapIndexed { i, a -> aimCtor.newInstance(a.at, a.covers, if (i == 0) 0.0 else a.at.distanceTo(result[i - 1].at)) }
    }

    /** Every crosshair spot worth trying (I4Geometry.SPOTS), aimed so the main arrow lands there, with the unhit cells it covers. */
    private fun candidates(bow: String, unmarked: Set<BlockPos>): List<Aim> {
        val p = mc.player ?: return emptyList()
        return I4Geometry.SPOTS.mapNotNull { spot ->
            val (at, hit) = I4Geometry.aim(bow, p.position(), p.isShiftKeyDown, stacks, spot)
            val covers = hit.filter { it in unmarked }.toSet()
            // Odin draws a unit cube around the position, sunk 0.1 into the board's face like its own.
            if (covers.isEmpty()) null else Aim(Vec3(at.x, at.y, 50.5), covers)
        }
    }

    init {
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (!overlay) return@onReceive
            stacks = STACKS.find(content.string.replace(CODES, ""))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 10) ?: 0
        }

        // Your bow, stacks (Hydra arrows or not) or stance changed: Odin's aims are redone for the target it shows.
        on<TickEvent.End> {
            if (!RandomStuff.showsI4BowAims()) return@on
            val p = mc.player ?: return@on
            val key = "${bow()}|${stacks >= 10}|${p.isShiftKeyDown}|${p.blockPosition()}"
            if (key == lastKey) return@on
            lastKey = key
            com.engineerclient.EngineerClient.safely("i4 aims") {
                val target = field("targetPosition").get(null) as? BlockPos ?: return@safely
                val aims = aimsFor(target) ?: return@safely
                field("optimalAimPositions").set(null, aims)
            }
        }
    }
}
