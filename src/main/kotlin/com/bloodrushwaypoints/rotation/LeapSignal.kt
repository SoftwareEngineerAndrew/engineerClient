package com.bloodrushwaypoints.rotation

import com.bloodrushwaypoints.BrwMod
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * Who you should be leaping to, and whether it is time yet.
 *
 * Two stages, because those are two different questions:
 *
 *  - **soft** — the target is decided, so you know *who to click*. Known the moment the rotation
 *    hands you a role.
 *  - **ready** — that player has actually reached the section you are going to, so you know
 *    *when to click*. Leaping to an early-enterer before they are through is a wasted leap.
 *
 * Readiness comes from the arrival messages Odin's positional-message boxes send to party chat
 * (`/posmsg`, same config on every teammate), so every client hears it in the same order. Where
 * the strategy has no such message for a target, it falls back to watching their position — a
 * local judgement, so it can be late, but role assignment stays chat-driven either way and can
 * never desync.
 */
object LeapSignal {

    data class Target(
        val ign: String,
        /** The section they are opening up, i.e. where you are going. */
        val section: Int,
        /** True once they are actually there. */
        val ready: Boolean,
        /** Timing the strategy needs that the graph cannot express. */
        val note: String,
    )

    private var lastReadyFor: String? = null
    private var lastTargetKey: String? = null

    fun reset() {
        lastReadyFor = null
        lastTargetKey = null
    }

    /** The current leap cue for this client, or null when there is nobody to leap to. */
    fun current(): Target? {
        val me = BrwMod.mc.player?.name?.string ?: return null
        val role = RotationEngine.roleOf(me) ?: return null
        val targetIgn = RotationEngine.leapTargetFor(me) ?: return null
        return Target(
            ign = targetIgn,
            section = role.section,
            ready = RotationEngine.leapReadyFor(me) ?: isInPlace(targetIgn, role.section),
            note = role.leapNote,
        )
    }

    /**
     * True when [ign] is standing in [section]. Uses the teammate list Odin already maintains, so
     * it works for anyone whose entity the client has loaded; an unloaded teammate reads as
     * not-yet-arrived, which fails safe — a late cue, never a wrong one.
     */
    private fun isInPlace(ign: String, section: Int): Boolean {
        val entity = DungeonUtils.dungeonTeammates
            .firstOrNull { it.name.equals(ign, ignoreCase = true) }
            ?.entity ?: return false
        return P3Sections.isInSection(section, entity.x, entity.y, entity.z)
    }

    /**
     * Call once per tick. Returns true exactly on the tick the cue turns ready, so the caller can
     * fire the sound once rather than every frame.
     */
    fun pollBecameReady(): Boolean {
        val target = current()
        val targetKey = target?.let { "${it.ign}:${it.section}" }
        if (targetKey != lastTargetKey) {
            lastTargetKey = targetKey
            BrwLog.log("LEAP", if (target == null) "no leap target" else "target ${target.ign} for section ${target.section}" +
                (if (target.note.isNotBlank()) " — ${target.note}" else ""))
        }
        if (target == null || !target.ready) {
            if (target == null) lastReadyFor = null
            return false
        }
        val key = "${target.ign}:${target.section}"
        if (lastReadyFor == key) return false
        lastReadyFor = key
        val me = BrwMod.mc.player?.name?.string ?: ""
        val how = if (RotationEngine.leapReadyFor(me) == null) "position fallback" else "announcement"
        BrwLog.log("LEAP", "READY: ${target.ign} for section ${target.section} ($how)")
        return true
    }
}
