package com.engineerclient.p3sim

/**
 * A P3 start's lead-in: the 102 ticks (5.1 s) from Storm's death line to Goldor's "Who dares
 * trespass" (P2Storm's end, as on Hypixel). You're on your P3 spot with the P3 hotbar from the
 * start, Storm's last lines play, a countdown shows the seconds left, then P3 begins.
 */
class StormEnd : Fight.Phase("Storm end") {
    override val restart get() = Fight.Start.P3

    override fun start() {
        Sim.player?.let { p ->
            val spot = Spots.p3Start(1)
            Sim.tp(p, spot.x, spot.y, spot.z, spot.yaw, spot.pitch)
            SimItems.giveHotbar(p, p3 = true)
        }
        Sim.boss("Storm", "I should have known that I stood no chance.")
        BossBar.progress(0f)
    }

    override fun tick() {
        if (t % 20 == 0 && t < LEAD) Sim.title("", "§eTerminals in §f${(LEAD - t + 19) / 20}", 0, 22, 0)
        when (t) {
            62 -> Sim.boss("Storm", "At least my son died by your hands.")
            LEAD -> Fight.begin(GoldorPhase(1, arrived = true))
        }
    }

    companion object { const val LEAD = 102 }
}
