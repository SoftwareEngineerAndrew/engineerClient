package com.adm.features.impl.boss

import com.adm.AdmMod.mc
import com.adm.events.EntityEvent
import com.adm.utils.modMessage
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Items
import java.util.*

object DragonCheck {

    val dragonHealthMap = mutableMapOf<UUID, Pair<EnderDragon, Float>>()

    private val dragons = ArrayList<EnderDragon>(8)
    private val standDragons = Int2ObjectOpenHashMap<EnderDragon>()

    fun clear() {
        dragonHealthMap.clear()
        dragons.clear()
        standDragons.clear()
    }

    // The synced health only reads 1024, 200 or 0; the real HP is on the name stand 4 blocks above each dragon.
    fun dragonUpdate(event: EntityEvent.SetData) {
        val entity = event.entity as? EnderDragon ?: return
        val values = event.synchedDataValues
        var dead = false
        for (i in values.indices) {
            val v = values[i]
            if (v.id == 9) { dead = (v.value as? Float ?: return) <= 0f; break }
        }
        if (!dead) return
        if (dragonHealthMap.containsKey(entity.uuid)) dragonHealthMap[entity.uuid] = Pair(entity, 0f)

        WitherDragonsEnum.entries.firstOrNull { it.entityUUID == entity.uuid }?.let {
            if (it.state != WitherDragonState.DEAD) it.setDead(true)
        }
    }

    fun standUpdate(stand: ArmorStand, values: List<SynchedEntityData.DataValue<*>>) {
        var name: Component? = null
        for (i in values.indices) {
            val v = values[i]
            if (v.id == 2) { name = (v.value as? Optional<*>)?.orElse(null) as? Component; break }
        }
        if (name == null) return
        val cached = standDragons.get(stand.id)
        val dragon = cached ?: standDragon(stand) ?: return
        val hp = if (dragon.isDeadOrDying) 0f else parseHp(name.string)
        if (hp < 0f) return
        if (cached == null) standDragons.put(stand.id, dragon)
        dragonHealthMap[dragon.uuid] = Pair(dragon, hp)
    }

    private fun standDragon(stand: ArmorStand): EnderDragon? {
        var best: EnderDragon? = null
        var bestDist = 4.0
        for (i in dragons.indices) {
            val d = dragons[i]
            if (d.isRemoved) continue
            val dx = d.x - stand.x
            val dy = d.y + 4.0 - stand.y
            val dz = d.z - stand.z
            val dist = dx * dx + dy * dy + dz * dz
            if (dist < bestDist) { bestDist = dist; best = d }
        }
        return best
    }

    // "Withered Dragon [ᛤ ]310.2M/1B❤" -> 310200000, or -1 when the name isn't a dragon's HP.
    private fun parseHp(text: String): Float {
        val start = text.indexOf("Withered Dragon")
        if (start < 0) return -1f
        val slash = text.indexOf('/', start)
        if (slash < 0) return -1f
        var i = slash - 1
        val scale = when (text[i]) {
            'B' -> 1e9
            'M' -> 1e6
            'k' -> 1e3
            else -> 1.0
        }
        if (scale != 1.0) i--
        val end = i
        while (i >= 0 && (text[i].isDigit() || text[i] == '.' || text[i] == ',')) i--
        if (i == end) return -1f
        var value = 0.0
        var frac = 0.0
        for (j in i + 1..end) {
            val c = text[j]
            when {
                c == '.' -> frac = 1.0
                c.isDigit() -> if (frac > 0.0) { frac /= 10; value += (c - '0') * frac } else value = value * 10 + (c - '0')
            }
        }
        return (value * scale).toFloat()
    }

    fun dragonSpawn(event: EntityEvent.Add) {
        val dragon = event.entity as? EnderDragon ?: return
        dragons.removeIf { it.isRemoved }
        dragons.add(dragon)
        WitherDragonsEnum.entries.find { it.aabbDimensions.contains(event.entity.position()) }?.setAlive(event.entity.uuid)
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

