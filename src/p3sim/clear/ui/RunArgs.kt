package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.clear.bots.BotStrategies
import com.engineerclient.p3sim.run.LagPreset
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.plugin.PartyStrategy

/** What the run settings (and the start screen) offer for the parts of a run the player did not name. */
data class RunDefaults(
    val bots: Boolean = true,
    val partySize: Int = 5,
    val lag: LagPreset = LagPreset.OFF,
    val noDeaths: Boolean = false,
    /** True: every run draws its mob HP from the pack's per-run table; false: base HP. */
    val mobHpFromPack: Boolean = false,
    /**
     * The party strategy choice ([StrategyChoice]): "auto" (the default: the first preset an installed plug-in offers; the neutral
     * strategy, which sends nobody to the blood room, with no plug-in), "neutral", or a preset's id.
     */
    val strategy: String = StrategyChoice.AUTO,
) {
    /**
     * The config these defaults make for [layout]. [seed] is the run's own seed. [offered] are the strategies the game offers (the choice
     * is turned into a real strategy id here, once, so the run and its summary say what will actually happen).
     */
    fun config(layout: LayoutSource, seed: Long, offered: List<PartyStrategy> = BotStrategies.available()): RunConfig = RunConfig(
        layout = layout, seed = seed, bots = bots, partySize = if (bots) partySize.coerceIn(1, 5) else 1,
        lag = lag, noDeaths = noDeaths, mobHpScale = if (mobHpFromPack) null else 1.0,
        strategy = StrategyChoice.resolve(strategy, offered),
    )
}

/**
 * Reads the words after `/ads run random`, `/ads run seed <n>` and `/ads run layout <id>` into a [RunConfig]. Pure.
 *
 * After the layout words come any of these options, in any order (what is not named comes from the run settings):
 * `bots on|off` (or `nobots`), `party <1-5>`, `lag off|good|typical|bad|highslot`, `nodeaths [on|off]`, `hp base|pack`, `strategy auto|neutral|<id>` (the party strategy: auto is the first preset an installed plug-in offers, else the neutral one).
 */
object RunArgs {
    sealed interface Parsed {
        class Ok(val config: RunConfig) : Parsed
        class Bad(val message: String) : Parsed
    }

    const val OPTIONS = "bots on|off, party 1-5, lag off|good|typical|bad|highslot, nodeaths, hp base|pack, strategy auto|neutral|<id>"

    /**
     * [mode] is `random`, `seed` or `layout`; [args] the words after it. [drawSeed] makes a fresh run seed. [offered] are the party strategies
     * the game offers (a word after `strategy` must name one, or be auto or neutral).
     */
    fun parse(
        mode: String, args: List<String>, defaults: RunDefaults,
        offered: List<PartyStrategy> = BotStrategies.available(), drawSeed: () -> Long,
    ): Parsed {
        var rest = args
        val layout: LayoutSource
        val seed: Long
        when (mode) {
            "random" -> { layout = LayoutSource.Random; seed = drawSeed() }
            "seed" -> {
                val n = rest.firstOrNull()?.toLongOrNull() ?: return Parsed.Bad("Give the seed as a number: /ads run seed 12345")
                rest = rest.drop(1)
                // The seed picks the layout and also seeds the run, so the same number repeats the whole run.
                layout = LayoutSource.Seed(n); seed = n
            }
            "layout" -> {
                val id = rest.firstOrNull() ?: return Parsed.Bad("Give the recorded layout's id: /ads run layout <id> (the browser on the start screen lists them)")
                rest = rest.drop(1)
                layout = LayoutSource.Recorded(id); seed = drawSeed()
            }
            else -> return Parsed.Bad("Unknown way to start: $mode")
        }
        var d = defaults
        var i = 0
        while (i < rest.size) {
            val w = rest[i].lowercase()
            val next = rest.getOrNull(i + 1)?.lowercase()
            when (w) {
                "nobots" -> { d = d.copy(bots = false); i++ }
                "bots" -> { d = d.copy(bots = onOff(next) ?: return bad("bots", "on or off")); i += 2 }
                "party" -> {
                    val n = next?.toIntOrNull()?.takeIf { it in 1..5 } ?: return bad("party", "a size from 1 to 5")
                    d = d.copy(partySize = n, bots = n > 1); i += 2
                }
                "lag" -> { d = d.copy(lag = lag(next) ?: return bad("lag", "off, good, typical, bad or highslot")); i += 2 }
                "nodeaths" -> {
                    val v = onOff(next)
                    d = d.copy(noDeaths = v ?: true); i += if (v != null) 2 else 1
                }
                "hp" -> {
                    d = d.copy(mobHpFromPack = when (next) { "base" -> false; "pack" -> true; else -> return bad("hp", "base or pack") }); i += 2
                }
                "strategy" -> {
                    val word = rest.getOrNull(i + 1)?.takeIf { it.isNotBlank() } ?: return bad("strategy", "a strategy id")
                    val choice = StrategyChoice.normalize(word, offered)
                        ?: return Parsed.Bad("There is no party strategy called \"$word\". Choose from: ${StrategyChoice.words(offered)}.")
                    d = d.copy(strategy = choice); i += 2
                }
                else -> return Parsed.Bad("I don't know \"${rest[i]}\". Options after the layout: $OPTIONS.")
            }
        }
        return Parsed.Ok(d.config(layout, seed, offered))
    }

    private fun bad(option: String, wants: String) = Parsed.Bad("\"$option\" needs $wants. Options: $OPTIONS.")

    private fun onOff(w: String?): Boolean? = when (w) { "on", "true", "yes" -> true; "off", "false", "no" -> false; else -> null }

    /** A lag preset by its name, ignoring case and spaces, dashes and underscores ("High slot", "high_slot", "highslot"). */
    fun lag(word: String?): LagPreset? {
        val key = word?.filter { it.isLetter() }?.lowercase() ?: return null
        return LagPreset.entries.firstOrNull { it.name.filter { c -> c.isLetter() }.lowercase() == key || it.label.filter { c -> c.isLetter() }.lowercase() == key }
    }

    /** One plain sentence saying what a config will start (shown when a run is launched). */
    fun describe(c: RunConfig): String {
        val layout = when (val l = c.layout) {
            LayoutSource.Random -> "a random layout (seed ${c.seed})"
            is LayoutSource.Seed -> "the layout for seed ${l.seed}"
            is LayoutSource.Recorded -> "the recorded layout ${l.id}"
            is LayoutSource.Practice -> "${l.room} (practice)"
        }
        val party = if (!c.bots || c.partySize <= 1) "you alone" else "${c.partySize} players with bots"
        val extras = buildList {
            add("lag ${c.lag.label}")
            if (c.noDeaths) add("no deaths")
            c.strategy?.takeIf { it != "neutral" }?.let { add("party strategy $it") }
            add(if (c.mobHpScale == null) "mob HP from the pack" else "base mob HP")
        }
        return "Starting a ${c.floor} run on $layout, $party, ${extras.joinToString(", ")}."
    }
}
