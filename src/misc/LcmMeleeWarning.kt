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
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

/**
 * Left Click Mage: a mage's left click fires the Mage Staff beam, but with a mob inside melee reach the click lands
 * as a melee hit on it instead and the beam's damage is lost. A title the moment you come within melee reach of any
 * mob in the boss room, as Mage only.
 *
 * Melee reach is your own entity interaction range (3 blocks) from your eyes to the nearest point of the mob's
 * hitbox, the same test the game makes before a left click hits an entity. Players and armor stands (name tags) are
 * not mobs. One title per entry: it comes back only once you have been out of reach for [rearmTicks].
 */
object LcmMeleeWarning : Module(
    name = "LCM Melee Warning",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Boss only, as Mage: a title when you step into melee range of a mob, where your left click melees instead of firing the beam.",
    key = null,
) {
    private val playSound by BooleanSetting("Play Sound", true, desc = "Play the alert sound with the title.")
    private val rearmTicks by NumberSetting("Rearm Delay", 10, 0..60, 1, desc = "How long you must be out of melee range before the warning can show again.", unit = " ticks")

    private const val TITLE = "§c§lMELEE RANGE!"

    private var inRange = false
    /** Ticks out of reach since the last warning; the next one needs [rearmTicks]. */
    private var outTicks = Int.MAX_VALUE

    private fun reset() { inRange = false; outTicks = Int.MAX_VALUE }

    init {
        on<LevelEvent.Load> { reset() }

        on<TickEvent.End> {
            val me = mc.player
            if (me == null || !DungeonUtils.inBoss || DungeonUtils.currentDungeonPlayer.clazz != DungeonClass.MAGE) { reset(); return@on }

            val eye = me.eyePosition
            val reach = me.entityInteractionRange()
            val near = level.getEntitiesOfClass(LivingEntity::class.java, me.boundingBox.inflate(reach + 1.0)) {
                it.isAlive && it !is Player && it !is ArmorStand && it.boundingBox.distanceToSqr(eye) <= reach * reach
            }.isNotEmpty()

            if (near && !inRange && outTicks >= rearmTicks) alert(TITLE, playSound)
            if (near) outTicks = 0 else if (outTicks < Int.MAX_VALUE) outTicks++
            inRange = near
        }
    }
}
