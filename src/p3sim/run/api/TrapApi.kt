package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.run.Run
import java.util.UUID

/**
 * The trap room and the rooms that disable abilities. Implemented by [com.engineerclient.p3sim.clear.traps.TrapsModule].
 * The damage work item edits the item handlers (`SimItems.kt`) and asks this before an ability (teleport, blink, wither
 * abilities...) is allowed. Traps themselves hurt through [DamageService.hurtPlayer] with exactly 15.0 percent, true damage.
 */
interface TrapService {
    /** True when [player] stands where abilities are disabled right now. The default allows everything. */
    fun abilitiesDisabled(run: Run, player: UUID): Boolean = false
}
