package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.index
import com.odtheking.odin.features.ModuleManager
import net.minecraft.world.phys.Vec3
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * The sim's main menu: Esc > P3 Sim Menu, the SkyBlock Menu star (hotbar), `/p3sim` or the keybind.
 *
 * The top half: Restart P3 with Stop above it; on their left the Practice, Plan, Teleport and Settings tabs;
 * on their right the skill level and, right of that, your role (class). The bottom half is the tab
 * you're on (none when the menu opens): the section plan (each section's jobs: who does them) with Advanced
 * under it (spawn and early-enter spots, the leap menu, the bots' options), Settings, Teleport or Practice.
 * A tab's button is green while it's open; a second click closes it. Toggles are green when on. Every
 * button says what it does when hovered; changes save at once, starts and teleports close the menu.
 */
class SimRestartScreen : Screen(Component.literal("P3 Sim Beta")) {
    private enum class Tab { PLAN, ADVANCED, SETTINGS, TELEPORT, PRACTICE, BOSS }

    private lateinit var panel: LinearLayout

    // Each time the menu opens: the tab and Practice section it closed on.
    init { tab = Tab.entries.firstOrNull { it.name == Practice.menuTab }; practiceSec = Practice.menuSection }

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
            mc.setScreen(null)
            SimActions.send("start", "what" to Fight.Start.P3.name)
        }.tooltip(tip("Starts P3 over from its beginning, everything reset (Stop: ends it)." + if (inParty) " For the whole party." else "")).bounds(cx - RESTART_W / 2, restartY, RESTART_W, 20).build())
        addRenderableWidget(Button.builder(Component.literal("§cStop")) {
            mc.setScreen(null)
            SimActions.send("stop")
        }.tooltip(tip("Ends the run: the fight stops and the splits clear." + if (inParty) " For the whole party." else "")).bounds(cx - RESTART_W / 2, restartY - ROW, RESTART_W, 20).build())

        party()

        // Left: the tabs, Practice level with Restart, then Plan, Teleport, Settings and Boss above it.
        tabButton(Tab.BOSS, "Boss", "The boss fight: where the run stops (after terms, after Goldor).", left, restartY - 4 * ROW)
        tabButton(Tab.SETTINGS, "Settings", "Every setting: death ticks, terminals, your speed, hotbar, gear, the bots, masks...", left, restartY - 3 * ROW)
        tabButton(Tab.TELEPORT, "Teleport", "Every place in the arena: section starts, devices, pads, phases, early-enter spots.", left, restartY - 2 * ROW)
        tabButton(Tab.PLAN, "Plan", "The section plan: each section's jobs and who does them (yours, a bot's), with Advanced under it.", left, restartY - ROW, Tab.ADVANCED)
        tabButton(Tab.PRACTICE, "Practice", "Practice: a section (your role's part of it), or your own custom one; timed.", left, restartY)

        // Right: the skill level, the chosen one highlighted (its P3 time on hover). Shown names only: the presets keep theirs.
        title("§eSkill Level", right, SIDE_W, skillY)
        val skills = listOf(
            Triple(0, "Normal PF", "The bots play at a normal party finder pace: P3 in about 34 s."),
            Triple(1, "Quality PF", "The bots play at a quality party finder pace: P3 in about 28 s."),
            Triple(2, "Optimal PF", "The bots play at an optimal party finder pace: P3 in about 22 s."),
            Triple(P3Plan.THEORETICAL, "Theoretical", "The fastest terms, in theory: a far EE2, a high EE3, an EE4 by the core."),
        )
        for ((k, s) in skills.withIndex()) {
            val (i, name, about) = s
            addRenderableWidget(Button.builder(Component.literal(if (i == P3Plan.skill) "§a§n$name" else name)) {
                P3Plan.chooseSkill(i)
                rebuildWidgets()
            }.tooltip(tip("$about Your jobs become your class's role in it.")).bounds(right, skillY + k * ROW, SIDE_W, 20).build())
        }

        // Right of it: your role (class), one above the other; a bot plays each of the others (in a party: the
        // classes nobody plays). A class another player has is shown with their name: taking it swaps you.
        title("§eRole", roleX, CLASS_W, roleY)
        val roles = P3Plan.preset().roles
        Party.CLASSES.forEachIndexed { i, c ->
            val other = PartyClient.team.firstOrNull { it.player && it.clazz == c }
            val text = when {
                c == P3Sim.myClass -> "§a§n${Roles.label(c)}"
                other != null -> "§b${Roles.label(c).take(4)} §7${other.name.take(6)}"
                else -> Roles.label(c)
            }
            addRenderableWidget(Button.builder(Component.literal(text)) {
                P3Sim.classS.value = i
                ModuleManager.saveConfigurations()
                rebuildWidgets()
            }.tooltip(tip(if (other != null) "${other.name} plays the ${Roles.label(c)}. Click: you take it and they get yours (${Roles.label(P3Sim.myClass)})."
                else "Play as ${Roles.label(c)}: your jobs become its ${P3Plan.skillName()} role (${roles[c] ?: "none"}); a bot plays each class no player plays."))
                .bounds(roleX, roleY + i * ROW, CLASS_W, 20).build())
        }

        // The bottom half: the tab.
        panel = LinearLayout.vertical().spacing(2)
        panel.defaultCellSetting().alignHorizontallyCenter()
        // By identity, not a when over the enum (its ordinal table goes stale when the tabs change in a hotswapped game).
        val t = tab
        if (t === Tab.PLAN) planPanel()
        else if (t === Tab.ADVANCED) advancedPanel()
        else if (t === Tab.SETTINGS) settingsPanel()
        else if (t === Tab.TELEPORT) teleportPanel()
        else if (t === Tab.PRACTICE) practicePanel()
        else if (t != null && t === Tab.BOSS) bossPanel()
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
        Practice.saveMenu(tab?.name, practiceSec.takeIf { it in 1..4 } ?: 1)
        ModuleManager.saveConfigurations()
    }

    // ------------------------------------------------------------------ the top half

    /** A tab's button on the left: green while it (or [also], a tab inside it) is open; a second click closes it. */
    private fun tabButton(t: Tab, name: String, about: String, x: Int, y: Int, also: Tab? = null) {
        val open = tab === t || (also != null && tab === also)
        addRenderableWidget(Button.builder(Component.literal(if (open) "§a$name" else name)) {
            tab = if (open) null else t
            rebuildWidgets()
        }.tooltip(tip("$about Click again: closes it.")).bounds(x, y, SIDE_W, 20).build())
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
        // Another player plays that class: theirs (their own plan decides), not a bot's.
        val mate = if (mine) null else PartyClient.team.firstOrNull { it.player && it.clazz == doer }
        // Which bot does a job is the host's pick in a party.
        val joiner = PartyClient.joiner
        val text = when {
            mine && P3Plan.isStacked(job) -> "§aYou§e+"
            mine -> "§aYou$star"
            mate != null -> "§d${mate.name.take(4)}$star"
            else -> (if (picked) "§b" else "§7") + (doer?.let { Roles.label(it).take(4) } ?: "§8any") + star
        }
        val owners = P3Plan.plan().owners[job].orEmpty()
        val who = when {
            mine && P3Plan.isStacked(job) -> "Yours, stacked: the ${P3Plan.helperOf(job)?.let { Roles.label(it) } ?: "?"} bot does it too with Helpers ${onOff(P3Plan.helper)}§r."
            mine -> "Yours."
            mate != null -> "${mate.name} plays the ${Roles.label(mate.clazz)}: theirs, unless their own plan says otherwise."
            doer != null -> "The ${Roles.label(doer)} bot does it${if (picked) " (you picked it; its role's: ${P3Plan.roleDoer(job)?.let { Roles.label(it) } ?: "any"})" else ""}."
            else -> "Whichever bot is least busy does it."
        }
        val shared = if (owners.size > 1) " In the ${owners.joinToString(" and ") { Roles.label(it) }} roles (a stack)." else ""
        val click = if (mine) "Left click: the bot's. Right click: ${if (P3Plan.isStacked(job)) "not a stack" else "a stack (its bot does it too)"}."
        else if (joiner) "Left click: yours. (Which bot does a job is the host's pick.)"
        else "Left click: yours. Right click: the next bot."
        return ClickButton(JOB_W, text, tip("${jobName(job)}. $who$shared $click"),
            left = { P3Plan.toggle(job); rebuildWidgets() },
            right = { if (P3Plan.isMine(job)) P3Plan.toggleStacked(job) else if (!joiner) P3Plan.cycleDoer(job); rebuildWidgets() })
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
        row(listOf<LayoutElement>(label("§eEarly enters", LABEL_W, "Where each early enter stands, whoever does it.")) + P3Plan.earlyEnters.filter { it.into != 4 || it.on }.map { ee ->
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
            toggle("Bots", P3Sim.bots, 44, "The plan does the other four's jobs at its times either way. On (green): bots show them (just the look: a late one is put on its job). Off: nobody's shown.") { P3Sim.botsS.value = !P3Sim.bots },
            toggle("Real Movement", P3Sim.realMoves, 90, P3Sim.realMovesS.description) { P3Sim.toggleRealMoves() },
            change("Reset Role", 70, "Your jobs, the bots you picked and your stacks back to the ${Roles.label(P3Sim.myClass)}'s $skill role.") { P3Plan.resetMine() },
        ))

        // Your best runs: a class's bot can play your fastest P3 as it (S1 starts) instead of its role.
        val ghosts = P3Plan.botOrder().map { c ->
            val best = GhostStore.best(skill, c.name)
            val t = best?.let { "%.2f".format(Locale.ROOT, it.time / 20.0) }
            toggle(Roles.label(c), P3Plan.ghostOn(c), 56, "On (green): the ${Roles.label(c)} bot plays your fastest $skill P3 as ${Roles.label(c)} (S1 starts) instead of its role. " +
                (t?.let { "Yours: ${it}s." } ?: "No run of yours yet: it plays its role.")) { P3Plan.toggleGhost(c) }
        }
        val times = if (P3Plan.skill != P3Plan.RANDOM) emptyList() else listOf<LayoutElement>(
            label("§eBot times", 56, "Random skill: each bot job takes between these two."),
            change("-", 14, "Shorter.") { P3Plan.botMin = (P3Plan.botMin - 0.5).coerceAtLeast(0.0); P3Plan.save() },
            label("§f${"%.1f".format(Locale.ROOT, P3Plan.botMin)}s", 30),
            change("+", 14, "Longer.") { P3Plan.botMin = (P3Plan.botMin + 0.5).coerceAtMost(P3Plan.botMax); P3Plan.save() },
            change("-", 14, "Shorter.") { P3Plan.botMax = (P3Plan.botMax - 0.5).coerceAtLeast(P3Plan.botMin); P3Plan.save() },
            label("§f${"%.1f".format(Locale.ROOT, P3Plan.botMax)}s", 30),
            change("+", 14, "Longer.") { P3Plan.botMax = (P3Plan.botMax + 0.5).coerceAtMost(60.0); P3Plan.save() },
        )
        row(listOf<LayoutElement>(label("§dYour best", LABEL_W, "A bot plays your fastest run as its class.")) + ghosts + times)
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
        val p3Part = PartyClient.phase != "P1" && PartyClient.phase != "P2"
        val part = if (p3Part) "P3" else "P1/P2"
        val saved = HotbarLayout.has(p3Part)
        val who = Roles.label(P3Sim.myClass)
        row(listOf(
            stack("§eDeath Ticks", 84, P3Sim.deathTicksS.description, DEATH_TICKS.mapIndexed { i, (name, about) ->
                change(pick(name, P3Sim.deathTicks == i), 84, about) { P3Sim.deathTicksS.value = i }
            } + listOf(
                // Your own (the server puts them on you the moment they change: PartyServer.tick).
                toggle("Real Masks", P3Sim.realMasksS.value, 84, P3Sim.realMasksS.description) { P3Sim.realMasksS.value = !P3Sim.realMasksS.value },
                change("Mask: ${if (P3Sim.wornMaskS.value == 0) "Spirit" else "Bonzo"}", 84, P3Sim.wornMaskS.description) { P3Sim.wornMaskS.value = 1 - P3Sim.wornMaskS.value },
                change("Pet: ${if (P3Sim.phoenixS.value) "Phoenix" else "Black Cat"}", 84, P3Sim.phoenixS.description) { P3Sim.phoenixS.value = !P3Sim.phoenixS.value },
            )),
            // Terminals: random or one type for all.
            stack("§eTerminals", 66, P3Sim.terminalS.description, listOf(
                change(pick("Random", forced == null), 66, "Each terminal a random type, as on Hypixel.") { P3Sim.terminalS.value = 0 },
            ) + Terminals.Type.entries.map { t ->
                change(pick(TYPE_NAMES.getValue(t).first, forced == t), 66, TYPE_NAMES.getValue(t).second) { lastType = t; P3Sim.terminalS.value = t.ordinal + 1 }
            }),
            stack("§eSpeed", 110, P3Sim.speedS.description, listOf(
                SpeedSlider(110),
                speedRow,
                change("Ping: ${P3Sim.ping}ms", 110, P3Sim.pingS.description) { P3Sim.pingS.value = PINGS[(PINGS.indexOf(P3Sim.ping) + 1).mod(PINGS.size)] },
                toggle("Jitter", P3Sim.jitterS.value, 110, P3Sim.jitterS.description) { P3Sim.jitterS.value = !P3Sim.jitterS.value },
                toggle("No Melodies", P3Sim.noMelodies, 110, P3Sim.noMelodiesS.description) { P3Sim.noMelodiesS.value = !P3Sim.noMelodies },
                toggle("Click Limit", P3Sim.clickLimitS.value, 110, P3Sim.clickLimitS.description) { P3Sim.clickLimitS.value = !P3Sim.clickLimitS.value },
                LuckSlider(110),
                LuckSlider(110, "SS Luck", P3Sim.ssLuckS),
                toggle("1st Click Melody", P3Sim.firstClickMelody, 110, "Always First Click Melody: " + P3Sim.firstClickMelodyS.description) { P3Sim.firstClickMelodyS.value = !P3Sim.firstClickMelody },
            )),
            stack("§eTimings", 106, "The fight's numbers.", listOf(
                stepper("Goldor kill", "${P3Sim.goldorKill}t", P3Sim.goldorKillS.description, { P3Sim.goldorKillS.value = (P3Sim.goldorKill - 1).coerceAtLeast(10) }, { P3Sim.goldorKillS.value = (P3Sim.goldorKill + 1).coerceAtMost(120) }),
                stepper("Shortbow", "${P3Sim.shortbowCooldown}t", P3Sim.shortbowCooldownS.description, { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown - 1).coerceAtLeast(1) }, { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown + 1).coerceAtMost(20) }),
                stepper("Hydra", "${P3Sim.hydraStart}", P3Sim.hydraStartS.description, { P3Sim.hydraStartS.value = (P3Sim.hydraStart - 1).coerceAtLeast(0) }, { P3Sim.hydraStartS.value = (P3Sim.hydraStart + 1).coerceAtMost(10) }),
                toggle("Mage On All", P3Sim.mageAll, 106, "Mage Beam On All Classes: " + P3Sim.mageAllS.description) { P3Sim.mageAllS.value = !P3Sim.mageAll },
                toggle("Infinite Charges", P3Sim.breakerInfinite, 106, "On (green): the Dungeonbreaker never runs out of charges.") { P3Sim.toggleBreakerInfinite() },
                toggle("Perma Break", P3Sim.breakerPerma, 106, "On (green): blocks the Dungeonbreaker breaks stay broken (through restarts too) until you turn it off; then they all come back.") {
                    // Off: the server brings the blocks back (PartyServer.tick).
                    P3Sim.toggleBreakerPerma()
                },
            )),
            stack("§eFight", 92, "How the fight goes on.", listOf(
                toggle("Lava Bounce", P3Sim.lava, 92, P3Sim.lavaS.description) { P3Sim.lavaS.value = !P3Sim.lava },
                toggle("Start On Join", P3Sim.autoStart, 92, P3Sim.autoStartS.description) { P3Sim.autoStartS.value = !P3Sim.autoStart },
                toggle("Terror At Terms", P3Sim.terrorAtTerms, 92, P3Sim.terrorAtTermsS.description) { P3Sim.toggleTerrorAtTerms() },
                toggle("Black Cat Start", P3Sim.blackCatAtStart, 92, "Black Cat At Start: " + P3Sim.blackCatS.description) { P3Sim.toggleBlackCatAtStart() },
                toggle("Section Times", P3Sim.showTimes, 92, P3Sim.showTimesS.description) { P3Sim.showTimesS.value = !P3Sim.showTimes },
                toggle("Hide Players", P3Sim.hidePlayers, 92, "On (green): other players out of sight.") { P3Sim.toggleHidePlayers() },
                toggle("Debug Bots", P3Sim.debugBots, 92, P3Sim.debugBotsS.description) { P3Sim.debugBotsS.value = !P3Sim.debugBots },
            )),
            stack("§eOther", 100, "Your hotbar, recording, leaving.", listOf(
                act(if (saved) "§aSave $part Hotbar" else "Save $part Hotbar", 100, "Saves where your items are right now (hotbar and inventory), the slot you hold, what you wear and your pet, for the $who on ${P3Plan.skillName()} ($part). " +
                    "Every start and restart lays it out (without one, your items stay as you left them).") {
                    SimActions.send("hotbarSave", "p3" to p3Part)
                },
                act("Default Hotbar", 100, "Forgets your saved $part hotbar for the $who on ${P3Plan.skillName()} and lays out the sim's default.") {
                    SimActions.send("hotbarReset", "p3" to p3Part)
                }.also { it.active = saved },
                act("Reset Items", 100, "Your $part items, gear and pet back to your saved hotbar for the $who on ${P3Plan.skillName()} now (or the default, forgetting how you left them).") { SimActions.send("resetItems", "p3" to p3Part) },
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
        // Now, not at the next start: the server applies it the moment it changes (PartyServer.tick: Fight.applySpeed).
        P3Sim.speedS.value = s
    }

    /** Your speed, 100 to 750 in steps of 10. */
    private inner class SpeedSlider(w: Int) : AbstractSliderButton(0, 0, w, 20, Component.empty(), (P3Sim.speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED).toDouble()) {
        init {
            updateMessage()
            setTooltip(tip(P3Sim.speedS.description))
        }
        private fun speed() = MIN_SPEED + Math.round(value * (MAX_SPEED - MIN_SPEED) / 10).toInt() * 10
        override fun updateMessage() {
            message = Component.literal("Speed: ${speed()}" + if (P3Sim.phoenixS.value) " §8(Phoenix out)" else " §8(${speed() + 100} with Black Cat)")
        }
        override fun applyValue() = setSpeed(speed())
    }

    /** A luck setting ([s]: Terminal Luck, SS Luck), -100 to 100% in fives. */
    private inner class LuckSlider(w: Int, private val name: String = "Luck", private val s: com.odtheking.odin.clickgui.settings.impl.NumberSetting<*> = P3Sim.termLuckS) :
        AbstractSliderButton(0, 0, w, 20, Component.empty(), (s.value.toDouble() / 100 + 1) / 2) {
        init {
            updateMessage()
            setTooltip(tip(s.description))
        }
        private fun luck() = Math.round(value * 40).toInt() * 5 - 100
        override fun updateMessage() { message = Component.literal("$name: ${if (luck() < 0) "§c" else ""}${luck()}%") }
        override fun applyValue() { @Suppress("UNCHECKED_CAST") (s as com.odtheking.odin.clickgui.settings.impl.NumberSetting<Int>).value = luck(); ModuleManager.saveConfigurations() }
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
    /**
     * A column per part of the run (P1, P2, S1-S4), its places top to bottom in the order you get to them. CEE3 is
     * the high EE3. The spots are here, not in [Spots], so a hotswap brings them in.
     */
    /** The boss fight: where the run stops. Green when on. */
    private fun bossPanel() {
        row(listOf(stack("§eStop", 120, "Where the run stops on its own (as the menu's Stop: everything back as built).", listOf(
            toggle("Stop After Terms", P3Sim.termsOnly, 120, P3Sim.termsOnlyS.description) { P3Sim.toggleStopAfter(terms = true) },
            toggle("Stop After Goldor", P3Sim.p3Only, 120, P3Sim.p3OnlyS.description) { P3Sim.toggleStopAfter(terms = false) },
        ))))
    }

    private fun teleportPanel() {
        val w = 64
        fun spot(name: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Pair<String, Spots.Spot?> = name to Spots.Spot(name, x, y, z, yaw, pitch)
        fun of(name: String, s: Spots.Spot?): Pair<String, Spots.Spot?> = name to s
        // A job's stand, facing it (as recorded at P3's start where that was measured).
        val stations = Station.all().associateBy { it.id }
        fun job(name: String, id: String): Pair<String, Spots.Spot?> = name to Party.STANDS[id]?.let { p ->
            val look = Spots.startLook(id).takeIf { it != 180f to 0f } ?: stations[id]?.let { st ->
                val aim = st.lever?.let { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5) } ?: st.at.add(0.0, 1.0, 0.0)
                val d = aim.subtract(p.add(0.0, 1.62, 0.0))
                Math.toDegrees(Math.atan2(-d.x, d.z)).toFloat() to (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))).toFloat()
            } ?: (180f to 0f)
            Spots.Spot("${jobName(id)}", p.x, p.y, p.z, look.first, look.second)
        }
        fun ee(name: String, key: String): Pair<String, Spots.Spot?> = name to P3Plan.earlyEnters.firstOrNull { it.key == key }?.let { P3Plan.eeSpot(it) }
        val groups = listOf(
            "P1" to listOf(of("Start", Spots.P1), spot("Agro Spot", 69.53, 225.0, 65.12, -0.25f, 6.57f),
                spot("LC Pad", 95.61, 224.0, 40.38, 45.78f, 13.31f), spot("RC Pad", 51.64, 224.0, 40.61, -45.13f, 18.90f)),
            "P2" to listOf(spot("Start", 77.5, 165.0, 40.5, -84.99f, 2.72f), spot("Yellow Pad", 27.75, 170.0, 94.55, -119.09f, 9.95f),
                spot("Purple Pad", 119.95, 170.0, 95.10, 137.35f, 11.10f), spot("YP DPS", 57.5, 169.0, 65.5, -89.74f, -14.13f)),
            // Levers: yours (the west one if that's yours, else the east).
            "S1" to listOf(spot("SS", 108.30, 120.0, 94.0, -89.16f, 1.72f), job("2", "S1 T2"), spot("4", 93.57, 122.0, 101.52, 90.47f, 16.44f),
                job("Levers", if (P3Plan.isMine("S1 west lever") && !P3Plan.isMine("S1 east lever")) "S1 west lever" else "S1 east lever")),
            "S2" to listOf(spot("EE2", 60.5, 132.0, 139.0, 90.47f, 1.64f), spot("i2", 36.03, 131.0, 139.01, 92.64f, 3.45f), spot("2", 59.53, 120.0, 126.0, -179.59f, 19.56f), spot("4", 39.48, 109.0, 139.61, -0.25f, 29.06f), spot("5", 40.53, 124.0, 125.54, 179.81f, 21.45f)),
            "S3" to listOf(ee("EE3", "ee3"), spot("CEE3", 18.5, 121.5, 91.5, 0.02f, -8.14f), spot("Dev", 2.54, 120.0, 77.48, 89.63f, 8.13f), spot("4", 1.11, 109.0, 77.55, 90.40f, 22.47f)),
            "S4" to listOf(ee("Core", "core"), ee("Recore", "recore"), spot("1", 41.51, 109.0, 33.36, -179.36f, 17.01f), spot("4", 72.45, 115.0, 45.31, -0.77f, 22.35f)),
        )
        row(groups.map { (group, spots) ->
            stack("§e$group", w, "Teleports: $group, in the order you get to them.", spots.map { (name, s) ->
                if (s == null) label("§8$name", w, "Not set.")
                else act(name, w, "To ${if (s.name == name) "$group $name" else s.name}: ${xyz(s.x, s.y, s.z)}, facing ${facing(s.yaw)}.") { tp(s.x, s.y, s.z, s.yaw, s.pitch) }
            })
        })
    }

    private fun tp(x: Double, y: Double, z: Double, yaw: Float?, pitch: Float?) =
        SimActions.send("tp", "x" to x, "y" to y, "z" to z, "yaw" to yaw, "pitch" to pitch)

    private fun xyz(x: Double, y: Double, z: Double) = "%.1f, %.1f, %.1f".format(Locale.ROOT, x, y, z)

    private fun facing(yaw: Float) = when (Math.floorMod(Math.round(yaw / 90f), 4)) { 0 -> "south"; 1 -> "west"; 2 -> "north"; else -> "east" }

    // ------------------------------------------------------------------ practice

    /** Practice: your custom one on the left (a column, its jobs beside it), the presets on the right. */
    private fun practicePanel() {
        // Custom: your start, the section's jobs you pick, checkpoints in order.
        val start = Practice.customStart
        val cps = Practice.checkpoints.size
        // The jobs: always the section you're in now (picks from other sections stay).
        val sec = mc.player?.let { Practice.sectionAt(it.x, it.y, it.z) } ?: 1
        val picked = Practice.customJobsIn(sec)
        // Left to right: Export / Import, the custom practice's jobs, Custom, then the section tabs with the open section's page.
        // Custom's buttons: as wide as their longest label (no scrolling text).
        val cw = maxOf(100, listOf("Start Custom", "Start Position", "Checkpoint §7($cps)", "Leap Waypoint §7(4/4)", "Clear")
            .maxOf { font.width(it) } + 10)
        val custom = ArrayList<LayoutElement>()
        custom += stack("§eCustom", cw, "Your own practice: a start position (its section is where you stand), that section's jobs you pick and checkpoints to reach in order (within 1 block, the same height), all timed.", listOf(
            act(if (start != null) "§aStart Custom" else "§8Start Custom", cw,
                if (start != null) "Starts the custom practice from your start position: your jobs (every section's picks) then the $cps checkpoint(s), timed." else "Set the Start Position first.") { SimActions.send("practiceCustom") }
                .also { it.active = start != null },
            change(if (start != null) "§aStart Position" else "Start Position", cw,
                "Click: the custom practice starts where you stand, facing as you are; its section is the one you're in (or the nearest). " +
                    (start?.let { "Now: S${Practice.customSection}, ${at(it)}." } ?: "Not set.")) {
                here()?.let { h ->
                    val held = mc.player?.mainHandItem
                    val n = Practice.setStart(h, held?.let { Practice.itemKey(it) })
                    EngineerClient.msg("§7Custom practice start: §fS$n§7, ${at(h)}${held?.takeIf { !it.isEmpty }?.let { "§7, holding §f${it.hoverName.string}" } ?: ""}§7.")
                }
            },
            ClickButton(cw, if (cps > 0) "§aCheckpoint §7($cps)" else "Checkpoint",
                tip("Left click: a checkpoint where you stand (reached within 1 block across, at this exact height; in order). Right click: removes the last one. Now: $cps."),
                left = { here()?.let { h -> Practice.addCheckpoint(h); EngineerClient.msg("§7Checkpoint §f${Practice.checkpoints.size}§7: ${at(h)}.") }; rebuildWidgets() },
                right = {
                    EngineerClient.msg(if (Practice.checkpoints.isEmpty()) "§7No checkpoints to remove." else "§7Checkpoint §f${Practice.checkpoints.size}§7 removed.")
                    Practice.removeCheckpoint(); rebuildWidgets()
                }),
            ClickButton(cw, if (Practice.leapWaypoints.isNotEmpty()) "§aLeap Waypoint §7(${Practice.leapWaypoints.size}/4)" else "Leap Waypoint",
                tip("Left click: where you stand becomes a leap's landing in the custom practice: the 1st the leap menu's top left, then top right, bottom left, bottom right. Right click: removes the last one. Now: ${Practice.leapWaypoints.size}/4."),
                left = {
                    here()?.let { h ->
                        val n = Practice.addLeapWaypoint(h)
                        EngineerClient.msg(if (n == 0) "§7All 4 leap waypoints are set (right click removes the last)." else "§7Leap waypoint §f$n§7 (${QUADRANTS[n - 1]}): ${at(h)}.")
                    }
                    rebuildWidgets()
                },
                right = {
                    val n = Practice.leapWaypoints.size
                    EngineerClient.msg(if (n == 0) "§7No leap waypoints to remove." else "§7Leap waypoint §f$n§7 (${QUADRANTS[n - 1]}) removed.")
                    Practice.removeLeapWaypoint(); rebuildWidgets()
                }),
            DelaySlider(cw),
            change("§cClear", cw, "Clears the custom practice: start, jobs, checkpoints and leap waypoints; the timer back to 0.") { Practice.clearCustom(); EngineerClient.msg("§7Custom practice cleared.") },
        ))
        val io = stack("", 64, "Your custom practice as a short code, to send to someone or take theirs.", listOf(
            change("Export", 64, "Copies the custom practice as a short code (start, jobs, checkpoints, leap waypoints, start timer) to send to someone.") {
                val code = Practice.exportCode()
                mc.keyboardHandler.clipboard = code
                EngineerClient.msg("§7Custom practice code copied: §f$code")
            },
            change("Import", 64, "Replaces your custom practice with the code on your clipboard.") {
                if (Practice.importCode(mc.keyboardHandler.clipboard)) EngineerClient.msg("§7Custom practice imported (S${Practice.customSection}, ${Practice.customJobs.size} job(s), ${Practice.checkpoints.size} checkpoint(s), ${Practice.leapWaypoints.size} leap waypoint(s)).")
                else EngineerClient.msg("§cYour clipboard doesn't hold a custom practice code.")
            },
        ))
        val others = (1..4).filter { it != sec }.mapNotNull { s -> Practice.customJobsIn(s).takeIf { it.isNotEmpty() }?.let { j -> "S$s ${j.joinToString(" ") { COLUMNS[column(it)].first }}" } }.joinToString(", ")
        // The section's jobs, in the plan's column order, 6 a column.
        val jobs = P3Plan.jobsIn(sec).sortedBy { column(it) }
        jobs.chunked(6).forEachIndexed { i, part ->
            custom += stack(if (i == 0) "§6§lS$sec" else "", JOB_W, "The section you're in: go to another to pick its jobs too (the picks of every section stay)." +
                    (others.takeIf { it.isNotEmpty() }?.let { " Picked elsewhere: $it." } ?: ""), part.map { job ->
                val on = job in picked
                change((if (on) "§a" else "§8") + COLUMNS[column(job)].first, JOB_W, "${jobName(job)}: ${if (on) "yours to do in the custom practice" else "done at its start"}. Click: ${if (on) "done at the start" else "yours"}.") { Practice.toggleJob(job) }
            })
        }
        val secCols = ArrayList<LayoutElement>()
        // The section tabs, and the open section's page (S1 when the menu opens).
        val s = practiceSec.takeIf { it in 1..4 } ?: 1
        secCols += sectionTabs(s)
        secCols += label("", 8)
        // Each group its own column(s), headed (6 a column); as many columns whichever section is open (the widest's; empty ones
        // after), so switching moves nothing.
        val all = Practice.presets()
        fun columns(k: Int) = Practice.presetGroups(k).flatMap { (h, ps) -> ps.chunked(6).mapIndexed { i, part -> (if (i == 0) h else "") to part } }
        val width = (1..4).maxOf { columns(it).size }
        // Every section's preset buttons as wide as the longest name or heading (no scrolling text; the same width in each section).
        val pw = maxOf(64, all.maxOfOrNull { font.width(it.name) + 10 } ?: 0, (1..4).flatMap { columns(it) }.maxOfOrNull { font.width(it.first) + 6 } ?: 0)
        val parts = columns(s)
        for (i in 0 until width) {
            val (head, part) = parts.getOrNull(i) ?: ("" to emptyList())
            secCols += stack(if (head.isNotEmpty()) "§7$head" else "", pw, "Section $s's presets: a start, the jobs and checkpoints (the rest of the section done), and their own start timer.", part.map { p ->
                act(p.name, pw, "Practice ${p.name} (S$s): ${p.jobs.joinToString(", ") { jobName(it) }}" +
                    (if (p.checkpoints.isNotEmpty()) ", then ${p.checkpoints.size} checkpoint(s)" else "") +
                    (p.delay?.let { ". Start timer ${"%.1f".format(Locale.ROOT, it)}s." } ?: ". Start timer: the slider's.")) { SimActions.send("practicePreset", "name" to p.name) }
            })
        }
        val cols = ArrayList<LayoutElement>()
        cols += io
        cols += label("", 8)
        cols += custom.drop(1).reversed()  // the overflow jobs (past 6: D, G, 5...) on the left
        cols += custom.first()
        cols += label("", 16)
        cols += secCols
        val r = LinearLayout.horizontal().spacing(2)
        r.defaultCellSetting().alignVerticallyTop()
        panel.addChild(r)
        cols.forEach { r.addChild(it) }
    }

    /** Practice's section tabs: [open]'s page shows beside them (one always open). */
    private fun sectionTabs(open: Int): LayoutElement = stack("§eSections", 64, "Each section's own practices.", (1..4).map { s ->
        change(if (open == s) "§aS$s" else "S$s", 64, "Section $s's practices.") { practiceSec = s }
    })

    // ------------------------------------------------------------------ pieces

    /** Top right: a party - host yours (a code for friends), or join one by its code. */
    private fun party() {
        val x = width - PARTY_W - 6
        var y = 6
        fun line(t: String) { addRenderableWidget(StringWidget(Component.literal(t), font).also { it.setPosition(x, y + 6) }); y += 20 }
        if (P3Party.hosting) {
            val names = P3Party.players()
            line("Party code: ${P3Party.code?.let { "§b§l$it" } ?: "§7..."}")
            line("§7${names.joinToString(", ").ifEmpty { "Just you" }} §8${names.size.coerceAtLeast(1)}/5")
            addRenderableWidget(Button.builder(Component.literal("Close Party")) { P3Party.stopHosting(); rebuildWidgets() }
                .tooltip(tip("Drops everyone who joined; your sim goes on.")).bounds(x, y, PARTY_W, 20).build())
            return
        }
        if (PartyClient.joiner) {
            // In someone's sim: who's in it (the server's roster), and the way out.
            val names = listOf(mc.player?.name?.string ?: "You") + PartyClient.team.filter { it.player }.map { it.name }
            line("§7In a party")
            line("§7${names.joinToString(", ")} §8${names.size}/5")
            addRenderableWidget(Button.builder(Component.literal("Leave Party")) { mc.setScreen(null); SimWorld.leave() }
                .tooltip(tip("Back to the title screen; the host's sim goes on.")).bounds(x, y, PARTY_W, 20).build())
            return
        }
        addRenderableWidget(Button.builder(Component.literal("Host Party")) { P3Party.host(); rebuildWidgets() }
            .tooltip(tip("Friends join your sim: you get a 4-letter code to send them.")).bounds(x, y, PARTY_W, 20).build())
        y += ROW
        val box = EditBox(font, x, y, PARTY_W - 42, 20, Component.literal("Code"))
        box.setMaxLength(4)
        box.setHint(Component.literal("§8Code"))
        addRenderableWidget(box)
        addRenderableWidget(Button.builder(Component.literal("Join")) { if (box.value.isNotBlank()) { mc.setScreen(null); P3Party.join(box.value) } }
            .tooltip(tip("Joins a friend's sim by its party code.")).bounds(x + PARTY_W - 40, y, 40, 20).build())
    }

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
        Button.builder(Component.literal(label)) { mc.setScreen(null); run() }.tooltip(tip(about)).width(w).build()

    /** One of a few choices: the chosen one green and underlined. */
    private fun pick(name: String, on: Boolean) = if (on) "§a§n$name" else name

    private fun onOff(b: Boolean) = if (b) "§aON" else "§cOFF"

    /** In a party: more than one player in the sim (starts and stops are everyone's). */
    private val inParty get() = PartyClient.party

    /** A button that takes right clicks too ([left] / [right]). */
    private class ClickButton(w: Int, text: String, tooltip: Tooltip, private val left: () -> Unit, private val right: () -> Unit) :
        Button(0, 0, w, 20, Component.literal(text), { left() }, { it.get() }) {
        init { setTooltip(tooltip) }
        // InputConstants has this platform's numbers (26.1.2 is on GLFW: left 0, right 1; 26.3 on SDL: left 1, right 3).
        override fun isValidClickButton(info: MouseButtonInfo) =
            info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT
        override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
            EngineerClient.logger.info("[ec] p3sim menu click: button ${event.button()} on ${message.string}")
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) right() else left()
        }
        override fun extractContents(g: net.minecraft.client.gui.GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partial: Float) {
            extractDefaultSprite(g)
            extractDefaultLabel(g.textRendererForWidget(this, net.minecraft.client.gui.GuiGraphicsExtractor.HoveredTextEffects.NONE))
        }
    }

    private companion object {
        const val RESTART_W = 100
        const val PARTY_W = 130
        val QUADRANTS = listOf("top left", "top right", "bottom left", "bottom right")
        /** The plan's and Advanced's height (5 rows) plus a gap: where the Advanced button goes. */
        const val PANEL_H = 6 * 20 + 5 * 2 + 4
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

        /** The tab open (null: none, as each time the menu opens). */
        private var tab: Tab? = null
        /** Practice's open section page, 1-4 ([sectionTabs]); the last one each time the menu opens. */
        private var practiceSec = 0

        /** The type a terminal type pick last chose. */
        var lastType = Terminals.Type.ORDER

        val PINGS = listOf(0, 50, 100, 150, 200, 300)

        /** Terminals 1-5, then L, R, D, G, as [P3Plan.short] names them. */
        val COLUMNS = (1..5).map { "$it" to "Terminal $it." } + listOf(
            "L" to "The left lever, coming in along the track (S1 east, S2 high, S3 west, S4 low).",
            "R" to "The right lever (S1 west, S2 low, S3 east, S4 high).",
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
