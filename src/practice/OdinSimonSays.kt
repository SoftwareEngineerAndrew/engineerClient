package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.PacketEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.core.BlockPos

/**
 * engineerClient's Simon Says solver, in Odin's Simon Says: Odin keeps drawing the boxes, blocking
 * wrong clicks, announcing and playing its sounds, but the answer it does that from is
 * [SimonSaysSolver]'s. Odin's own working-out (its block and tick listeners) is switched off by
 * `SimonSaysMixin` while this is on, and the answer written into its fields here.
 *
 * Fixes over Odin's: a re-skip after a fail (Odin keeps the stray light, as it only looks for one
 * after a world load - the start button never shows as pressed), a 2-light skip (Odin reverses
 * it), a flash before the show, a lagging last light, lights counted as they come on, and a skip
 * whose last round is still showing when the buttons come back (triple skips, delayed shows).
 */
object OdinSimonSays {

    private val better = BooleanSetting("Better Solver", true,
        desc = "engineerClient's solver: handles skips after a fail, 2-light skips, flashes before the show and lag. Odin still draws, blocks and announces from it.")

    /** On, and Odin's Simon Says with it: Odin's own working-out is off. Read by SimonSaysMixin. */
    @JvmStatic
    fun active(): Boolean = SimonSays.enabled && better.value

    /** The solver and what it is fed from ([SimonSaysFeed], shared with ADS's own Simon Says). */
    private val feed = SimonSaysFeed()
    @Volatile private var tick = 0L

    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    private fun live() = active() && !SimonSaysPractice.practicing && DungeonUtils.getF7Phase() == M7Phases.P3

    /** Adds the setting to Odin's module; before OdinSplitsLook.install, which re-reads Odin's config. */
    fun install() {
        SimonSays.registerSetting(better)
        on<TickEvent.Server> { tick++ }
        on<LevelEvent.Load> { feed.reset() }
        // Lamps from their block packets, stamped with the server tick on arrival, then handled on the client thread.
        on<PacketEvent.Receive> {
            val at = tick
            val lamps = feed.arrived(packet, at) ?: return@on
            val handle = Runnable {
                if (!live()) return@Runnable
                EngineerClient.safely("ss solver lamps") { feed.lamps(lamps, at); sync() }
            }
            if (EngineerClient.mc.isSameThread) handle.run() else EngineerClient.mc.execute(handle)
        }
        on<TickEvent.End> {
            if (!live()) return@on
            EngineerClient.safely("ss solver tick") { feed.serverTick(tick - 1); sync() }
        }
        on<BlockUpdateEvent> {
            if (!live()) return@on
            EngineerClient.safely("ss solver") {
                // Odin's event comes before the change is applied: the grid as it was.
                val level = EngineerClient.mc.level ?: return@safely
                if (feed.blockChanged(level, pos, old, updated, tick)) sync()
            }
        }
        EventBus.subscribe(this)
    }

    /** Odin's boxes, Block Wrong Clicks and announcements read these. */
    private fun sync() {
        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as ArrayList<BlockPos>
        order.clear(); order.addAll(feed.solver.answer)
        odinNeeded.setInt(null, feed.solver.next)
    }
}
