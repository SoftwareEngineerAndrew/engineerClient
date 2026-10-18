package com.engineerclient.p3sim.clear.puzzles

import kotlin.random.Random

/**
 * Three Weirdos as a logic puzzle. Three NPCs stand by three chests, one holds the reward, and each NPC says one sentence. Hypixel
 * does not mix sentences freely: across the recorded rooms the three NPCs always say one of exactly five fixed sets of three
 * sentences, and which NPC says which of the three varies. This class replays those five sets. The NPC that says the "solutions"
 * line of its set (the set's [Line.reward] line) is the one with the reward chest, the same line Odin's solver picks out.
 *
 * Some sentences name another NPC. Which one is fixed by the set: a sentence refers to the speaker of one particular line of the
 * same set ([Line.refers]). Hypixel writes a referenced name in red with a reset after it, e.g. `The reward isn't in §c§cCarver's §rchest.`;
 * [Scenario.lines] does the same.
 *
 * The sentence regexes the unit test replays are Odin's `WeirdosSolver` lists, OdinFabric 0.3.4, BSD 3-Clause, copyright (c) 2025
 * odtheking. Pure: no Minecraft. UNMEASURED: nothing about the draw itself except that every set was seen (13 rooms; a uniform draw
 * over the five sets and over which NPC says which line).
 */
object WeirdosLogic {
    /**
     * One sentence of a set. [text] has `{X}` where another NPC's name goes; [refers] is the index (in the set) of the line whose
     * speaker that name is, or -1 when the sentence names nobody. [reward] marks the line said by the NPC with the reward chest.
     */
    class Line(val text: String, val refers: Int = -1, val reward: Boolean = false)

    /** The five sets Hypixel uses, A to E. */
    val sets: List<List<Line>> = listOf(
        // A
        listOf(
            Line("They are both telling the truth. The reward isn't in {X}'s §rchest.", refers = 1),
            Line("The reward is not in my chest!", reward = true),
            Line("One of us is telling the truth!"),
        ),
        // B
        listOf(
            Line("The reward isn't in any of our chests.", reward = true),
            Line("The reward is in my chest!"),
            Line("The reward is not in my chest. They are both lying."),
        ),
        // C
        listOf(
            Line("My chest has the reward!"),
            Line("{X} §ris telling the truth.", refers = 0),
            Line("Both of them are telling the truth. Also, {X} §rhas the reward in their chest!", refers = 1, reward = true),
        ),
        // D
        listOf(
            Line("My chest doesn't have the reward. At least one of the others is telling the truth!"),
            Line("My chest doesn't have the reward. We are all telling the truth.", reward = true),
            Line("One of the others is lying!"),
        ),
        // E
        listOf(
            Line("We are all telling the truth!"),
            Line("{X} §ris telling the truth and the reward is in his chest.", refers = 0),
            Line("At least one of them is lying, and the reward is not in {X}'s §rchest!", refers = 1, reward = true),
        ),
    )

    /**
     * A drawn setting: [set] indexes [sets] and [slotOfLine] says which NPC slot (0..2, the chest index) says each line of the set.
     * [reward] is the slot of the NPC that says the reward line.
     */
    class Scenario(val set: Int, val slotOfLine: List<Int>) {
        private val lineSet get() = sets[set]
        val reward: Int get() = slotOfLine[lineSet.indexOfFirst { it.reward }]

        /** What each NPC slot says, with Hypixel's colour codes, given the three NPC [names] by slot. */
        fun lines(names: List<String>): List<String> {
            val out = arrayOfNulls<String>(3)
            lineSet.forEachIndexed { i, l ->
                val text = if (l.refers >= 0) l.text.replace("{X}", "§c§c" + names[slotOfLine[l.refers]]) else l.text
                out[slotOfLine[i]] = text
            }
            return out.map { it!! }
        }
    }

    /** One setting at random. */
    fun draw(rnd: Random): Scenario = Scenario(rnd.nextInt(sets.size), listOf(0, 1, 2).shuffled(rnd))

    /** Every setting: five sets times the six ways to hand the three lines to the three NPCs. */
    val scenarios: List<Scenario> by lazy {
        val perms = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        sets.indices.flatMap { s -> perms.map { Scenario(s, it) } }
    }

    /**
     * The names the NPCs go by in Hypixel's rooms (21 seen). Odin finds the NPC by the name on its armor stand, so any three distinct
     * names would work.
     */
    val NAMES = listOf(
        "Victoria", "Rose", "Luverne", "Lino", "Carver", "Melrose", "Madelia", "Ramsey", "Morris", "Hugo", "Hope",
        "Eveleth", "Marshall", "Elmo", "Ardis", "Winona", "Virginia", "Benson", "Willmar", "Montgomery", "Baxter",
    )

    /** Three distinct names. */
    fun drawNames(rnd: Random): List<String> = NAMES.shuffled(rnd).take(3)
}
