package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.alert
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.boss.wither.WitherBoss

/**
 * Left Click Mage: a mage's left click fires the Mage Staff beam, but with a boss inside melee reach the click lands
 * as a melee hit on it instead and the beam's damage is lost. A title as soon as you come within melee reach of a
 * wither boss (Maxor, Storm, Goldor, Necron), as Mage only, and again every [repeatTicks] while you stay there.
 *
 * Melee reach is your own entity interaction range (3 blocks) from your eyes to the nearest point of the wither's
 * hitbox, the same test the game makes before a left click hits an entity. Witherborn withers and the Wither Miners
 * round the arena don't count.
 */
object LcmMeleeWarning : Module(
    name = "LCM Melee Warning",
    category = Category.custom("ADS", 860, 10),
    description = "Boss only, as Mage: a title while you are in melee range of a wither boss, where your left click melees instead of firing the beam.",
    key = null,
) {
    private val playSound by BooleanSetting("Play Sound", true, desc = "Play the alert sound with the title.")
    private val repeatTicks by NumberSetting("Repeat Every", 10, 1..60, 1, desc = "While you stay in melee range, the warning shows again this often.", unit = " ticks")

    private const val TITLE = "§c§lMELEE RANGE!"

    /** Ticks since the last warning. */
    private var sinceAlert = Int.MAX_VALUE

    private fun reset() { sinceAlert = Int.MAX_VALUE }

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.End> {
            val me = mc.player
            if (me == null || !DungeonUtils.inBoss || DungeonUtils.currentDungeonPlayer.clazz != DungeonClass.MAGE) { reset(); return@on }

            val eye = me.eyePosition
            val reach = me.entityInteractionRange()
            val near = level.getEntitiesOfClass(WitherBoss::class.java, me.boundingBox.inflate(reach + 4.0)) {
                it.isAlive && Witherborn.isBoss(it) && !it.name.string.contains("Miner", true) &&
                    it.boundingBox.distanceToSqr(eye) <= reach * reach
            }.isNotEmpty()

            if (sinceAlert < Int.MAX_VALUE) sinceAlert++
            if (near && sinceAlert >= repeatTicks) { alert(TITLE, playSound); sinceAlert = 0 }
        }
    }
}
