package com.adm.utils.skyblock

import com.adm.AdmMod.mc
import com.adm.events.EntityEvent
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.NameMemo
import com.adm.utils.handlers.TickTask
import com.adm.utils.noControlCodes
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Giant
import net.minecraft.world.entity.monster.cubemob.MagmaCube

object KuudraUtils {

    inline val inKuudra get() = LocationUtils.isCurrentArea(Island.Kuudra)

    /** Player name to the time their Fresh Tools started; entries stay for the whole run. */
    val freshers = LinkedHashMap<String, Long>()
    val giantZombies = ArrayList<Giant>()
    val buildingPiles = ArrayList<ArmorStand>()

    var kuudraEntity: MagmaCube? = null
        private set
    var phase = 0
        private set
    var playersBuildingAmount = 0
        private set
    var buildDonePercentage = 0
        private set
    @Volatile
    var kuudraTier = 0
        private set

    private const val OWN_FRESH = "Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!"
    private const val SUPPLIES_RECEIVED = "✓ SUPPLIES RECEIVED ✓"

    private val partyFresh = Regex("^Party > (\\[[^]]*?])? ?(\\w{1,16}): FRESH$")
    private val pileProgress = Regex("PROGRESS: (\\d+)%")
    private val ballistaProgress = Regex("Building Progress (\\d+)% \\((\\d+) Players Helping\\)")
    private val tierLine = Regex("Kuudra's Hollow \\(T(\\d)\\)$")

    private class Stand(val pile: Boolean, val received: Boolean, val build: Int, val helpers: Int)

    private val plainStand = Stand(pile = false, received = false, build = -1, helpers = 0)

    private val stands = NameMemo { name ->
        val s = name.string
        val build = ballistaProgress.find(s)
        when {
            build != null -> Stand(pileProgress.matches(s), false, build.groupValues[1].toIntOrNull() ?: 0, build.groupValues[2].toIntOrNull() ?: 0)
            pileProgress.matches(s) -> Stand(pile = true, received = false, build = -1, helpers = 0)
            s == SUPPLIES_RECEIVED -> Stand(pile = false, received = true, build = -1, helpers = 0)
            else -> plainStand
        }
    }

    private val carriesSupply = NameMemo { name -> name.string.endsWith("Head") }

    init {
        TickTask(10) {
            if (!inKuudra) return@TickTask
            if (!Giants.active) Giants.start()
            scan()
        }

        on<MessageEvent.Chat> {
            if (!inKuudra) return@on
            when (message) {
                "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!" -> phase = 1
                "[NPC] Elle: OMG! Great work collecting my supplies!" -> phase = 2
                "[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!" -> phase = 3
                "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!" -> phase = 4
                OWN_FRESH -> freshers[mc.player?.name?.string ?: "self"] = System.currentTimeMillis()
                else -> if (message.startsWith("Party > ") && message.endsWith(": FRESH"))
                    partyFresh.find(message)?.let { freshers[it.groupValues[2]] = System.currentTimeMillis() }
            }
        }

        onReceive<ClientboundSetPlayerTeamPacket> {
            if (kuudraTier != 0 || !inKuudra) return@onReceive
            val team = parameters.orElse(null) ?: return@onReceive
            val line = (team.playerPrefix.string + team.playerSuffix.string).noControlCodes
            if (!line.contains("Kuudra's Hollow")) return@onReceive
            tierLine.find(line)?.let { kuudraTier = it.groupValues[1].toInt() }
        }

        on<LevelEvent.Load> {
            Giants.stop()
            for (supply in Supply.entries) supply.isActive = true
            giantZombies.clear()
            buildingPiles.clear()
            freshers.clear()
            kuudraEntity = null
            playersBuildingAmount = 0
            buildDonePercentage = 0
            kuudraTier = 0
            phase = 0
        }
    }

    private fun scan() {
        val level = mc.level ?: return
        buildingPiles.clear()
        for (entity in level.entitiesForRendering()) {
            when (entity) {
                is ArmorStand -> readStand(entity)
                is MagmaCube -> if (entity.size == 30 && entity.getAttributeBaseValue(Attributes.MAX_HEALTH) == 100000.0) kuudraEntity = entity
            }
        }
    }

    // Supplies appear and vanish with their giant, so they follow entity events instead of the scan.
    private object Giants {
        var active = false
            private set

        init {
            on<EntityEvent.Add> { (entity as? Giant)?.let(::update) }
            on<EntityEvent.SetItemSlot> { if (slot == EquipmentSlot.MAINHAND) (entity as? Giant)?.let(::update) }
            on<EntityEvent.Remove> { if (entity is Giant) giantZombies.remove(entity) }
        }

        fun start() {
            giantZombies.clear()
            mc.level?.entitiesForRendering()?.forEach { if (it is Giant) update(it) }
            EventBus.subscribe(this)
            active = true
        }

        fun stop() {
            if (!active) return
            EventBus.unsubscribe(this)
            active = false
        }

        private fun update(giant: Giant) {
            if (carriesSupply[giant, giant.mainHandItem.hoverName]) {
                if (giant !in giantZombies) giantZombies.add(giant)
            } else giantZombies.remove(giant)
        }
    }

    private fun readStand(stand: ArmorStand) {
        val info = stands[stand, stand.name]
        if (info === plainStand) return
        if (info.pile) buildingPiles.add(stand)
        if (info.build >= 0 && phase == 2) {
            playersBuildingAmount = info.helpers
            buildDonePercentage = info.build
        }
        if (info.received && phase == 1) supplyAt(stand.x.toInt(), stand.z.toInt())?.isActive = false
    }

    private fun supplyAt(x: Int, z: Int): Supply? = when {
        x == -98 && z == -112 -> Supply.Shop
        x == -98 && z == -99 -> Supply.Equals
        x == -110 && z == -106 -> Supply.xCannon
        x == -106 && z == -112 -> Supply.X
        x == -94 && z == -106 -> Supply.Triangle
        x == -106 && z == -99 -> Supply.Slash
        else -> null
    }
}
