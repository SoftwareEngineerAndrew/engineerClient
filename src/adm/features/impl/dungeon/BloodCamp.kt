package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.*
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.*
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawLine
import com.adm.utils.render.drawText
import com.adm.utils.render.textDim
import com.adm.features.impl.dungeon.map.tile.RoomType
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.components.LerpingBossEvent
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object BloodCamp : Module(
    name = "Blood Camp",
    description = "Features for Blood Camping."
) {
    private val sendMoveTime by BooleanSetting("Move alert", true, desc = "Sends a message & title indicating when the watcher will move.")
    private val moveSound by BooleanSetting("Move Sound", true, desc = "Plays a sound with the move alert.").withDependency { sendMoveTime }
    private val mageOnly by BooleanSetting("Mage Only", false, desc = "Move alert and HUD only as Mage or in the blood room.")
    private val moveTimer by HUD("Move HUD", "Displays the time until the watcher moves.") { example ->
        val ticks = if (example) 10 else moveTicks
        if (ticks <= 0 || !example && !moveForMe) return@HUD 0 to 0
        textDim(moveText(ticks), 0, 0, Colors.MINECRAFT_RED)
    }

    private val moveTimerPrefix by BooleanSetting("Move Timer Prefix", true, desc = "\"Move Timer:\" in front of the move HUD's time.")
    private val assistDropdown by DropdownSetting("Blood Assist Dropdown", desc = "Shows settings for rendering boxes on mobs spawning in the blood room.")
    private val bloodAssist by BooleanSetting("Blood Camp Assist", true, desc = "Draws boxes to spawning mobs in the blood room.").withDependency { assistDropdown }

    private val pboxColor by ColorSetting("Spawn Color", Colors.MINECRAFT_RED, true, desc = "Color for Spawn render box. Set alpha to 0 to disable.").withDependency { bloodAssist && assistDropdown}
    private val fboxColor by ColorSetting("Final Color", Colors.MINECRAFT_DARK_AQUA, true, desc = "Color for when Spawn and Mob boxes are merged. Set alpha to 0 to disable.").withDependency { bloodAssist && assistDropdown }
    private val mboxColor by ColorSetting("Position Color", Colors.MINECRAFT_GREEN, true, desc = "Color for current position box. Set alpha to 0 to disable.").withDependency { bloodAssist && assistDropdown }
    private val boxSize by NumberSetting("Box Size", 1.0, 0.1..1.0, 0.1, desc = "The size of the boxes. Lower values may seem less accurate.").withDependency { bloodAssist && assistDropdown }
    private val drawLine by BooleanSetting("Line", true, desc = "Line between Position box and Spawn box.").withDependency { bloodAssist && assistDropdown }
    private val drawTime by BooleanSetting("Time Left", true, desc = "Time before the blood mob spawns. Adjust offset depending on accuracy. May be up to ~100ms off.").withDependency { bloodAssist && assistDropdown }
    private val advanced by DropdownSetting("Advanced", desc = "Shows advanced timing settings for the blood camp assist.").withDependency { bloodAssist && assistDropdown }
    private val offset by NumberSetting("Offset", 40, -100..100, desc = "Tick offset to adjust between ticks.").withDependency { advanced && bloodAssist && assistDropdown }
    private val tick by NumberSetting("Tick", 38, 35..41, desc = "Tick to assume spawn. Adjust offset to offset this value to the ms.").withDependency { advanced && bloodAssist && assistDropdown }
    private val pingOffset by BooleanSetting("Ping Offset", true, desc = "Offsets the mob box by your ping.").withDependency { advanced && bloodAssist && assistDropdown }
    private val manualOffset by NumberSetting("Mob Box Offset", 0f, 0.0..300.0, 1, desc = "Manually offsets the mob box.").withDependency { advanced && bloodAssist && assistDropdown && !pingOffset}
    private val hitSound by BooleanSetting("Hit Sound", false, desc = "Plays a sound when a mob can be hit, early by your ping.").withDependency { bloodAssist && assistDropdown }
    private val hitLead by NumberSetting("Hit Sound Lead", 65, 0..500, desc = "Extra time the hit sound plays early.", unit = "ms").withDependency { hitSound && bloodAssist && assistDropdown }
    private val clickCountdown by BooleanSetting("Click Countdown", true, desc = "Above the next mob, time until a click sent now lands as it can be hit.").withDependency { bloodAssist && assistDropdown }
    private val clickHud by HUD("Click Countdown HUD", "The click countdown for the next mob, with how many are due within a second.") { example ->
        if (example) return@HUD textDim(clickHudText(450, 2), 0, 0)
        if (!bloodAssist || !DungeonUtils.inClear || clickMs == Long.MIN_VALUE) return@HUD 0 to 0
        textDim(clickHudText(clickMs, clickDue), 0, 0)
    }.withDependency { bloodAssist && assistDropdown }
    private val watcherBar by BooleanSetting("Watcher Bar", true, desc = "Shows the watcher's health.")
    private val hideOutsideBlood by BooleanSetting("Hide Outside Blood", false, desc = "Hides the Watcher's bar when you are not in the blood room.").withDependency { watcherBar }

    private const val WATCHER = "[BOSS] The Watcher: "
    private val openLines = listOf(
        "Congratulations, you made it through the Entrance.", "Ah, you've finally arrived.", "Ah, we meet again...",
        "So you made it this far... interesting.", "You've managed to scratch and claw your way here, eh?",
        "I'm starting to get tired of seeing you around here...", "Oh.. hello?", "Things feel a little more roomy now, eh?",
        "Ah, we meet again. As I foresaw..."
    ).mapTo(HashSet()) { WATCHER + it }
    private const val SECOND_WAVE_LINE = WATCHER + "Let's see how you can handle this."
    private const val MOVE_TICKS = 410
    private const val FIRST_WAVE_REACH = 16.1
    private const val LATER_WAVE_REACH = 11.9
    private const val STEP_MS = 150L
    private const val START_SAMPLES = 5
    private val SPAWN_Y = doubleArrayOf(71.75, 75.75, 79.75)

    // Each spawning mob is a head on an armor stand that slides from its start towards where it lands.
    private class Spawn(val start: Vec3, var startedMs: Long, val firstWave: Boolean, var step: Int) {
        // Each step's own start estimate, averaged so one late or early packet doesn't set it.
        var samples = 1
        var startSum = startedMs
        var last: Vec3 = start
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var seen = false
        var at: Vec3 = start
        var landing: Vec3 = start
        var prevLanding: Vec3 = start
        var landingMs = startedMs
        var velX = 0.0
        var velY = 0.0
        var velZ = 0.0
        var hitPlayed = false
    }

    private val spawns = HashMap<ArmorStand, Spawn>()
    private var watcher: Zombie? = null
    private var firstWave = true
    @Volatile private var serverMs = 0L
    @Volatile private var moveTicks = 0
    private val boxes = BoxBatch()

    private var lastBarName: Component? = null
    private var lastBarIsWatcher = false
    private var barCount = -1
    private var barTotal = -1
    private var barText: Component = Component.empty()
    @Volatile private var moveForMe = true

    private var moveTextTicks = -1
    private var moveTextValue = ""
    private var moveTextPrefix = true

    private fun moveText(ticks: Int): String {
        val prefix = com.adm.features.impl.render.ClickGUIModule.timerPrefixes && moveTimerPrefix
        if (ticks != moveTextTicks || prefix != moveTextPrefix) {
            moveTextTicks = ticks
            moveTextPrefix = prefix
            moveTextValue = "${if (prefix) "Move Timer: " else ""}${ticks / 20}.${((ticks % 20) * 5).toString().padStart(2, '0')}s"
        }
        return moveTextValue
    }

    private val timeText = FormatCache(16) { seconds ->
        val color = when {
            seconds > 1.5 -> 'a'
            seconds in 0.5..1.5 -> '6'
            seconds in 0.0..0.5 -> 'c'
            else -> 'b'
        }
        "§$color${seconds.toFloat().toFixed()}s"
    }

    private val clickText = FormatCache(8) { ms -> if (ms <= 0) "§a§lGO!" else "§b${String.format(Locale.ROOT, "%.2f", ms / 1000)}s" }

    private var clickMs = Long.MIN_VALUE
    private var clickDue = 0
    private var hudMs = Long.MIN_VALUE
    private var hudDue = -1
    private var hudText = ""

    private fun clickHudText(ms: Long, due: Int): String {
        if (ms != hudMs || due != hudDue) {
            hudMs = ms
            hudDue = due
            val text = clickText(ms.toDouble())
            hudText = if (due > 1) "$text §7($due)" else text
        }
        return hudText
    }

    private var clickPos: Vec3 = Vec3.ZERO

    private fun clickPosAt(x: Double, y: Double, z: Double): Vec3 {
        val p = clickPos
        if (p.x != x || p.y != y || p.z != z) clickPos = Vec3(x, y, z)
        return clickPos
    }

    private fun msUntilSpawn(spawn: Spawn) = (if (spawn.firstWave) 2000 else 0) + (tick * 50) - (serverMs - spawn.startedMs) + offset

    // Only on the bus while the Watcher is known: every other entity move would return at once.
    private object Spawns {
        init {
            on<EntityEvent.Move> {
                val boss = watcher ?: return@on
                val stand = entity as? ArmorStand ?: return@on
                if (newPos == Vec3.ZERO || newPos == stand.position() || !DungeonUtils.inClear) return@on
                if (boss.distanceTo(stand) > 20) return@on
                val head = stand.getItemBySlot(EquipmentSlot.HEAD)
                if (head.item != Items.PLAYER_HEAD || head.texture !in bloodMobHeads) return@on

                val now = serverMs
                val known = spawns[stand]
                val spawn = known ?: newSpawn(stand.position(), newPos, now).also { spawns[stand] = it }
                val dx = newPos.x - spawn.last.x
                val dy = newPos.y - spawn.last.y
                val dz = newPos.z - spawn.last.z
                spawn.last = newPos
                if (dx * dx + dy * dy + dz * dz > 0) {
                    spawn.sumX += dx
                    spawn.sumY += dy
                    spawn.sumZ += dz
                    if (known != null && spawn.samples < START_SAMPLES) {
                        spawn.step++
                        spawn.startSum += now - spawn.step * STEP_MS
                        spawn.startedMs = spawn.startSum / ++spawn.samples
                    }
                }

                val heading = if (spawn.sumX * spawn.sumX + spawn.sumY * spawn.sumY + spawn.sumZ * spawn.sumZ > 0) Vec3(spawn.sumX, spawn.sumY, spawn.sumZ).normalize() else Vec3.ZERO
                val elapsed = now - spawn.startedMs
                val landing = spawn.start.add(heading.scale(if (spawn.firstWave) FIRST_WAVE_REACH else LATER_WAVE_REACH))
                spawn.prevLanding = if (spawn.seen) spawn.landing else landing
                spawn.landing = landing
                spawn.seen = true
                spawn.landingMs = now
                if (elapsed > 0) {
                    spawn.velX = (newPos.x - spawn.start.x) / elapsed
                    spawn.velY = (newPos.y - spawn.start.y) / elapsed
                    spawn.velZ = (newPos.z - spawn.start.z) / elapsed
                }
                spawn.at = newPos
            }
        }
    }

    init {
        on<MessageEvent.Chat> {
            if (!message.startsWith(WATCHER) || !DungeonUtils.inClear) return@on
            if (message in openLines) {
                moveTicks = MOVE_TICKS
                moveForMe = isMoveForMe()
                if (sendMoveTime && moveForMe) modMessage("Watcher will move in 20.50s.")
            } else if (message == SECOND_WAVE_LINE) firstWave = false
        }

        on<EntityEvent.SetItemSlot> {
            if (watcher != null || slot != EquipmentSlot.HEAD || !bloodAssist || stack.isEmpty || !DungeonUtils.inClear) return@on
            if (stack.texture in watcherHeads) watcher = entity as? Zombie
            if (watcher != null) EventBus.subscribe(Spawns)
        }

        on<EntityEvent.Remove> {
            if (spawns.isNotEmpty() && entity is ArmorStand) spawns.remove(entity)
            val boss = watcher ?: return@on
            if (entity.id == boss.id) {
                watcher = null
                EventBus.unsubscribe(Spawns)
            }
        }

        on<TickEvent.End> {
            if (moveTicks > 0) moveForMe = isMoveForMe()
        }

        on<TickEvent.Server> {
            serverMs += 50
            if (moveTicks > 0 && --moveTicks == 0) mc.execute { if (sendMoveTime && moveForMe) alert("Kill Mobs", moveSound) }
        }

        on<LevelEvent.Load> {
            watcher = null
            EventBus.unsubscribe(Spawns)
            spawns.clear()
            serverMs = 0
            firstWave = true
            moveTicks = 0
        }

        on<RenderBossBarEvent> {
            if (!watcherBar || !hideOutsideBlood || !DungeonUtils.inClear) return@on
            if (isWatcherBar(bossBar.name) && DungeonUtils.currentRoom?.type != RoomType.BLOOD) cancel()
        }

        on<RenderExtractEvent> {
            clickMs = Long.MIN_VALUE
            if (spawns.isEmpty() || !bloodAssist || !DungeonUtils.inClear) return@on
            val size = boxSize
            val half = size / -2.0
            val lead = if (pingOffset) ServerUtils.averagePing.toFloat() else manualOffset
            val hitAt = if (hitSound) ServerUtils.averagePing + hitLead else Int.MIN_VALUE
            val now = serverMs
            val ping = ServerUtils.averagePing
            var nextUntil = Long.MAX_VALUE
            var nextX = 0.0
            var nextY = 0.0
            var nextZ = 0.0
            var due = 0
            boxes.clear()
            for ((stand, spawn) in spawns) {
                if (!stand.isAlive) continue
                val blend = min(now - spawn.landingMs, 100) / 100f
                val from = spawn.prevLanding
                val to = spawn.landing
                val landX = from.x + (to.x - from.x) * blend
                val landY = from.y + (to.y - from.y) * blend
                val landZ = from.z + (to.z - from.z) * blend
                val untilSpawn = msUntilSpawn(spawn)
                if (untilSpawn <= hitAt && !spawn.hitPlayed) {
                    spawn.hitPlayed = true
                    playSoundAtPlayer(SoundEvents.NOTE_BLOCK_BELL.value())
                }

                val landBox = cube(landX, landY, landZ, half, size)
                if (lead < untilSpawn) {
                    boxes.outline(cube(stand.x + spawn.velX * lead, stand.y + spawn.velY * lead, stand.z + spawn.velZ * lead, half, size), mboxColor, depth = true)
                    boxes.outline(landBox, pboxColor, depth = true)
                } else boxes.outline(landBox, fboxColor, depth = true)

                val label = Vec3(landX, landY + 2.0, landZ)
                if (drawLine) drawLine(listOf(spawn.at.add(0.0, 2.0, 0.0), label), Colors.MINECRAFT_RED, depth = true)
                if (drawTime) drawText(timeText(((untilSpawn.toFloat() - offset) / 1000).toDouble()), label, 2f, true)
                if (untilSpawn > 0) {
                    if (untilSpawn - ping <= 1000) due++
                    if (untilSpawn < nextUntil) {
                        nextUntil = untilSpawn
                        nextX = landX; nextY = landY; nextZ = landZ
                    }
                }
            }
            drawBoxes(boxes)
            if (nextUntil == Long.MAX_VALUE) return@on
            clickMs = nextUntil - ping
            clickDue = due
            // Above the Time Left label so the two don't overlap.
            if (clickCountdown) drawText(clickText(clickMs.toDouble()), clickPosAt(nextX, nextY + 2.7, nextZ), 1.2f, true)
        }
    }

    override fun onEnable() {
        super.onEnable()
        if (watcher != null) EventBus.subscribe(Spawns)
    }

    override fun onDisable() {
        EventBus.unsubscribe(Spawns)
        super.onDisable()
    }

    private fun cube(x: Double, y: Double, z: Double, half: Double, size: Double): AABB {
        val minX = half + x
        val minY = 1.5 + y
        val minZ = half + z
        return AABB(minX, minY, minZ, size + minX, size + minY, size + minZ)
    }

    // A head first seen mid-flight: walk its last step back to a wall spawn to find when it left.
    private fun newSpawn(prev: Vec3, next: Vec3, now: Long): Spawn {
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val dz = next.z - prev.z
        val steps = if (atSpawn(prev) || abs(dy) < 1e-4) 0 else stepsFromSpawn(prev, dx, dy, dz)
        if (steps <= 0) return Spawn(next, now, firstWave, 0)
        val back = (steps - 1).toDouble()
        return Spawn(Vec3(prev.x - back * dx, prev.y - back * dy, prev.z - back * dz), now - steps * STEP_MS, firstWave, steps)
    }

    private fun stepsFromSpawn(prev: Vec3, dx: Double, dy: Double, dz: Double): Int {
        for (y in SPAWN_Y) {
            val n = (prev.y - y) / dy
            val steps = n.roundToInt()
            if (steps < 1 || abs(n - steps) > 0.05) continue
            if (isCentre(prev.x - steps * dx) && isCentre(prev.z - steps * dz)) return steps
        }
        return 0
    }

    private fun atSpawn(pos: Vec3) = isCentre(pos.x) && isCentre(pos.z) && SPAWN_Y.any { abs(pos.y - it) < 0.01 }

    private fun isCentre(v: Double) = abs(v - floor(v) - 0.5) < 0.02

    private fun isMoveForMe(): Boolean =
        !mageOnly || DungeonUtils.currentDungeonPlayer.clazz == DungeonClass.MAGE || DungeonUtils.currentRoom?.type == RoomType.BLOOD

    private fun isWatcherBar(name: Component): Boolean {
        if (name !== lastBarName) {
            lastBarName = name
            lastBarIsWatcher = name.string == "§c§lThe Watcher"
        }
        return lastBarIsWatcher
    }

    // Hypixel's name stays on the bar; only the drawn text carries the count, read from the bar's target instead of its lerp.
    @JvmStatic
    fun barName(event: LerpingBossEvent, name: Component): Component {
        if (!enabled || !watcherBar || !DungeonUtils.inClear || !isWatcherBar(name)) return name
        if (hideOutsideBlood && DungeonUtils.currentRoom?.type != RoomType.BLOOD) return Component.empty()
        val progress = event.targetPercent
        if (progress < 0.05f) return name
        val total = 8 + (DungeonUtils.floor?.floorNumber ?: 0)
        val count = (total * progress).roundToInt()
        if (count != barCount || total != barTotal) {
            barCount = count
            barTotal = total
            barText = Component.literal("§c§lThe Watcher $count/$total")
        }
        return barText
    }

    private val watcherHeads = setOf(
        "ewogICJ0aW1lc3RhbXAiIDogMTY5NzMwOTQxNzI1NiwKICAicHJvZmlsZUlkIiA6ICJjYjYxY2U5ODc4ZWI0NDljODA5MzliNWYxNTkwMzE1MiIsCiAgInByb2ZpbGVOYW1lIiA6ICJWb2lkZWRUcmFzaDUxODUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTY2MmI2ZmI0YjhiNTg2ZGM0Y2RmODAzYjA0NDRkOWI0MWQyNDVjZGY2NjhkYWIzOGZhNmMwNjRhZmU4ZTQ2MSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjM1MjMyMiwKICAicHJvZmlsZUlkIiA6ICI3MmY5MTdjNWQyNDU0OTk0YjlmYzQ1YjVhM2YyMjIzMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGF0X0d1eV9Jc19NZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNzM5ZDdmNGU2NmE3ZGIyZWE2Y2Q0MTRlNGM0YmE0MWRmN2E5MjQ1NWM5ZmM0MmNhYWIwMTQ2NjVjMzY3YWQ1IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjI5MjgzNiwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9iZjZlMWU3ZWQzNjU4NmMyZDk4MDU3MDAyYmMxYWRjOTgxZTI4ODlmN2JkN2I1YjM4NTJiYzU1Y2M3ODAyMjA0IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTY5NzIzODQ0NjgxMiwKICAicHJvZmlsZUlkIiA6ICJmMjc0YzRkNjI1MDQ0ZTQxOGVmYmYwNmM3NWIyMDIxMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJIeXBpZ3NlbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80Y2VjNDAwMDhlMWMzMWMxOTg0ZjRkNjUwYWJiMzQxMGYyMDM3MTE5ZmQ2MjRhZmM5NTM1NjNiNzM1MTVhMDc3IiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjAwOTg2NywKICAicHJvZmlsZUlkIiA6ICJiMGQ0YjI4YmMxZDc0ODg5YWYwZTg2NjFjZWU5NmFhYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lU2tpbl9vcmciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjM3ZGQxOGI1OTgzYTc2N2U1NTZkYzY0NDI0YWY0YjlhYmRiNzVkNGM5ZThiMDk3ODE4YWZiYzQzMWJmMGUwOSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNTkyNDIwNSwKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9mNWYwZDc4ZmUzOGQxZDdmNzVmMDhjZGNmMmExODU1ZDZkYTAzMzdlMTE0YTNjNjNlM2JmM2M2MThiYzczMmIwIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTU1MDkyNjM2MSwKICAicHJvZmlsZUlkIiA6ICI0ZDcwNDg2ZjUwOTI0ZDMzODZiYmZjOWMxMmJhYjRhZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJzaXJGYWJpb3pzY2hlIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzUxOTY3ZGI1ZTMxOTk5MTYyNTIwMjE5MDNjZjRlOTk1MmVmN2NlYzIyMGZhYWNhMWJhNzliYWZlNTkzOGJkODAiCiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIxMjc1NSwKICAicHJvZmlsZUlkIiA6ICI2NGRiNmMwNTliOTk0OTM2YTY0M2QwODEwODE0ZmJkMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVTaWx2ZXJEcmVhbXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWZkNjFlODA1NWY2ZWU5N2FiNWI2MTk2YThkN2VjOTgwNzhhYzM3ZTAwMzc2MTU3YjZiNTIwZWFhYTJmOTNhZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwNjIzOTU4NiwKICAicHJvZmlsZUlkIiA6ICJhYWZmMDUwYTExOTk0NzM1YjEyNDVlNDk0MGFlZjY4NCIsCiAgInByb2ZpbGVOYW1lIiA6ICJMYXN0SW1tb3J0YWwiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTVjMWRjNDdhMDRjZTU3MDAxYThiNzI2ZjAxOGNkZWY0MGI3ZWE5ZDdiZDZkODM1Y2E0OTVhMGVmMTY5Zjg5MyIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9"
    )

    private val bloodMobHeads = setOf(
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwNjQwNTAsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVhNzk4NjBhY2E3OTk0MDdjMGZhYTEwYjFiYmNmNDI5OThmYWQ0ZWJjZjMxZDdhMjE0MTgwODI2YjRhYzk0ZTEifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDExODY2MzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzQ3NzQ4NzExOTBjODc4YzlhMmM0NDk2YzFlMTAyNTdjNmM0ZWExMzgwN2Q3MmMxNWQ3YWM2YWIzYTdhOWE4ZGMifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDAyMDM1NzMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2Y0NjI0YTlhOGM2OWNhMjA0NTA0YWJiMDQzZDQ3NDU2Y2Q5YjA5NzQ5YTM2MzU3NDYyMzAzZjI3NmEyMjlkNCJ9fX0=",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDExNDUyMjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2M5MTllNWI4ZDU2ZjA2MmEyMWQyMjRkZTE0YWY3NzFlMmY1NWQwOWI1OWU3YjA5OWQwOWRhYTU3NTQwYjc5Y2YiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA1MzgzODIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2E4OWY2MzAzYWY4NTg3NzYxMDkxMmRjMDRiOGIxZTg5NzI0NzUyZjBhN2VlYTA1YWI2NTQ3ZTIyODE3OWMwNmYiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5ODk1NTgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY3MjM3ZWRkYWViZGJiZGFhY2ZhOTEyODg1NTYwY2NkYzY1ZGE5M2I0YzNkNTEzNTMyODY4ZWMyM2JiNWI0NDgifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA0OTUwMjgsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2ZmMTg0YzE5ZTcyNTYyM2QzMjgyOGEwYTRlNzQxZTg2ZjEzNWFjNjNkYmM4MjhmZjNjODQ2ODMzOGYzNjgzYiJ9fX0=",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDEwMzA3NjUsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzVjY2NkNTNmNTE5MWMyOWE5ZGM4ZjAxNzBmYmRjNGU1OWU2NjQ3NmFhZTMzZGUyN2I0NjhmMWRlMWI3Y2YzYjIifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5MTc4NzYsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2I1YmE3NmUwMmNhYjcyZmE3ZDhhYzU0Y2VlYzg0OTk3NmFiMGIwMGEwMTA2OGQ2OGMyNjY3NjZiZjcwYzM5OTcifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA3Njk2MTQsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FhMjNjOGNkZTI5NDNjODQyNDlkZTgzNTFiYzM1NDBiZTVmOGFmYWFiYThiMmNiMDMyZmM1YWNhZDc4YTI2OWIifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA4MTg4MDMsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkxNzFmMzViOGY1MDgxNDJiZDhjNjU0MTdkMGYzMjQxNTNhYjkxNDc3MzllZTRkMTBkZWE3MzNjYzgwZWFhMjAifX19",
        "eyJ0aW1lc3RhbXAiOjE1ODYwNDA5NTY0MjIsInByb2ZpbGVJZCI6ImRhNDk4YWM0ZTkzNzRlNWNiNjEyN2IzODA4NTU3OTgzIiwicHJvZmlsZU5hbWUiOiJOaXRyb2hvbGljXzIiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzdkMTJiMmFkZTQxM2E2Y2Q3Y2NhM2M5NWU5NjFiYTlmMGFlNzE2NWZhNDFmYzdiNWQ1ZjA5NGEwMTI0MGM2MDkifX19",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTZjM2UzMWNmYzY2NzMzMjc1YzQyZmNmYjVkOWE0NDM0MmQ2NDNiNTVjZDE0YzljNzdkMjczYTIzNTIifX19",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzE2OTIxMSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODQyMWJhNWI4ZTM1NzNlZjk3YmViNWI0MGUxNWQxNWIyMGYzMDYzMWM0YzUzMzBjM2RlZGEzMDQ3ZGYwZTkyIgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzExMjUwMCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQyMjc3MmY3NjkwNDVmZGM1YmU4MTlhZDY4YjAxYTk3YWMwNGM2MDg4NmQyY2E3YWZlZTM5YjI4MmY3YTM4MyIKICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzM4Njc5NCwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWQ2N2Y5N2Q3ZjgyMTcyOWJlYjM0YTgyYzNmMTM1OTJiNDA0MzlmZTUyNDhlNzI1NzZmZGU3YWExODBiZjc3IgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzIxNTkwNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmIzOTczYTc1MmIyNGEyZjNhYmIwMDM0MjdmNmRiZTZjYTNhNjFkYjBhMWJjZjM1MWM2ZWFiMjdlYzI3ZTUwIgogICAgfQogIH0KfQ==",
        "eyJ0aW1lc3RhbXAiOjE1NzQ0MTkzMTAxNjQsInByb2ZpbGVJZCI6Ijc1MTQ0NDgxOTFlNjQ1NDY4Yzk3MzlhNmUzOTU3YmViIiwicHJvZmlsZU5hbWUiOiJUaGFua3NNb2phbmciLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzEyNzE2ZWNiZjViOGRhMDBiMDVmMzE2ZWM2YWY2MWU4YmQwMjgwNWIyMWViOGU0NDAxNTE0NjhkYzY1NjU0OWMifX19",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTkyMzAyODAxNSwKICAicHJvZmlsZUlkIiA6ICJhMmY4MzQ1OTVjODk0YTI3YWRkMzA0OTcxNmNhOTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJiUHVuY2giLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzI2MDMyNTE3MWE3YmE4NDYwODMwYzBlZWE1MTVjNzU3YTY2NWU1YjE2YTE0MjA3YmExYTMxODI3NTJiZWU4NyIKICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5NTQyODIyMDAyMCwKICAicHJvZmlsZUlkIiA6ICJkYTQ5OGFjNGU5Mzc0ZTVjYjYxMjdiMzgwODU1Nzk4MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJOaXRyb2hvbGljXzIiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjJkOGZkM2FhNTYxN2IxZGFjMGFhZTljODFmNmRkNzBhZDkzYTU5OTQyZjQ2MGQyN2U0ZDU1YTVjYjg5MThlOCIKICAgIH0KICB9Cn0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTZmYzg1NGJiODRjZjRiNzY5NzI5Nzk3M2UwMmI3OWJjMTA2OTg0NjBiNTFhNjM5YzYwZTVlNDE3NzM0ZTExIn19fQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU4OTc5MzA2ODgzOSwKICAicHJvZmlsZUlkIiA6ICIyYzEwNjRmY2Q5MTc0MjgyODRlM2JmN2ZhYTdlM2UxYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOYWVtZSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83ZGU3YmJiZGYyMmJmZTE3OTgwZDRlMjA2ODdlMzg2ZjExZDU5ZWUxZGI2ZjhiNDc2MjM5MWI3OWE1YWM1MzJkIgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5ODk3NzI1OTM1NywKICAicHJvZmlsZUlkIiA6ICJlNzkzYjJjYTdhMmY0MTI2YTA5ODA5MmQ3Yzk5NDE3YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVfSG9zdGVyX01hbiIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMTAwN2M1YjcxMTRhYmVjNzM0MjA2ZDRmYzYxM2RhNGYzYTBlOTlmNzFmZjk0OWNlZGFkYzk5MDc5MTM1YTBiIgogICAgfQogIH0KfQ=="
    )
}
