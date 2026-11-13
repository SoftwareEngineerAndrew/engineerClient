package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.SecretClicked
import com.odtheking.odin.utils.playSoundSettings
import net.minecraft.world.entity.item.ItemEntity

object OdinInstantChime {

    private val instant = BooleanSetting("Instant Item Chime", false, desc = "Secret items chime as soon as they can be picked up, not when the server confirms it. Added by ADM.")

    private const val PICKUP_DELAY = 10
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
            if (!instant.value || !chimeOn()) return@on
            val player = EngineerClient.mc.player ?: return@on
            val level = EngineerClient.mc.level ?: return@on
            val reach = player.boundingBox.inflate(1.0, 0.5, 1.0)
            for (entity in level.entitiesForRendering()) {
                if (entity !is ItemEntity || entity.isRemoved || entity.id in chimed) continue
                if (entity.tickCount < PICKUP_DELAY || !reach.intersects(entity.boundingBox) || !ItemHighlight.wanted(entity)) continue
                chimed.add(entity.id)
                playSoundSettings(soundSettings())
            }
        }
        EventBus.subscribe(this)
    }
}
