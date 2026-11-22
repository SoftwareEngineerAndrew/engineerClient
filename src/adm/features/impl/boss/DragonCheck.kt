package com.adm.features.impl.boss

import com.adm.AdmMod.mc
import com.adm.events.EntityEvent
import com.adm.utils.modMessage
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import java.util.*

object DragonCheck {

    val dragonHealthMap = mutableMapOf<UUID, Pair<EnderDragon, Float>>()

    fun clear() {
        dragonHealthMap.clear()
    }

    // Above this the synced health is the real HP, sent on every hit; it reads 200 while spawning and 0 on death.
    const val SYNCED_HP = 1024f

    fun dragonUpdate(event: EntityEvent.SetData) {
        val entity = event.entity as? EnderDragon ?: return
        val values = event.synchedDataValues
        var hp = -1f
        for (i in values.indices) {
            val v = values[i]
            if (v.id == 9) { hp = v.value as? Float ?: return; break }
        }
        if (hp > SYNCED_HP) {
            dragonHealthMap[entity.uuid] = Pair(entity, hp)
            return
        }
        if (hp != 0f) return
        if (dragonHealthMap.containsKey(entity.uuid)) dragonHealthMap[entity.uuid] = Pair(entity, 0f)

        WitherDragonsEnum.entries.firstOrNull { it.entityUUID == entity.uuid }?.let {
            if (it.state != WitherDragonState.DEAD) it.setDead(true)
        }
    }

    fun dragonSpawn(event: EntityEvent.Add) {
        val dragon = event.entity as? EnderDragon ?: return
        val uuid = dragon.uuid
        for (d in WitherDragonsEnum.entries) if (d.entityUUID == uuid) return
        WitherDragonsEnum.entries.find { it.spawnsAt(dragon.x, dragon.y, dragon.z) }?.setAlive(uuid)
    }

    fun dragonSprayed(event: EntityEvent.SetItemSlot) {
        if (event.stack.item != Items.PACKED_ICE) return
        val sprayedEntity = event.entity as? ArmorStand ?: return

        WitherDragonsEnum.entries.forEach { dragon ->
            val entity = mc.level?.getEntity(dragon.entityUUID ?: return@forEach) as? EnderDragon ?: return@forEach
            if (dragon.isSprayed || dragon.state != WitherDragonState.ALIVE || sprayedEntity.distanceTo(entity) > 8) return@forEach

            if (WitherDragons.sendSpray) {
                modMessage("§${dragon.colorCode}${dragon.name} §fdragon was sprayed in §c${(WitherDragons.currentTick - dragon.spawnedTime).let { 
                    "$it §ftick${if (it > 1) "s" else ""}" 
                }}.")
            }
            dragon.isSprayed = true
        }
    }
}

