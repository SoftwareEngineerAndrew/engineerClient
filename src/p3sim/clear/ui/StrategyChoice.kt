package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.clear.bots.NeutralStrategy
import com.engineerclient.p3sim.run.plugin.PartyStrategy

/**
 * The party strategy the player picks for a run (the start screen's button, the "Run Party Strategy" setting and the `strategy` word of
 * `/ads run ...`): "auto", "neutral" or the id of a preset an installed plug-in offers. Pure; the list of offered strategies is passed in
 * (the callers pass `BotStrategies.available()`).
 *
 * "Auto" is the default. With a plug-in installed it means the plug-in's first preset; with no plug-in it is the neutral strategy, which
 * sends nobody to the blood room. "Neutral" stays selectable for a run where you want the bots to only finish their rooms.
 */
object StrategyChoice {
    const val AUTO = "auto"

    /** True when a plug-in offers at least one preset besides the neutral one; only then is there anything to choose. */
    fun hasPresets(offered: List<PartyStrategy>): Boolean = offered.any { it.id != NeutralStrategy.ID }

    /** The preset "auto" stands for: the first one a plug-in offers, or null when none does (the neutral strategy then). */
    fun autoPick(offered: List<PartyStrategy>): PartyStrategy? = offered.firstOrNull { it.id != NeutralStrategy.ID }

    /** Every choice in the order the button cycles through: auto, neutral, then the plug-in presets. */
    fun choices(offered: List<PartyStrategy>): List<String> =
        (listOf(AUTO, NeutralStrategy.ID) + offered.map { it.id }).distinct()

    /** The choice after [current] (back to auto after the last). A [current] that is not offered any more counts as auto. */
    fun next(current: String, offered: List<PartyStrategy>): String {
        val all = choices(offered)
        val at = all.indexOf(normalize(current, offered) ?: AUTO)
        return all[(at + 1) % all.size]
    }

    /**
     * The choice a typed or saved word names, ignoring case (a preset's label and its id both work), or null when nothing offered
     * has that name.
     */
    fun normalize(word: String?, offered: List<PartyStrategy>): String? {
        val w = word?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (w.equals(AUTO, ignoreCase = true)) return AUTO
        if (w.equals(NeutralStrategy.ID, ignoreCase = true)) return NeutralStrategy.ID
        return offered.firstOrNull { it.id.equals(w, ignoreCase = true) || it.label.equals(w, ignoreCase = true) }?.id
    }

    /**
     * The `RunConfig.strategy` a choice makes: the preset's id, or null for the neutral strategy. A saved choice that no plug-in offers
     * any more (the plug-in was removed) counts as auto.
     */
    fun resolve(choice: String?, offered: List<PartyStrategy>): String? =
        when (val c = normalize(choice, offered) ?: AUTO) {
            AUTO -> autoPick(offered)?.id
            NeutralStrategy.ID -> null
            else -> c
        }

    /** What the button says: "Auto: <the first preset's label>", "Neutral", the preset's own label. */
    fun label(choice: String, offered: List<PartyStrategy>): String {
        val c = normalize(choice, offered) ?: AUTO
        return when (c) {
            AUTO -> "Auto: " + (autoPick(offered)?.label ?: NeutralStrategy.label)
            NeutralStrategy.ID -> NeutralStrategy.label
            else -> offered.firstOrNull { it.id == c }?.label ?: c
        }
    }

    /** The tooltip text for a choice: what it does, and that a click picks the next one. */
    fun about(choice: String, offered: List<PartyStrategy>): String {
        val c = normalize(choice, offered) ?: AUTO
        val what = when (c) {
            AUTO -> "Auto uses the first preset a plug-in offers (${autoPick(offered)?.label ?: NeutralStrategy.label}), and the neutral strategy when none does."
            NeutralStrategy.ID -> NeutralStrategy.description
            else -> offered.firstOrNull { it.id == c }?.description?.takeIf { it.isNotBlank() } ?: "A party strategy from a plug-in."
        }
        return "$what Click: the next choice. It only matters with bots."
    }

    /** The ids a player can type after `strategy`, for a message. */
    fun words(offered: List<PartyStrategy>): String = choices(offered).joinToString(", ")
}
