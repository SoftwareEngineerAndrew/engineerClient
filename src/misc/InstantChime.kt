package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.utils.playSoundSettings
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.item.ItemEntity

object InstantChime {

    private val instant = BooleanSetting("Instant Item Chime", false, desc = "Secret items chime as soon as they can be picked up, not when the server confirms it. Added by ADM.").withDependency {
        (SecretClicked.settings["Clicked"] as? DropdownSetting)?.value == true && (SecretClicked.settings["Secret Chime"] as? BooleanSetting)?.value == true
    }

    /** Secret items can be picked up 5 ticks after they spawn, not the usual 10. */
    private const val PICKUP_DELAY = 5
    private val chimed = HashSet<Int>()

    @Suppress("UNCHECKED_CAST")
    private val soundSettings by lazy {
        SecretClicked::class.java.getDeclaredField("soundSettings").apply { isAccessible = true }.get(null) as () -> Triple<String, Float, Float>
    }

    private fun chimeOn(): Boolean =
        SecretClicked.enabled && (SecretClicked.settings["Secret Chime"] as? BooleanSetting)?.value == true

    @JvmStatic
    fun alreadyChimed(item: ItemEntity): Boolean = item.id in chimed

    fun install() {
        SecretClicked.registerSetting(instant)
        on<LevelEvent.Load> { chimed.clear() }
        on<TickEvent.End> {
            if (!instant.value || !DungeonUtils.inClear || !chimeOn()) return@on
            val player = EngineerClient.mc.player ?: return@on
            val level = EngineerClient.mc.level ?: return@on
            val reach = player.boundingBox.inflate(1.0, 0.5, 1.0)
            // The item reached us half a ping after the server spawned it: it's that much older there.
            val ready = (PICKUP_DELAY - com.adm.utils.ServerUtils.averagePing / 100).coerceAtLeast(0)
            for (entity in level.entitiesForRendering()) {
                if (entity !is ItemEntity || entity.isRemoved || entity.id in chimed) continue
                if (entity.tickCount < ready || !reach.intersects(entity.boundingBox) || !ItemHighlight.wanted(entity)) continue
                chimed.add(entity.id)
                playSoundSettings(soundSettings())
            }
        }
        EventBus.subscribe(this)
    }
}
