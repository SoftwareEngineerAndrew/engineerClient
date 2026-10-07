package com.engineerclient.misc

/**
 * The Agro Leaderboard sphere's radius rule, kept free of Minecraft so it tests headlessly.
 *
 * Maxor and Storm aggro onto the closest player, so a sphere around the boss through the closest
 * player is the aggro boundary: step inside it and you take aggro. When YOU are the closest, that
 * sphere would just pass through you and say nothing, so it goes through the 2nd closest instead —
 * the margin you have before someone else takes it.
 *
 * [distances] is every live party member's distance to the boss, with whether it is you; order
 * does not matter. Returns null when there is no one to measure against.
 */
object AgroSphere {

    data class Member(val isMe: Boolean, val distance: Double)

    data class Result(val radius: Double, val youHaveAggro: Boolean)

    fun radius(distances: List<Member>): Result? {
        val sorted = distances.sortedBy { it.distance }
        val closest = sorted.firstOrNull() ?: return null
        if (!closest.isMe) return Result(closest.distance, youHaveAggro = false)
        val second = sorted.getOrNull(1) ?: return null
        return Result(second.distance, youHaveAggro = true)
    }
}
