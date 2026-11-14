package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB

object LeapCount : Module(
    name = "Leap Count",
    category = Category.BOSS,
    description = "Blade's leap display: on an early enter, core or relic spot, how many teammates have leapt into that section.",
    key = null,
) {
    private val assumeSplitEE2 by BooleanSetting("Assume Split EE2", true, desc = "Expects 3 at EE2 instead of 4.")
    private val assumeCore by BooleanSetting("Assume Core", true, desc = "Expects 3 at EE3 instead of 4.")

    private val hud by HUD("Leap Display", "Teammates leapt to the spot you're on.", true, 420, 330, 2.1f) { example ->
        val max = if (example) 3 else max() ?: return@HUD 0 to 0
        val n = if (example) 2 else count
        val colour = if (max - n <= 1) "§9" else "§4"
        textDim("$colour$n§9/$max Players Leaped", 0, 0)
    }

    private val EE2 = AABB(57.0, 108.0, 130.0, 59.0, 110.0, 132.0)
    private val HIGH_EE2 = AABB(59.0, 132.0, 138.0, 62.0, 133.0, 140.0)
    private val EE3 = AABB(1.0, 108.0, 103.0, 3.0, 110.0, 105.0)
    private val CORE = AABB(53.5, 114.0, 49.5, 55.5, 116.0, 51.5)
    private val RELIC = AABB(51.5, 3.0, 73.5, 57.5, 8.0, 79.5)

    /** The section each spot counts teammates in, by spot (2 EE2, 3 EE3, 4 core, 5 relic). */
    private val REGIONS = mapOf(
        2 to AABB(17.0, 106.0, 121.0, 108.0, 145.0, 143.0),
        3 to AABB(-2.0, 106.0, 51.0, 20.0, 145.0, 128.0),
        4 to AABB(20.0, 26.0, 29.0, 191.0, 145.0, 58.0),
        5 to AABB(3.0, 5.0, 0.0, 128.0, 48.0, 140.0),
    )

    private var spot = 0
    private var count = 0

    private fun max(): Int? = when (spot) {
        2 -> if (assumeSplitEE2) 3 else 4
        3 -> if (assumeCore) 3 else 4
        4, 5 -> 4
        else -> null
    }

    private fun spotOf(p: Player): Int {
        val at = p.position()
        return when {
            HIGH_EE2.contains(at) || EE2.contains(at) -> 2
            EE3.contains(at) -> 3
            CORE.contains(at) -> 4
            RELIC.contains(at) && DungeonUtils.getF7Phase() != M7Phases.P5 -> 5
            else -> 0
        }
    }

    init {
        on<TickEvent.End> {
            val me = mc.player
            val level = mc.level
            if (me == null || level == null || !DungeonUtils.inBoss || DungeonUtils.floor?.floorNumber != 7) { spot = 0; return@on }
            spot = spotOf(me)
            val region = REGIONS[spot] ?: run { count = 0; return@on }
            val names = DungeonUtils.dungeonTeammatesNoSelf.filter { !it.isDead }.map { it.name }.toSet()
            count = level.getEntities(me, region).count { it is Player && it.name.string in names }
        }
    }
}
