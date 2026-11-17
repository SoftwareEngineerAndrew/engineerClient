package com.adm.features.impl.nether

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.alert
import com.adm.utils.modMessage
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.KuudraUtils
import com.adm.utils.skyblock.Supply
import net.minecraft.world.phys.Vec3

object NoPre : Module(
    name = "Pre-Spot Alert",
    description = "Alerts the party if a pre spot is missing."
) {
    private val showCratePriority by BooleanSetting("Show Crate Priority", false, desc = "Shows the crate priority.")
    private val advanced by BooleanSetting("Advanced Mode", false, desc = "Provides harder crate priority in certain situations.").withDependency { showCratePriority }

    private val partyCall = Regex("^Party > (\\[[^]]*?])? ?(\\w{1,16}): No ?(Triangle|X|Equals|Slash|xCannon|Square|Shop)!$")

    private var preSpot = Supply.None
    var missing = Supply.None
        private set

    init {
        on<MessageEvent.Chat> {
            if (!KuudraUtils.inKuudra) return@on
            when (message) {
                "[NPC] Elle: Head over to the main platform, I will join you when I get a bite!" -> registerPreSpot()
                "[NPC] Elle: Not again!" -> if (preSpot != Supply.None) checkSpawns()
                else -> if (message.startsWith("Party > ") && message.endsWith("!")) {
                    val called = partyCall.matchEntire(message)?.groupValues?.last() ?: return@on
                    missing = Supply.valueOf(called)
                    if (showCratePriority) advice(missing)?.let {
                        alert(it)
                        modMessage("Crate Priority: $it")
                    }
                }
            }
        }

        on<LevelEvent.Load> {
            preSpot = Supply.None
            missing = Supply.None
        }
    }

    private fun registerPreSpot() {
        val at = mc.player?.blockPosition() ?: return
        preSpot = when {
            Supply.Triangle.pickUpSpot.closerThan(at, 15.0) -> Supply.Triangle
            Supply.X.pickUpSpot.closerThan(at, 30.0) -> Supply.X
            Supply.Equals.pickUpSpot.closerThan(at, 15.0) -> Supply.Equals
            Supply.Slash.pickUpSpot.closerThan(at, 15.0) -> Supply.Slash
            else -> Supply.None
        }
        modMessage(if (preSpot == Supply.None) "§cDidn't register your pre-spot because you didn't get there in time." else "Pre-spot: ${preSpot.name}")
    }

    private fun checkSpawns() {
        val (partner, partnerRange) = when (preSpot) {
            Supply.Triangle -> Supply.Shop to 18.0
            Supply.X -> Supply.xCannon to 16.0
            Supply.Slash -> Supply.Square to 20.0
            else -> Supply.None to 0.0
        }
        var atPre = false
        var atPartner = false
        for (giant in KuudraUtils.giantZombies) {
            val spawn = Vec3(giant.x, 76.0, giant.z)
            if (preSpot.pickUpSpot.closerToCenterThan(spawn, 18.0)) atPre = true
            else if (partner != Supply.None && partner.pickUpSpot.closerToCenterThan(spawn, partnerRange)) atPartner = true
        }
        val missingSpot = when {
            !atPre -> preSpot
            !atPartner && partner != Supply.None -> partner
            else -> return
        }
        sendCommand("pc No ${missingSpot.name}!")
    }

    private fun advice(missing: Supply): String? {
        val pre = preSpot
        val cannon = "Go X Cannon"
        val shopOrCannon = if (advanced) "Go Shop" else cannon
        return when {
            pre == Supply.None || missing == Supply.None -> null
            missing == pre -> if (advanced) "Pull Square and X Cannon. Next: collect Shop" else "Pull Square. Next: collect Shop"
            missing == Supply.Shop -> if (pre == Supply.Triangle || pre == Supply.X) cannon else "Go Square, place on Shop"
            missing == Supply.Square -> if (pre == Supply.Triangle || pre == Supply.Equals) "Go Shop" else cannon
            missing == Supply.xCannon -> if (pre == Supply.Triangle || pre == Supply.Equals) "Go Shop" else "Go Square, place on X Cannon"
            pre == Supply.Slash -> "Go Square, place on ${missing.name}"
            pre == Supply.X -> cannon
            pre == Supply.Equals -> shopOrCannon
            missing == Supply.Slash -> "Go Square, place on Slash"
            missing == Supply.Equals -> shopOrCannon
            else -> cannon
        }
    }
}
