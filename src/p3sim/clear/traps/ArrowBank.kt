package com.engineerclient.p3sim.clear.traps

/**
 * A bank of dispensers that fires its volley (one arrow from each dispenser, [ArrowRule.perDispenser] of them, all in one tick) on a fixed beat:
 * every [ArrowRule.period] ticks of the bank's clock, shifted by [phase] (where in the beat the run happens to be when the room is first near).
 * Real banks fire whether or not anyone is in the room, so the module ticks this one while a player is within range of the bank, not only while
 * somebody is inside the room. Pure: the module spawns the arrows, flies them and hurts whoever one hits (15 percent, true damage).
 */
class ArrowBank(val rule: ArrowRule, val phase: Int) : Hazard {
    override fun tick(clock: Int, players: List<PlayerPos>, out: HazardOut) {
        if (Math.floorMod(clock + phase, rule.period) == 0) out.volleys += rule
    }
}
