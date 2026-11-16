package com.engineerclient.misc

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.EventBus
import com.adm.features.impl.dungeon.MageBeam
import net.minecraft.client.Minecraft
import com.adm.utils.alert
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
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
object LcmMeleeWarning {
    private val on = BooleanSetting("LCM Melee Warning", false, desc = "As Mage in boss: a title while a wither boss is in melee range.")
    private val playSoundSetting = BooleanSetting("Melee Warning Sound", true, desc = "Play the alert sound with the title.").withDependency { on.value }
    private val repeatSetting = NumberSetting("Melee Warning Repeat", 10, 1..60, 1, desc = "How often the warning shows again while in range.", unit = " ticks").withDependency { on.value }
    private val playSound get() = playSoundSetting.value
    private val repeatTicks get() = repeatSetting.value

    private const val TITLE = "§c§lMELEE RANGE!"

    /** Ticks since the last warning. */
    private var sinceAlert = Int.MAX_VALUE

    private fun reset() { sinceAlert = Int.MAX_VALUE }

    fun install() {
        for (s in listOf(on, playSoundSetting, repeatSetting)) MageBeam.registerSetting(s)
        on<LevelEvent.Load> { reset() }

        on<TickEvent.End> {
            val me = Minecraft.getInstance().player
            if (!MageBeam.enabled || !on.value || me == null || !DungeonUtils.inBoss || DungeonUtils.currentDungeonPlayer.clazz != DungeonClass.MAGE) { reset(); return@on }

            val eye = me.eyePosition
            val reach = me.entityInteractionRange()
            val near = level.getEntitiesOfClass(WitherBoss::class.java, me.boundingBox.inflate(reach + 4.0)) {
                it.isAlive && Witherborn.isBoss(it) && !it.name.string.contains("Miner", true) &&
                    it.boundingBox.distanceToSqr(eye) <= reach * reach
            }.isNotEmpty()

            if (sinceAlert < Int.MAX_VALUE) sinceAlert++
            if (near && sinceAlert >= repeatTicks) { alert(TITLE, playSound); sinceAlert = 0 }
        }
        EventBus.subscribe(this)
    }
}
