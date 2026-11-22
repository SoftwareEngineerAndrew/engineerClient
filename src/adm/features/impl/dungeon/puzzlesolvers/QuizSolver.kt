package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.utils.Color
import com.adm.utils.JsonResourceLoader
import com.adm.utils.render.drawBeaconBeam
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawTracer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.toFixed
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB
import com.google.gson.reflect.TypeToken
import java.io.File

object QuizSolver {
    private const val START_TICKS = 220
    private const val QUESTION_TICKS = 100
    private const val ORUO = "[STATUE] Oruo the Omniscient: "

    private val answers: MutableMap<String, List<String>> =
        (JsonResourceLoader.loadJson<MutableMap<String, List<String>>>(Identifier.fromNamespaceAndPath("engineerclient", "puzzles/quiz-answers.json")) ?: mutableMapOf())
            .also { it.putAll(extraAnswers()) }

    // config/adm/quiz-answers.json, same shape as the bundled file, adds or replaces questions without an update.
    private fun extraAnswers(): Map<String, List<String>> = try {
        val file = File(AdmMod.configFile, "quiz-answers.json")
        if (!file.exists()) emptyMap()
        else JsonResourceLoader.defaultGson.fromJson<Map<String, List<String>>>(file.readText(), object : TypeToken<Map<String, List<String>>>() {}.type) ?: emptyMap()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyMap()
    }
    private var triviaAnswers: List<String>? = null

    // Answer stands ⓐ ⓑ ⓒ, the block under each, and which are right.
    private val triviaOptions = arrayOfNulls<BlockPos>(3)
    private val below = arrayOfNulls<BlockPos>(3)
    private val belowBox = arrayOfNulls<AABB>(3)
    private val correct = BooleanArray(3)

    // Ticks left, ticks the stage started with, stage 1..3.
    private val timer = IntArray(3)

    private var shownKey = -1L
    private var shownText = ""
    private val exampleText by lazy { format(START_TICKS, START_TICKS, 1) }

    private fun startTimer(ticks: Int, stage: Int) {
        timer[0] = ticks; timer[1] = ticks; timer[2] = stage
    }

    fun onServerTick() {
        if (timer[0] > 0) timer[0]--
    }

    fun timerText(example: Boolean): String? {
        if (example) return exampleText
        val tick = timer[0]
        if (tick <= 0) return null
        val key = (tick.toLong() shl 32) or (timer[1].toLong() shl 2) or timer[2].toLong()
        if (key != shownKey) { shownKey = key; shownText = format(tick, timer[1], timer[2]) }
        return shownText
    }

    private fun format(tick: Int, length: Int, stage: Int): String {
        val color = if (tick.toFloat() >= length * 0.66) "§a" else if (tick.toFloat() >= length * 0.33) "§6" else "§c"
        return "§5Quiz §8(§f$stage§8/§f3§8): $color${(tick / 20f).toFixed()}s"
    }

    fun onMessage(msg: String) {
        when (msg) {
            "${ORUO}I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know." -> startTimer(START_TICKS, 1)
            "${ORUO}2 questions left... Then you will have proven your worth to me!" -> if (timer[2] != 2) startTimer(QUESTION_TICKS, 2)
            "${ORUO}One more question!" -> if (timer[2] != 3) startTimer(QUESTION_TICKS, 3)
        }

        if (msg.startsWith(ORUO) && msg.endsWith("correctly!")) {
            if ("answered the final question" in msg) {
                PuzzleSolvers.onPuzzleComplete("Quiz")
                reset()
                return
            }
            if ("answered Question #" in msg) {
                correct.fill(false)
                // Oruo's remark comes 40 ticks after a right answer (39 before "One more question!"), so the timer starts here.
                if ("Question #1 " in msg) startTimer(QUESTION_TICKS + 40, 2) else startTimer(QUESTION_TICKS + 39, 3)
            }
        }

        val line = msg.trim()
        val slot = when (line.firstOrNull()) {
            'ⓐ' -> 0
            'ⓑ' -> 1
            'ⓒ' -> 2
            else -> -1
        }
        if (slot >= 0 && triviaAnswers?.any { msg.endsWith(it) } == true) correct[slot] = true

        if (line == "What SkyBlock year is it?") {
            triviaAnswers = listOf("Year ${(((System.currentTimeMillis() / 1000) - 1560276000) / 446400).toInt() + 1}")
            return
        }
        for ((question, right) in answers) if (question in msg) { triviaAnswers = right; return }
    }

    fun onRoomEnter(event: RoomEnterEvent) {
        val room = event.room ?: return
        if (room.data?.name != "Quiz") return
        place(0, room.getRealCoords(BlockPos(20, 70, 6)))
        place(1, room.getRealCoords(BlockPos(15, 70, 9)))
        place(2, room.getRealCoords(BlockPos(10, 70, 6)))
    }

    private fun place(i: Int, pos: BlockPos) {
        val under = pos.below()
        triviaOptions[i] = pos; below[i] = under; belowBox[i] = AABB(under)
    }

    fun onRenderWorld(event: RenderExtractEvent, quizColor: Color, quizDepth: Boolean) {
        if (triviaAnswers == null) return
        for (i in 0 until 3) {
            if (!correct[i]) continue
            val box = belowBox[i] ?: continue
            event.drawFilledBox(box, quizColor, depth = quizDepth)
            event.drawBeaconBeam(below[i]!!, quizColor)
        }
    }

    fun tracer(event: RenderExtractEvent, color: Color) {
        if (triviaAnswers == null || DungeonUtils.currentRoomName != "Quiz") return
        for (i in 0 until 3) if (correct[i]) belowBox[i]?.let { event.drawTracer(it.center, color, false) }
    }

    fun isWrongClick(pos: BlockPos): Boolean {
        if (!correct[0] && !correct[1] && !correct[2]) return false
        if (DungeonUtils.currentRoomName != "Quiz" || AdmMod.mc.player?.isShiftKeyDown == true) return false
        for (i in 0 until 3) {
            val at = triviaOptions[i] ?: continue
            if (at.distSqr(pos) <= 2.0) return !correct[i]
        }
        return false
    }

    fun reset() {
        correct.fill(false)
        triviaAnswers = null
        timer.fill(0)
    }

    fun unload() {
        reset()
        triviaOptions.fill(null); below.fill(null); belowBox.fill(null)
    }
}
