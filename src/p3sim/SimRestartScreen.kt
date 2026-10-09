package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.index
import com.odtheking.odin.features.ModuleManager
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the keybind.
 *
 * The top half: Restart P3 with Stop above it; on their left the Practice, Teleport and Settings tabs;
 * on their right the skill level and, right of that, your role (class). The bottom half is the tab
 * you're on: the section plan (each section's jobs: who does them) with Advanced under it (spawn and
 * early-enter spots, the leap menu, the bots' options), Settings, Teleport or Practice. A tab's button
 * is green while it's open; a second click goes back to the plan. Toggles are green when on. Every
 * button says what it does when hovered; changes save at once, starts and teleports close the menu.
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim")) {
    private enum class Tab { PLAN, ADVANCED, SETTINGS, TELEPORT, PRACTICE }

    private lateinit var panel: LinearLayout

    // Each time the menu opens: no tab, the section plan.
    init { tab = Tab.PLAN }

    override fun init() {
        super.init()
        val cx = width / 2
        // Restart in the middle of the screen; the rest hangs off it.
        val restartY = height / 2 - 10
        // The skill and role columns end level with Restart's bottom.
        val skillY = restartY - 3 * ROW
        val roleY = restartY - 4 * ROW
        val left = cx - RESTART_W / 2 - GAP - SIDE_W
        val right = cx + RESTART_W / 2 + GAP
        val roleX = right + SIDE_W + GAP

        addRenderableWidget(Button.builder(Component.literal("§aRestart P3")) {
            mc.gui.setScreen(null)
            SimServer.run("restart") { Fight.start(Fight.Start.P3) }
        }.tooltip(tip("Starts P3 over from its beginning, everything reset (Stop: ends it).")).bounds(cx - RESTART_W / 2, restartY, RESTART_W, 20).build())
        addRenderableWidget(Button.builder(Component.literal("§cStop")) {
            mc.gui.setScreen(null)
            SimServer.run("stop") { Fight.end() }
        }.tooltip(tip("Ends the run: the fight stops and the splits clear.")).bounds(cx - RESTART_W / 2, restartY - ROW, RESTART_W, 20).build())

        // Left: the tabs, Practice level with Restart, Teleport and Settings above it.
        tabButton(Tab.SETTINGS, "Settings", "Every setting: death ticks, terminals, your speed, hotbar, gear, the bots, masks...", left, restartY - 2 * ROW)
        tabButton(Tab.TELEPORT, "Teleport", "Every place in the arena: section starts, devices, pads, phases, early-enter spots.", left, restartY - ROW)
        tabButton(Tab.PRACTICE, "Practice", "Practice: a section (your role's part of it), or your own custom one; timed.", left, restartY)

        // Right: the skill level, the chosen one highlighted (its P3 time on hover). Shown names only: the presets keep theirs.
        title("§eSkill Level", right, SIDE_W, skillY)
        val skills = listOf(
            Triple(0, "Normal PF", "The bots play at a normal party finder pace: P3 in about 34 s."),
            Triple(1, "Quality PF", "The bots play at a quality party finder pace: P3 in about 28 s."),
            Triple(2, "Optimal PF", "The bots play at an optimal party finder pace: P3 in about 22 s."),
            Triple(P3Plan.RANDOM + 1, "Theoretical", "The bots play a route planner's roles and times: P3 in about 18 s."),
        )
        for ((k, s) in skills.withIndex()) {
            val (i, name, about) = s
            addRenderableWidget(Button.builder(Component.literal(if (i == P3Plan.skill) "§a§n$name" else name)) {
                P3Plan.chooseSkill(i)
                rebuildWidgets()
            }.tooltip(tip("$about Your jobs become your class's role in it.")).bounds(right, skillY + k * ROW, SIDE_W, 20).build())
        }

        // Right of it: your role (class), one above the other; a bot plays each of the others.
        title("§eRole", roleX, CLASS_W, roleY)
        val roles = P3Plan.preset().roles
        Party.CLASSES.forEachIndexed { i, c ->
            addRenderableWidget(Button.builder(Component.literal(if (c == P3Sim.myClass) "§a§n${Roles.label(c)}" else Roles.label(c))) {
                P3Sim.classS.index = i
                ModuleManager.saveConfigurations()
                rebuildWidgets()
            }.tooltip(tip("Play as ${Roles.label(c)}: your jobs become its ${P3Plan.skillName()} role (${roles[c] ?: "none"}); a bot plays each of the other classes."))
                .bounds(roleX, roleY + i * ROW, CLASS_W, 20).build())
        }

        // The bottom half: the tab.
        panel = LinearLayout.vertical().spacing(2)
        panel.defaultCellSetting().alignHorizontallyCenter()
        when (tab) {
            Tab.PLAN -> planPanel()
            Tab.ADVANCED -> advancedPanel()
            Tab.SETTINGS -> settingsPanel()
            Tab.TELEPORT -> teleportPanel()
            Tab.PRACTICE -> practicePanel()
        }
        panel.arrangeElements()
        val top = restartY + 20 + 10
        panel.setPosition(cx - panel.width / 2, top)
        panel.visitWidgets(this::addRenderableWidget)
        // Advanced: the same place in the plan and in Advanced (both are 5 rows tall).
        if (tab == Tab.PLAN || tab == Tab.ADVANCED)
            addRenderableWidget(tabCell(Tab.ADVANCED, "Advanced", if (tab == Tab.ADVANCED) "Back to the section plan." else "Spawn and early-enter spots, the leap menu, the bots' options.")
                .also { it.setPosition(cx - it.width / 2, top + PANEL_H) })
    }

    override fun isPauseScreen(): Boolean = false

    /** Odin's settings save when the menu closes (dragging the speed slider would write every step). */
    override fun removed() {
        super.removed()
        ModuleManager.saveConfigurations()
    }

    // ------------------------------------------------------------------ the top half

    private fun tabButton(t: Tab, name: String, about: String, x: Int, y: Int) {
        addRenderableWidget(Button.builder(Component.literal(if (tab == t) "§a$name" else name)) {
            tab = if (tab == t) Tab.PLAN else t
            rebuildWidgets()
        }.tooltip(tip("$about Click again: back to the section plan.")).bounds(x, y, SIDE_W, 20).build())
    }

    /** A column's title, centred over it (its first button at [y]). */
    private fun title(t: String, x: Int, w: Int, y: Int) {
        val c = Component.literal(t)
        addRenderableWidget(StringWidget(c, font).also { it.setPosition(x + (w - font.width(c)) / 2, y - 12) })
    }

    // ------------------------------------------------------------------ the plan (default)

    /**
     * Each section's jobs in fixed columns (terminals 1-5, the left and right lever, the device, the
     * gate). Left click: yours or its bot's. Right click a bot's: the next bot does it; right click
     * yours: a stack, its bot does it too with Helpers on (whoever's first).
     */
    private fun planPanel() {
        row(listOf(label("", SEC_W)) + COLUMNS.map { (head, about) -> label("§e$head", JOB_W, about) })
        for (s in 1..4) {
            val cells = arrayOfNulls<String>(COLUMNS.size)
            for (job in P3Plan.jobsIn(s)) cells[column(job)] = job
            row(listOf(label("§6§lS$s", SEC_W, "Section $s's jobs.")) + cells.map { job -> if (job == null) label("", JOB_W) else jobButton(job) })
        }
    }

    private fun jobButton(job: String): Button {
        val mine = P3Plan.isMine(job)
        val doer = P3Plan.doer(job)
        val picked = !mine && doer != P3Plan.roleDoer(job)
        val star = if (P3Plan.isStack(job)) "§e*" else ""
        val text = when {
            mine && P3Plan.isStacked(job) -> "§aYou§e+"
            mine -> "§aYou$star"
            else -> (if (picked) "§b" else "§7") + (doer?.let { Roles.label(it).take(4) } ?: "§8any") + star
        }
        val owners = P3Plan.plan().owners[job].orEmpty()
        val who = when {
            mine && P3Plan.isStacked(job) -> "Yours, stacked: the ${P3Plan.helperOf(job)?.let { Roles.label(it) } ?: "?"} bot does it too with Helpers ${onOff(P3Plan.helper)}§r."
            mine -> "Yours."
            doer != null -> "The ${Roles.label(doer)} bot does it${if (picked) " (you picked it; its role's: ${P3Plan.roleDoer(job)?.let { Roles.label(it) } ?: "any"})" else ""}."
            else -> "Whichever bot is least busy does it."
        }
        val shared = if (owners.size > 1) " In the ${owners.joinToString(" and ") { Roles.label(it) }} roles (a stack)." else ""
        val click = if (mine) "Left click: the bot's. Right click: ${if (P3Plan.isStacked(job)) "not a stack" else "a stack (its bot does it too)"}."
        else "Left click: yours. Right click: the next bot."
        return ClickButton(JOB_W, text, tip("${jobName(job)}. $who$shared $click"),
            left = { P3Plan.toggle(job); rebuildWidgets() },
            right = { if (P3Plan.isMine(job)) P3Plan.toggleStacked(job) else P3Plan.cycleDoer(job); rebuildWidgets() })
    }

    // ------------------------------------------------------------------ advanced

    /** Your spawn and the early-enter spots (this skill and class), the leap menu (2x2 as in game), the bots' options. */
    private fun advancedPanel() {
        val skill = P3Plan.skillName()
        val me = Roles.label(P3Sim.myClass)
        val spawn = P3Plan.customSpot("spawn")
        row(listOf(
            label("§eSpawn", LABEL_W, "Where Restart P3 puts you."),
            change(if (P3Plan.hasCustomSpot("spawn")) "§aSet here" else "Set here", 60,
                "Restart P3 puts you where you stand now, facing as you are (as $me in $skill). Now: ${spawn?.let { at(it) } ?: "your first S1 job's spot"}.") { here()?.let { P3Plan.setCustomSpot("spawn", it) } },
            change("Default", 50, "Back to the default spawn (Normal PF's, as $me).") { P3Plan.setCustomSpot("spawn", null) },
        ))
        row(listOf<LayoutElement>(label("§eEarly enters", LABEL_W, "Where each early enter stands, whoever does it.")) + P3Plan.earlyEnters.map { ee ->
            val who = ee.owner?.let { if (ee.byYou) "you" else "the ${Roles.label(it)} bot" } ?: "nobody in $skill"
            change(if (P3Plan.hasCustomSpot(ee.key)) "§a${ee.label}" else ee.label, 48,
                "${ee.label} (${who}) stands where you stand now, facing as you are (as $me in $skill). Now: ${at(P3Plan.eeSpot(ee))}.") { here()?.let { P3Plan.setCustomSpot(ee.key, it) } }
        } + change("Defaults", 56, "Every early enter back to its default spot (Normal PF's) for $me in $skill.") { P3Plan.earlyEnters.forEach { P3Plan.setCustomSpot(it.key, null) } })

        // The leap menu as it opens: slots 1 2 over 3 4. Left click: the next class; right click: the one before.
        val order = P3Plan.botOrder()
        val grid = LinearLayout.vertical().spacing(2)
        for (r in 0..1) {
            val line = LinearLayout.horizontal().spacing(2)
            for (k in 0..1) {
                val slot = r * 2 + k + 1
                val c = order.getOrNull(slot - 1)
                line.addChild(ClickButton(LEAP_W, "§7$slot: §f${c?.let { Roles.label(it) } ?: "-"}",
                    tip("Leap menu slot $slot: ${c?.let { Roles.label(it) } ?: "nobody"}. Left click: swap with the next slot's class; right click: with the one before. (Your own order: Leap Sort goes to Custom.)"),
                    left = { P3Plan.cycleSlot(slot); P3Plan.save(); rebuildWidgets() },
                    right = { P3Plan.cycleSlot(slot, back = true); P3Plan.save(); rebuildWidgets() }))
            }
            grid.addChild(line)
        }
        val side = LinearLayout.vertical().spacing(2)
        side.addChild(change("Leap Sort: " + if (P3Plan.odinSort) "§aOdin" else "Custom", 100,
            "The leap menu's order. Odin (green): as Odin's Leap Menu sorts it (by class). Custom: your own, set with the slots.") { P3Plan.odinSort = !P3Plan.odinSort; P3Plan.save() })
        val gap = LinearLayout.horizontal().spacing(2)
        gap.addChild(change("-", 16, "Bots leap 0.25 s closer together.") { P3Plan.leapGap = (P3Plan.leapGap - 0.25).coerceAtLeast(0.05); P3Plan.save() })
        gap.addChild(label("§f${"%.2f".format(Locale.ROOT, P3Plan.leapGap)}s apart", 64, "How far apart the bots' leaps onto an early enterer come."))
        gap.addChild(change("+", 16, "Bots leap 0.25 s further apart.") { P3Plan.leapGap = (P3Plan.leapGap + 0.25).coerceAtMost(5.0); P3Plan.save() })
        side.addChild(gap)
        row(listOf(label("§eLeaps", LABEL_W, "The leap menu (2x2 as it opens in game) and the bots' leaps."), grid, side))

        row(listOf(
            toggle("Helpers", P3Plan.helper, 64, "On (green): the bots help with your stacks: a stacked job of yours (right click it in the plan) and the preset's stacks are done by their bot too, whoever gets there first.") { P3Plan.helper = !P3Plan.helper; P3Plan.save() },
            toggle("Wait For You", P3Plan.waitForYou, 84, "On (green): a section's last bot job waits until you're at your early enter for the next section.") { P3Plan.waitForYou = !P3Plan.waitForYou; P3Plan.save() },
            toggle("Bots", P3Sim.bots, 44, "On (green): the other four are bots playing their roles.") { P3Sim.botsS.value = !P3Sim.bots },
            toggle("Real Movement", P3Sim.realMoves, 90, P3Sim.realMovesS.description) { P3Sim.toggleRealMoves() },
            change("Reset Role", 70, "Your jobs, the bots you picked and your stacks back to the ${Roles.label(P3Sim.myClass)}'s $skill role.") { P3Plan.resetMine() },
        ))
    }

    /** Where you stand and look now (a tenth of a block; y to the hundredth, so a slab's height stays). */
    private fun here(): Spots.Spot? = mc.player?.let {
        Spots.Spot("set", Math.round(it.x * 10) / 10.0, Math.floor(it.y * 100) / 100.0, Math.round(it.z * 10) / 10.0, it.yRot, it.xRot)
    }

    private fun at(p: Spots.Spot) = "%.1f, %.1f, %.1f".format(Locale.ROOT, p.x, p.y, p.z)

    // ------------------------------------------------------------------ settings

    private fun settingsPanel() {
        val forced = P3Sim.forcedTerminal
        if (forced != null) lastType = forced
        val speedRow = LinearLayout.horizontal().spacing(2)
        speedRow.addChild(change("-", 54, "10 less speed.") { setSpeed(P3Sim.speed - 10) })
        speedRow.addChild(change("+", 54, "10 more speed.") { setSpeed(P3Sim.speed + 10) })
        // Your hotbar: P1/P2's or P3's, whichever part of the fight you are in (HotbarLayout).
        val p3Part = Fight.phase !is P1Maxor && Fight.phase !is P2Storm
        val part = if (p3Part) "P3" else "P1/P2"
        val saved = HotbarLayout.has(p3Part)
        row(listOf(
            stack("§eDeath Ticks", 84, P3Sim.deathTicksS.description, DEATH_TICKS.mapIndexed { i, (name, about) ->
                change(pick(name, P3Sim.deathTicks == i), 84, about) { P3Sim.deathTicksS.index = i }
            } + listOf(
                toggle("Real Masks", P3Sim.realMasks, 84, P3Sim.realMasksS.description) { P3Sim.realMasksS.value = !P3Sim.realMasks; server { Sim.player?.let { Masks.equip(it) } } },
                change("Mask: ${if (P3Sim.wornMaskS.index == 0) "Spirit" else "Bonzo"}", 84, P3Sim.wornMaskS.description) { P3Sim.wornMaskS.index = 1 - P3Sim.wornMaskS.index; server { Sim.player?.let { Masks.equip(it) } } },
                change("Pet: ${if (P3Sim.phoenix) "Phoenix" else "Black Cat"}", 84, P3Sim.phoenixS.description) { P3Sim.phoenixS.value = !P3Sim.phoenix; server { Sim.player?.let { Fight.applySpeed(it) } } },
            )),
            // Terminals: random or one type for all.
            stack("§eTerminals", 66, P3Sim.terminalS.description, listOf(
                change(pick("Random", forced == null), 66, "Each terminal a random type, as on Hypixel.") { P3Sim.terminalS.index = 0 },
            ) + Terminals.Type.entries.map { t ->
                change(pick(TYPE_NAMES.getValue(t).first, forced == t), 66, TYPE_NAMES.getValue(t).second) { lastType = t; P3Sim.terminalS.index = t.ordinal + 1 }
            }),
            stack("§eSpeed", 110, P3Sim.speedS.description, listOf(
                SpeedSlider(110),
                speedRow,
                change("Ping: ${P3Sim.ping}ms", 110, P3Sim.pingS.description) { P3Sim.pingS.value = PINGS[(PINGS.indexOf(P3Sim.ping) + 1).mod(PINGS.size)] },
                toggle("Jitter", P3Sim.jitterS.value, 110, P3Sim.jitterS.description) { P3Sim.jitterS.value = !P3Sim.jitterS.value },
                toggle("No Melodies", P3Sim.noMelodies, 110, P3Sim.noMelodiesS.description) { P3Sim.noMelodiesS.value = !P3Sim.noMelodies },
                toggle("Click Limit", P3Sim.clickLimitS.value, 110, P3Sim.clickLimitS.description) { P3Sim.clickLimitS.value = !P3Sim.clickLimitS.value },
            )),
            stack("§eTimings", 106, "The fight's numbers.", listOf(
                stepper("Goldor kill", "${P3Sim.goldorKill}t", P3Sim.goldorKillS.description, { P3Sim.goldorKillS.value = (P3Sim.goldorKill - 1).coerceAtLeast(10) }, { P3Sim.goldorKillS.value = (P3Sim.goldorKill + 1).coerceAtMost(120) }),
                stepper("Shortbow", "${P3Sim.shortbowCooldown}t", P3Sim.shortbowCooldownS.description, { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown - 1).coerceAtLeast(1) }, { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown + 1).coerceAtMost(20) }),
                stepper("Hydra", "${P3Sim.hydraStart}", P3Sim.hydraStartS.description, { P3Sim.hydraStartS.value = (P3Sim.hydraStart - 1).coerceAtLeast(0) }, { P3Sim.hydraStartS.value = (P3Sim.hydraStart + 1).coerceAtMost(10) }),
                toggle("Infinite Charges", P3Sim.breakerInfinite, 106, "On (green): the Dungeonbreaker never runs out of charges.") { P3Sim.toggleBreakerInfinite() },
                toggle("Perma Break", P3Sim.breakerPerma, 106, "On (green): blocks the Dungeonbreaker breaks stay broken (through restarts too) until you turn it off; then they all come back.") {
                    P3Sim.toggleBreakerPerma()
                    if (!P3Sim.breakerPerma) server { SimItems.unPerma() }
                },
            )),
            stack("§eFight", 92, "How the fight goes on.", listOf(
                toggle("Lava Bounce", P3Sim.lava, 92, P3Sim.lavaS.description) { P3Sim.lavaS.value = !P3Sim.lava },
                toggle("Stop After P3", P3Sim.p3Only, 92, P3Sim.p3OnlyS.description) { P3Sim.p3OnlyS.value = !P3Sim.p3Only },
                toggle("Start On Join", P3Sim.autoStart, 92, P3Sim.autoStartS.description) { P3Sim.autoStartS.value = !P3Sim.autoStart },
                toggle("Terror At Terms", P3Sim.terrorAtTerms, 92, P3Sim.terrorAtTermsS.description) { P3Sim.toggleTerrorAtTerms() },
                toggle("Section Times", P3Sim.showTimes, 92, P3Sim.showTimesS.description) { P3Sim.showTimesS.value = !P3Sim.showTimes },
                toggle("Hide Players", P3Sim.hidePlayers, 92, "On (green): other players out of sight.") { P3Sim.toggleHidePlayers() },
                toggle("Debug Bots", P3Sim.debugBots, 92, P3Sim.debugBotsS.description) { P3Sim.debugBotsS.value = !P3Sim.debugBots },
            )),
            stack("§eOther", 100, "Your hotbar, recording, leaving.", listOf(
                act(if (saved) "§aSave $part Hotbar" else "Save $part Hotbar", 100, "Saves where your items are right now (hotbar and inventory), the slot you hold, what you wear and your pet: every $part hotbar reset (a start, Reset Items) lays them out so. Arrange them first.") {
                    server { Sim.player?.let { EngineerClient.msg(HotbarLayout.save(it, p3Part)) } }
                },
                act("Default Hotbar", 100, "Forgets your saved $part layout: its resets go back to the sim's default.") {
                    server { EngineerClient.msg(HotbarLayout.reset(p3Part)) }
                }.also { it.active = saved },
                act("Reset Items", 100, "Your hotbar back as it is at a start.") { server { Sim.player?.let { SimItems.giveHotbar(it, p3Part) } } },
                toggle("Record", P3Sim.record, 100, P3Sim.recordS.description) { P3Sim.recordS.value = !P3Sim.record },
                act("§7Leave", 100, "Leaves the sim world.") { SimWorld.leave() },
            )),
        ))
    }

    /** A column: its title, then [items] one under another. */
    private fun stack(title: String, w: Int, about: String, items: List<LayoutElement>): LayoutElement {
        val c = LinearLayout.vertical().spacing(2)
        c.defaultCellSetting().alignHorizontallyCenter()
        c.addChild(label(title, w, about))
        items.forEach { c.addChild(it) }
        return c
    }

    private fun stepper(name: String, value: String, about: String, down: () -> Unit, up: () -> Unit): LayoutElement {
        val r = LinearLayout.horizontal().spacing(1)
        r.addChild(change("-", 14, "Less.", down))
        r.addChild(label("§7$name §f$value", 76, about))
        r.addChild(change("+", 14, "More.", up))
        return r
    }

    private fun setSpeed(v: Int) {
        val s = v.coerceIn(MIN_SPEED, MAX_SPEED)
        if (s == P3Sim.speed) return
        P3Sim.speedS.value = s
        // Now, not at the next start: Black Cat's +100 and the speed cap come with it (Fight.applySpeed).
        server { Sim.player?.let { Fight.applySpeed(it) } }
    }

    /** Your speed, 100 to 750 in steps of 10. */
    private inner class SpeedSlider(w: Int) : AbstractSliderButton(0, 0, w, 20, Component.empty(), (P3Sim.speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED).toDouble()) {
        init {
            updateMessage()
            setTooltip(tip(P3Sim.speedS.description))
        }
        private fun speed() = MIN_SPEED + Math.round(value * (MAX_SPEED - MIN_SPEED) / 10).toInt() * 10
        override fun updateMessage() {
            message = Component.literal("Speed: ${speed()}" + if (P3Sim.phoenix) " §8(Phoenix out)" else " §8(${speed() + 100} with Black Cat)")
        }
        override fun applyValue() = setSpeed(speed())
    }

    /** Practice's start timer, 0 to 5 s in tenths. */
    private inner class DelaySlider(w: Int) : AbstractSliderButton(0, 0, w, 20, Component.empty(), Practice.startDelay / 5.0) {
        init {
            updateMessage()
            setTooltip(tip("Every practice start: you stand on its start this long before anything is up (terminals, levers, Goldor) and the clock runs. 0: at once."))
        }
        private fun delay() = Math.round(value * 50) / 10.0
        override fun updateMessage() { message = Component.literal("Timer: ${"%.1f".format(Locale.ROOT, delay())}s") }
        override fun applyValue() = Practice.setStartDelay(delay())
    }

    // ------------------------------------------------------------------ teleport

    /** A column for each group (a long one split in two), early enters last. */
    private fun teleportPanel() {
        val cols = ArrayList<LayoutElement>()
        for ((group, spots) in Spots.teleportGroups) {
            spots.chunked(4).forEachIndexed { i, chunk ->
                cols += stack(if (i == 0) "§e$group" else "", SPOT_W, "Teleports: $group.", chunk.map { spot ->
                    act(spot.name.substringBefore(" (").trim(), SPOT_W, "To ${spot.name}: ${xyz(spot.x, spot.y, spot.z)}, facing ${facing(spot.yaw)}.") { tp(spot.x, spot.y, spot.z, spot.yaw, spot.pitch) }
                })
            }
        }
        cols += stack("§eEarly Enters", 70, "The early-enter spots (set in Advanced).", P3Plan.earlyEnters.map { ee ->
            val who = ee.owner?.let { if (ee.byYou) "yours" else "the ${Roles.label(it)}'s" } ?: "nobody's in this skill"
            val s = P3Plan.eeSpot(ee)
            act(ee.label, 70, "To the ${ee.label} spot (${who}): ${xyz(s.x, s.y, s.z)}. Set it in Advanced.") { tp(s.x, s.y, s.z, s.yaw, s.pitch) }
        })
        row(cols)
    }

    private fun tp(x: Double, y: Double, z: Double, yaw: Float?, pitch: Float?) = server {
        GhostCapture.invalidate("a menu teleport")
        Sim.player?.let { Sim.tp(it, x, y, z, yaw, pitch) }
    }

    private fun xyz(x: Double, y: Double, z: Double) = "%.1f, %.1f, %.1f".format(Locale.ROOT, x, y, z)

    private fun facing(yaw: Float) = when (Math.floorMod(Math.round(yaw / 90f), 4)) { 0 -> "south"; 1 -> "west"; 2 -> "north"; else -> "east" }

    // ------------------------------------------------------------------ practice

    /** Practice: your custom one, then the presets. */
    private fun practicePanel() {
        // Custom: your start, the section's jobs you pick, checkpoints in order.
        val start = Practice.customStart
        val cps = Practice.checkpoints.size
        // The row: the start's section once it's set, else the one you're in now.
        val sec = if (start != null) Practice.customSection else mc.player?.let { Practice.sectionAt(it.x, it.y, it.z) } ?: 1
        val picked = Practice.customJobsIn(sec)
        row(listOf(label("§eCustom", LABEL_W, "Your own practice: a start position (its section is where you stand), that section's jobs you pick and checkpoints to reach in order (within 1 block, the same height), all timed.")))
        row(listOf(
            change(if (start != null) "§aStart Position" else "Start Position", 100,
                "Click: the custom practice starts where you stand, facing as you are; its section is the one you're in (or the nearest). " +
                    (start?.let { "Now: S${Practice.customSection}, ${at(it)}." } ?: "Not set.")) {
                here()?.let { h -> val n = Practice.setStart(h); EngineerClient.msg("§7Custom practice start: §fS$n§7, ${at(h)}.") }
            },
            change(if (cps > 0) "§aCheckpoint §7($cps)" else "Checkpoint", 100,
                "Click: a checkpoint where you stand (reached within 1 block across, at this exact height; in order). Now: $cps.") {
                here()?.let { h -> Practice.addCheckpoint(h); EngineerClient.msg("§7Checkpoint §f${Practice.checkpoints.size}§7: ${at(h)}.") }
            },
            change("§cClear", 40, "Clears the custom practice: start, jobs and checkpoints.") { Practice.clearCustom(); EngineerClient.msg("§7Custom practice cleared.") },
            DelaySlider(96),
        ))
        val cells = arrayOfNulls<String>(COLUMNS.size)
        for (job in P3Plan.jobsIn(sec)) cells[column(job)] = job
        row(listOf(label("§6§lS$sec", SEC_W, if (start != null) "The custom practice's section: where its start is." else "The section you're in (the start's, once it's set).")) + cells.map { job ->
            if (job == null) label("", JOB_W)
            else {
                val on = job in picked
                change((if (on) "§a" else "§8") + COLUMNS[column(job)].first, JOB_W, "${jobName(job)}: ${if (on) "yours to do in the custom practice" else "done at its start"}. Click: ${if (on) "done at the start" else "yours"}.") { Practice.toggleJob(job, sec) }
            }
        })
        row(listOf(act(if (start != null) "§aStart Custom" else "§8Start Custom", 100,
            if (start != null) "Starts the custom practice: S$sec, from your start position, your jobs then the $cps checkpoint(s), timed." else "Set the Start Position first.") { server { Practice.startCustom() } }
            .also { it.active = start != null }))

        // Presets, a column for each section that has some.
        row(listOf(label("§ePresets", LABEL_W, "Set practices: a start, the jobs and checkpoints (the rest of the section done), and their own start timer.")))
        // A long section takes more than one column (6 a column), its title over the first.
        row(Practice.presets().groupBy { it.section }.toSortedMap().flatMap { (sec, list) ->
            list.chunked(6).mapIndexed { i, part ->
                stack(if (i == 0) "§6S$sec" else "", 64, "Section $sec's presets.", part.map { p ->
                    act(p.name, 64, "Practice ${p.name} (S$sec): ${p.jobs.joinToString(", ") { jobName(it) }}" +
                        (if (p.checkpoints.isNotEmpty()) ", then ${p.checkpoints.size} checkpoint(s)" else "") +
                        (p.delay?.let { ". Start timer ${"%.1f".format(Locale.ROOT, it)}s." } ?: ". Start timer: the slider's.")) { server { Practice.startPreset(p.name) } }
                })
            }
        })
    }

    // ------------------------------------------------------------------ pieces

    private fun text(t: String) { panel.addChild(StringWidget(Component.literal(t), font)) }

    private fun row(widgets: List<LayoutElement>) {
        val r = LinearLayout.horizontal().spacing(2)
        panel.addChild(r)
        widgets.forEach { r.addChild(it) }
    }

    private fun tip(text: String) = Tooltip.create(Component.literal(text))

    /** Text centred in a cell [w] wide, [about] on hover. */
    private fun label(t: String, w: Int, about: String? = null): LayoutElement {
        val cell = FrameLayout().setMinDimensions(w, 20)
        if (t.isNotEmpty()) cell.addChild(StringWidget(Component.literal(t), font).also { if (about != null) it.setTooltip(tip(about)) })
        return cell
    }

    /** Changes something: saved, and the menu stays open (rebuilt) to show it. */
    private fun change(label: String, w: Int, about: String, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) {
            run()
            ModuleManager.saveConfigurations()
            rebuildWidgets()
        }.tooltip(tip(about)).width(w).build()

    /** A toggle: green while on. */
    private fun toggle(name: String, on: Boolean, w: Int, about: String, run: () -> Unit) = change(if (on) "§a$name" else name, w, about, run)

    /** A tab's button in the bottom half (Advanced): green while it's open. */
    private fun tabCell(t: Tab, name: String, about: String): Button =
        Button.builder(Component.literal(if (tab == t) "§a$name" else name)) {
            tab = if (tab == t) Tab.PLAN else t
            rebuildWidgets()
        }.tooltip(tip(about)).width(100).build()

    /** Closes the menu, back in the game, and does it. */
    private fun act(label: String, w: Int, about: String, run: () -> Unit): Button =
        Button.builder(Component.literal(label)) { mc.gui.setScreen(null); run() }.tooltip(tip(about)).width(w).build()

    /** One of a few choices: the chosen one green and underlined. */
    private fun pick(name: String, on: Boolean) = if (on) "§a§n$name" else name

    private fun onOff(b: Boolean) = if (b) "§aON" else "§cOFF"

    private fun server(run: () -> Unit) = SimServer.run("menu") { run() }

    /** A button that takes right clicks too ([left] / [right]). */
    private class ClickButton(w: Int, text: String, tooltip: Tooltip, private val left: () -> Unit, private val right: () -> Unit) :
        Button(0, 0, w, 20, Component.literal(text), { left() }, { it.get() }) {
        init { setTooltip(tooltip) }
        override fun isValidClickButton(info: MouseButtonInfo) = info.button() == 0 || info.button() == 1
        override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
            EngineerClient.logger.info("[ec] p3sim menu click: button ${event.button()} on ${message.string}")
            if (event.button() == 1) right() else left()
        }
        override fun extractContents(g: net.minecraft.client.gui.GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partial: Float) {
            extractDefaultSprite(g)
            extractDefaultLabel(g.textRendererForWidget(this, net.minecraft.client.gui.GuiGraphicsExtractor.HoveredTextEffects.NONE))
        }
    }

    private companion object {
        const val RESTART_W = 100
        /** The plan's and Advanced's height (5 rows) plus a gap: where the Advanced button goes. */
        const val PANEL_H = 5 * 20 + 4 * 2 + 4
        const val GAP = 10
        const val ROW = 22
        const val SIDE_W = 90
        const val CLASS_W = 70
        const val LABEL_W = 64
        const val SEC_W = 24
        const val JOB_W = 40
        const val LEAP_W = 90
        const val SPOT_W = 92
        const val MIN_SPEED = 100
        const val MAX_SPEED = 750

        /** The tab open (the plan each time the menu opens). */
        private var tab = Tab.PLAN

        /** The type a terminal type pick last chose. */
        var lastType = Terminals.Type.ORDER

        val PINGS = listOf(0, 50, 100, 150, 200, 300)

        /** Terminals 1-5, then L, R, D, G, as [P3Plan.short] names them. */
        val COLUMNS = (1..5).map { "$it" to "Terminal $it." } + listOf(
            "L" to "The left lever, coming in along the track (S1 west, S2 high, S3 west, S4 low).",
            "R" to "The right lever (S1 east, S2 low, S3 east, S4 high).",
            "D" to "The section's device: S1 Simon Says, S2 Lights, S3 Arrows, S4 Target (i4).",
            "G" to "The section's gate: blown with Superboom or a Dungeonbreaker (or open by itself 5 s after the section ends).",
        )

        fun column(job: String): Int = when (val short = P3Plan.short(job)) {
            "L" -> 5; "R" -> 6; "D" -> 7; "G" -> 8
            else -> ((short.toIntOrNull() ?: 1) - 1).coerceIn(0, 4)
        }

        /** "S1 T2" -> "S1 terminal 2", "gate 3" -> "S3 gate", "S2 Lights" -> "S2 device (Lights)"; levers as they are. */
        fun jobName(job: String): String {
            if (job.startsWith("gate ")) return "S${job.removePrefix("gate ")} gate"
            val s = job.substringBefore(' ')
            return when (P3Plan.short(job)) {
                "L", "R", "G" -> job
                "D" -> "$s device (${job.substringAfter(' ').let { if (it == "SS") "Simon Says" else it }})"
                else -> "$s terminal ${P3Plan.short(job)}"
            }
        }

        val DEATH_TICKS = listOf(
            "Off" to "No death ticks: being ahead of Goldor never hurts.",
            "Warn" to "A death tick only says so: Goldor's line and a title. Nothing is used up and you don't die.",
            "Masks" to "As on Hypixel: your Spirit Mask, Bonzo's Mask and Phoenix save you; with none left you die (back to the section's start).",
        )

        val TYPE_NAMES = mapOf(
            Terminals.Type.ORDER to ("Order" to "Every terminal Click in order!: the panes 1 to 14 in order."),
            Terminals.Type.PANES to ("Panes" to "Every terminal Correct all the panes!: every red pane to green."),
            Terminals.Type.RUBIX to ("Rubix" to "Every terminal Change all to same color!: every pane to one colour."),
            Terminals.Type.STARTS to ("Starts" to "Every terminal What starts with: 'X'?: every item whose name starts with the letter."),
            Terminals.Type.SELECT to ("Select" to "Every terminal Select all the X items!: every item of the colour."),
            Terminals.Type.MELODY to ("Melody" to "Every terminal Melody: each row's button as the pane passes it."),
        )
    }
}
