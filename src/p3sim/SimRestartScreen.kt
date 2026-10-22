package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.rustclear.mod.ui.RustclearScreen
import net.rustclear.mod.ui.Theme
import java.util.Locale

/**
 * The sim's main menu: Esc > ADS Menu, the SkyBlock Menu star (hotbar), `/ads menu` or the keybind. Drawn with the ADS
 * screens' own toolkit ([RustclearScreen]), so it reads as the rest of them.
 *
 * The rail on the left holds the tabs (Plan, Advanced, Settings, Teleport, Practice, Boss), each with a line of
 * what's set, and the party under them (host yours, or join one by its code). On the right: your skill level and
 * role on top, then the open tab, scrolled when it's taller than the panel. Restart P3, Stop and Done along the
 * bottom. Every control says what it does when hovered; changes save at once, starts and teleports close the menu.
 */
class SimRestartScreen : RustclearScreen(Component.literal(P3Sim.FULL_NAME), null) {
    private enum class Tab(val title: String, val color: Int, val about: String) {
        PLAN("Plan", Theme.GOLD, "Each section's jobs and who does them: yours or a bot's."),
        ADVANCED("Advanced", Theme.PUZZLE, "Spawn and early-enter spots, the leap menu, the bots' options."),
        SETTINGS("Settings", Theme.SILVER, "Death ticks, terminals, your speed, timings, the fight, your hotbar."),
        TELEPORT("Teleport", Theme.ENTRANCE, "Every place in the arena: section starts, devices, pads, early-enter spots."),
        PRACTICE("Practice", Theme.TRAP, "A section (your role's part of it), or your own custom one; timed."),
        BOSS("Boss", Theme.BLOOD, "The boss fight: where the run stops (after terms, after Goldor)."),
    }

    // Each time the menu opens: the tab and Practice section it closed on.
    init { tab = Tab.entries.firstOrNull { it.name == Practice.menuTab } ?: Tab.PLAN; practiceSec = Practice.menuSection }

    /** The party code box, while you're in no party (hosting or joined). */
    private var codeBox: EditBox? = null

    override fun init() {
        layoutPanel(MAX_W, MAX_H)
        codeBox = null
        if (!P3Party.hosting && !PartyClient.joiner) {
            codeBox = input(px + 13, partyY() + 34, 52, "Code", 4).also { addRenderableWidget(it) }
        }
    }

    override fun isPauseScreen(): Boolean = false

    /** Odin's settings save when the menu closes (dragging the speed slider would write every step). */
    override fun removed() {
        super.removed()
        Practice.saveMenu(tab.name, practiceSec.takeIf { it in 1..4 } ?: 1)
        P3Sim.saveSettings()
    }

    // ------------------------------------------------------------------ the frame

    /** This frame's graphics and mouse, so the row helpers below don't pass them along. */
    private lateinit var g: GuiGraphicsExtractor
    private var mx = 0
    private var my = 0

    private fun contentX() = px + RAIL_W + 10
    private fun contentW() = pw - RAIL_W - 20
    /** The party's block at the rail's bottom (under the tabs when the panel is short). */
    private fun partyY() = maxOf(py + 31 + Tab.entries.size * 28 + 4, py + ph - 60)

    override fun extractFrame(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        this.g = g
        this.mx = mx
        this.my = my
        sliders = building
        building = ArrayList()
        panel(g)
        val phase = PartyClient.phase
        header(g, "P3 Sim", if (phase.isEmpty()) "STOPPED" else "$phase RUNNING", if (phase.isEmpty()) Theme.DIM else Theme.GOOD)
        railBackground(g, RAIL_W)
        rail()
        party()

        val x = contentX()
        val w = contentW()
        cl = x
        rowEnd = x + w
        cy = py + 29
        skillRow()
        roleRow()
        rect(g, x, cy - 1, w, 1, Theme.PANEL_EDGE)

        val ty = cy + 4
        val t = tab
        val tw = title(g, t.title, x, ty)
        text(g, fit(t.about, w - tw - 8), x + tw + 8, ty + 2, Theme.DIM)
        tabView(ty + 17, py + ph - 31)
        footer(x, w)
    }

    /** The open tab, scrolled in the window from [top] to [bottom]. */
    private fun tabView(top: Int, bottom: Int) {
        val x = contentX()
        val w = contentW()
        val h = bottom - top
        if (h <= 0) return
        view = intArrayOf(x, top, w + 6, h)
        beginClip(g, x, top, w + 6, h)
        cy = top - scroll
        // By identity, not a when over the enum (its ordinal table goes stale when the tabs change in a hotswapped game).
        val t = tab
        if (t === Tab.PLAN) planTab()
        else if (t === Tab.ADVANCED) advancedTab()
        else if (t === Tab.SETTINGS) settingsTab()
        else if (t === Tab.TELEPORT) teleportTab()
        else if (t === Tab.PRACTICE) practiceTab()
        else if (t === Tab.BOSS) bossTab()
        val full = cy + scroll - top
        endClip(g)
        view = null
        maxScroll = maxOf(0, full - h)
        scroll = scroll.coerceIn(0, maxScroll)
        scrollbar(g, x + w + 4, top, h, h, full, scroll, maxScroll)
    }

    private fun footer(x: Int, w: Int) {
        val by = py + ph - 23
        rect(g, x, by - 5, w, 1, Theme.PANEL_EDGE)
        val party = if (inParty) " For the whole party." else ""
        var right = button(g, mx, my, "Done", px + pw - 8, by, Style.SECONDARY) { onClose() }
        right = button(g, mx, my, "Stop", right - 4, by, Style.DANGER) {
            mc.gui.setScreen(null)
            SimActions.send("stop")
        }
        tip(right, by, font.width("Stop") + 16, 17, "Ends the run: the fight stops and the splits clear.$party")
        right = button(g, mx, my, "Restart P3", right - 4, by, Style.PRIMARY) {
            mc.gui.setScreen(null)
            SimActions.send("start", "what" to Fight.Start.P3.name)
        }
        tip(right, by, font.width("Restart P3") + 16, 17, "Starts P3 over from its beginning, everything reset (Stop: ends it).$party")
        // Solo, with the bots shown: in a party everyone plays their own class.
        val watch = !inParty && P3Sim.bots
        right = button(g, mx, my, "Spectate Route", right - 4, by, Style.SECONDARY, if (!watch) null else Runnable {
            mc.gui.setScreen(null)
            SimActions.send("spectateRoute")
        })
        tip(right, by, font.width("Spectate Route") + 16, 17, when {
            inParty -> "Solo only: in a party everyone plays their own class."
            !P3Sim.bots -> "Needs the bots shown: Advanced > Bots."
            else -> "Starts P3 with a bot for every class, yours too, and you watching yours play your jobs (F5: first or third person). You're back in survival when it ends (Goldor dead, Stop, Restart P3)."
        })
        if (inParty) text(g, fit("Starts and stops are the party's.", right - x - 8), x, by + 5, Theme.DIM)
    }

    // ------------------------------------------------------------------ the rail

    private fun rail() {
        var y = py + 29
        for (t in Tab.entries) {
            railItem(g, mx, my, px + 5, y, RAIL_W - 10, tab === t, t.title, summary(t), t.color, Theme.DIM) {
                if (tab !== t) { tab = t; scroll = 0 }
            }
            y += 28
        }
    }

    /** A tab's line in the rail: what's set in it. */
    private fun summary(t: Tab): String = when {
        t === Tab.PLAN -> "${P3Plan.skillName()}, ${(1..4).sumOf { s -> P3Plan.jobsIn(s).count { P3Plan.isMine(it) } }} jobs yours"
        t === Tab.ADVANCED -> "Leaps: ${if (P3Plan.odinSort) "Odin" else "Custom"}, helpers ${if (P3Plan.helper) "on" else "off"}"
        t === Tab.SETTINGS -> "Speed ${P3Sim.speed}, ping ${P3Sim.ping}ms"
        t === Tab.TELEPORT -> "P1 to S4, early enters"
        t === Tab.PRACTICE -> if (Practice.customStart != null) "Custom set (S${Practice.customSection})" else "Presets, custom"
        P3Sim.termsOnly -> "Stops after terms"
        P3Sim.p3Only -> "Stops after Goldor"
        else -> "Goes on to Necron"
    }

    /** The rail's bottom: a party - host yours (a code for friends), or join one by its code. */
    private fun party() {
        val x = px + 9
        val w = RAIL_W - 18
        var y = partyY()
        // The code box only while you're in no party (it can start or end with the menu open).
        codeBox?.visible = !P3Party.hosting && !PartyClient.joiner
        rect(g, x, y - 4, w, 1, Theme.PANEL_EDGE)
        text(g, "PARTY", x, y + 2, Theme.DIM)
        y += 13
        if (P3Party.hosting) {
            val names = P3Party.players()
            text(g, "Code ", x, y, Theme.MUTED)
            text(g, P3Party.code ?: "...", x + font.width("Code "), y, Theme.GOLD)
            textRight(g, "${names.size.coerceAtLeast(1)}/5", x + w, y, Theme.DIM)
            text(g, fit(names.joinToString(", ").ifEmpty { "Just you" }, w), x, y + 10, Theme.MUTED)
            railChoice("Close Party", x, y + 22, "Drops everyone who joined; your sim goes on.") { P3Party.stopHosting(); rebuildWidgets() }
            return
        }
        if (PartyClient.joiner) {
            // In someone's sim: who's in it (the server's roster), and the way out.
            val names = listOf(mc.player?.name?.string ?: "You") + PartyClient.team.filter { it.player }.map { it.name }
            text(g, "In a party", x, y, Theme.MUTED)
            textRight(g, "${names.size}/5", x + w, y, Theme.DIM)
            text(g, fit(names.joinToString(", "), w), x, y + 10, Theme.MUTED)
            railChoice("Leave Party", x, y + 22, "Back to the title screen; the host's sim goes on.") { mc.gui.setScreen(null); SimWorld.leave() }
            return
        }
        railChoice("Host Party", x, y, "Friends join your sim: you get a 4-letter code to send them.") { P3Party.host(); rebuildWidgets() }
        val box = codeBox ?: return
        inputFrame(g, x, y + 18, 60, box.isFocused, false)
        railChoice("Join", x + 64, y + 18, "Joins a friend's sim by its party code.") { if (box.value.isNotBlank()) { mc.gui.setScreen(null); P3Party.join(box.value) } }
    }

    private fun railChoice(label: String, x: Int, y: Int, about: String, run: () -> Unit) {
        choice(g, mx, my, label, x, y, false) { run() }
        tip(x, y, choiceWidth(label), 13, about)
    }

    // ------------------------------------------------------------------ skill and role (on top)

    /** The skill level, the chosen one lit (its P3 time on hover). Shown names only: the presets keep theirs. */
    private fun skillRow() = row("Skill", "How fast the bots play; your jobs become your class's role in it.") {
        for ((i, name, about) in SKILLS)
            opt(name, i == P3Plan.skill, "$about Your jobs become your class's role in it.") { P3Plan.chooseSkill(i) }
    }

    /**
     * Your role (class); a bot plays each of the others (in a party: the classes nobody plays). A class another
     * player has is shown with their name: taking it swaps you.
     */
    private fun roleRow() = row("Role", "Your class: a bot plays each class no player plays.") {
        val roles = P3Plan.preset().roles
        Party.CLASSES.forEachIndexed { i, c ->
            val other = PartyClient.team.firstOrNull { it.player && it.clazz == c }
            val label = if (other != null) "${Roles.label(c).take(4)} ${other.name.take(6)}" else Roles.label(c)
            val color = classColor(c.name)
            val on = c == P3Sim.myClass
            val w = choiceWidth(label)
            val x = place(w)
            val hover = hot(x, cy, w, 13)
            round(g, x, cy, w, 13, if (on) Theme.alpha(color, 0x40) else if (hover) Theme.ROW_HOVER else Theme.RAIL)
            if (on) outline(g, x, cy, w, 13, Theme.alpha(color, 0xA0))
            text(g, label, x + 5, cy + 3, if (on || hover) color else if (other != null) Theme.TEAM_A else Theme.lerp(color, Theme.MUTED, 0.45f))
            click(x, cy, w, 13) { P3Sim.classS.index = i; P3Sim.saveSettings() }
            tip(x, cy, w, 13, if (other != null) "${other.name} plays the ${Roles.label(c)}. Click: you take it and they get yours (${Roles.label(P3Sim.myClass)})."
                else "Play as ${Roles.label(c)}: your jobs become its ${P3Plan.skillName()} role (${roles[c] ?: "none"}); a bot plays each class no player plays.")
        }
    }

    // ------------------------------------------------------------------ the plan

    /**
     * Each section's jobs in fixed columns (terminals 1-5, the left and right lever, the device, the
     * gate). Left click: yours or its bot's. Right click a bot's: the next bot does it; right click
     * yours: a stack, its bot does it too with Helpers on (whoever's first).
     */
    private fun planTab() {
        val cw = ((rowEnd - cl - SEC_W) / COLUMNS.size - 2).coerceIn(26, 44)
        for ((i, col) in COLUMNS.withIndex()) {
            val x = cl + SEC_W + i * (cw + 2)
            textCenter(g, col.first, x + cw / 2, cy + 3, Theme.GOLD)
            tip(x, cy, cw, 13, col.second)
        }
        cy += 16
        for (s in 1..4) {
            text(g, "S$s", cl + 2, cy + 3, Theme.GOLD)
            tip(cl, cy, SEC_W, 13, "Section $s's jobs.")
            val cells = arrayOfNulls<String>(COLUMNS.size)
            for (job in P3Plan.jobsIn(s)) cells[column(job)] = job
            cells.forEachIndexed { i, job -> if (job != null) jobCell(job, cl + SEC_W + i * (cw + 2), cy, cw) }
            cy += 16
        }
        cy += 4
        note("Left click: yours or its bot's. Right click a bot's: the next bot; right click yours: a stack (its bot does it too, with Helpers on). Green: yours; blue: a bot you picked; pink: another player's; * a stack.")
    }

    private fun jobCell(job: String, x: Int, y: Int, w: Int) {
        val mine = P3Plan.isMine(job)
        val doer = P3Plan.doer(job)
        val picked = !mine && doer != P3Plan.roleDoer(job)
        val star = if (P3Plan.isStack(job)) "*" else ""
        // Another player plays that class: theirs (their own plan decides), not a bot's.
        val mate = if (mine) null else PartyClient.team.firstOrNull { it.player && it.clazz == doer }
        // Which bot does a job is the host's pick in a party.
        val joiner = PartyClient.joiner
        val (label, color) = when {
            mine && P3Plan.isStacked(job) -> "You+" to Theme.GOOD
            mine -> "You$star" to Theme.GOOD
            mate != null -> "${mate.name.take(4)}$star" to MATE
            doer == null -> "any$star" to Theme.DIM
            else -> "${Roles.label(doer).take(4)}$star" to (if (picked) Theme.TEAM_A else Theme.MUTED)
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
        val clicks = if (mine) "Left click: the bot's. Right click: ${if (P3Plan.isStacked(job)) "not a stack" else "a stack (its bot does it too)"}."
        else if (joiner) "Left click: yours. (Which bot does a job is the host's pick.)"
        else "Left click: yours. Right click: the next bot."
        cell(label, color, mine, x, y, w, "${jobName(job)}. $who$shared $clicks",
            left = { P3Plan.toggle(job) },
            right = { if (P3Plan.isMine(job)) P3Plan.toggleStacked(job) else if (!joiner) P3Plan.cycleDoer(job) })
    }

    // ------------------------------------------------------------------ advanced

    /** Your spawn and the early-enter spots (this skill and class), the leap menu (2x2 as in game), the bots' options. */
    private fun advancedTab() {
        val skill = P3Plan.skillName()
        val me = Roles.label(P3Sim.myClass)
        row("Spawn", "Where Restart P3 puts you.") {
            val spawn = P3Plan.customSpot("spawn")
            change("Set here", "Restart P3 puts you where you stand now, facing as you are (as $me in $skill). Now: ${spawn?.let { at(it) } ?: "your first S1 job's spot"}.",
                on = P3Plan.hasCustomSpot("spawn")) { here()?.let { P3Plan.setCustomSpot("spawn", it) } }
            change("Default", "Back to the default spawn (Normal PF's, as $me).") { P3Plan.setCustomSpot("spawn", null) }
        }
        row("Early enters", "Where each early enter stands, whoever does it. Lit: your own spot.") {
            for (ee in P3Plan.earlyEnters.filter { it.into != 4 || it.on }) {
                val who = ee.owner?.let { if (ee.byYou) "you" else "the ${Roles.label(it)} bot" } ?: "nobody in $skill"
                change(ee.label, "${ee.label} (${who}) stands where you stand now, facing as you are (as $me in $skill). Now: ${at(P3Plan.eeSpot(ee))}.",
                    on = P3Plan.hasCustomSpot(ee.key)) { here()?.let { P3Plan.setCustomSpot(ee.key, it) } }
            }
            change("Defaults", "Every early enter back to its default spot (Normal PF's) for $me in $skill.") { P3Plan.earlyEnters.forEach { P3Plan.setCustomSpot(it.key, null) } }
        }
        gap()

        // The leap menu as it opens: slots 1 2 over 3 4. Left click: the next class; right click: the one before.
        row("Leap menu", "The leap menu, 2x2 as it opens in game.") {
            val order = P3Plan.botOrder()
            for (slot in 1..4) {
                if (slot == 3) newLine()
                val c = order.getOrNull(slot - 1)
                val name = c?.let { Roles.label(it) } ?: "-"
                val x = place(LEAP_W)
                cell("$slot: $name", Theme.TEXT, false, x, cy, LEAP_W,
                    "Leap menu slot $slot: ${c?.let { Roles.label(it) } ?: "nobody"}. Left click: swap with the next slot's class; right click: with the one before. (Your own order: Leap Sort goes to Custom.)",
                    left = { P3Plan.cycleSlot(slot); P3Plan.save() },
                    right = { P3Plan.cycleSlot(slot, back = true); P3Plan.save() })
            }
        }
        row("Leap sort", "The leap menu's order.") {
            val about = "The leap menu's order. Odin: as Odin's Leap Menu sorts it (by class). Custom: your own, set with the slots."
            change("Odin", about, on = P3Plan.odinSort) { P3Plan.odinSort = true; P3Plan.save() }
            change("Custom", about, on = !P3Plan.odinSort) { P3Plan.odinSort = false; P3Plan.save() }
        }
        row("Leap gap", "How far apart the bots' leaps onto an early enterer come.") {
            stepper("${"%.2f".format(Locale.ROOT, P3Plan.leapGap)}s apart", "Bots leap 0.25 s closer together.", "Bots leap 0.25 s further apart.",
                { P3Plan.leapGap = (P3Plan.leapGap - 0.25).coerceAtLeast(0.05); P3Plan.save() },
                { P3Plan.leapGap = (P3Plan.leapGap + 0.25).coerceAtMost(5.0); P3Plan.save() })
        }
        gap()

        row("Bots", "The bots' options.") {
            flag("Helpers", P3Plan.helper, "On: the bots help with your stacks: a stacked job of yours (right click it in the plan) and the preset's stacks are done by their bot too, whoever gets there first.") { P3Plan.helper = !P3Plan.helper; P3Plan.save() }
            flag("Wait For You", P3Plan.waitForYou, "On: a section's last bot job waits until you're at your early enter for the next section.") { P3Plan.waitForYou = !P3Plan.waitForYou; P3Plan.save() }
            flag("Bots", P3Sim.bots, "The plan does the other four's jobs at its times either way. On: bots show them (just the look: a late one is put on its job). Off: nobody's shown.") { P3Sim.botsS.value = !P3Sim.bots }
            flag("Real Movement", P3Sim.realMoves, P3Sim.realMovesS.description) { P3Sim.toggleRealMoves() }
        }
        row("Your role", "Your jobs, picked bots and stacks.") {
            change("Reset Role", "Your jobs, the bots you picked and your stacks back to the ${Roles.label(P3Sim.myClass)}'s $skill role.") { P3Plan.resetMine() }
        }

        // Your best runs: a class's bot can play your fastest P3 as it (S1 starts) instead of its role.
        row("Your best", "A bot plays your fastest run as its class.") {
            for (c in P3Plan.botOrder()) {
                val t = GhostStore.best(skill, c.name)?.let { "%.2f".format(Locale.ROOT, it.time / 20.0) }
                flag(Roles.label(c), P3Plan.ghostOn(c), "On: the ${Roles.label(c)} bot plays your fastest $skill P3 as ${Roles.label(c)} (S1 starts) instead of its role. " +
                    (t?.let { "Yours: ${it}s." } ?: "No run of yours yet: it plays its role.")) { P3Plan.toggleGhost(c) }
            }
        }
        if (P3Plan.skill == P3Plan.RANDOM) row("Bot times", "Random skill: each bot job takes between these two.") {
            stepper("${"%.1f".format(Locale.ROOT, P3Plan.botMin)}s", "Shorter.", "Longer.",
                { P3Plan.botMin = (P3Plan.botMin - 0.5).coerceAtLeast(0.0); P3Plan.save() },
                { P3Plan.botMin = (P3Plan.botMin + 0.5).coerceAtMost(P3Plan.botMax); P3Plan.save() })
            place(6)
            stepper("${"%.1f".format(Locale.ROOT, P3Plan.botMax)}s", "Shorter.", "Longer.",
                { P3Plan.botMax = (P3Plan.botMax - 0.5).coerceAtLeast(P3Plan.botMin); P3Plan.save() },
                { P3Plan.botMax = (P3Plan.botMax + 0.5).coerceAtMost(60.0); P3Plan.save() })
        }
    }

    /** Where you stand and look now (a tenth of a block; y to the hundredth, so a slab's height stays). */
    private fun here(): Spots.Spot? = mc.player?.let {
        Spots.Spot("set", Math.round(it.x * 10) / 10.0, Math.floor(it.y * 100) / 100.0, Math.round(it.z * 10) / 10.0, it.yRot, it.xRot)
    }

    private fun at(p: Spots.Spot) = "%.1f, %.1f, %.1f".format(Locale.ROOT, p.x, p.y, p.z)

    // ------------------------------------------------------------------ settings

    private fun settingsTab() {
        val forced = P3Sim.forcedTerminal
        if (forced != null) lastType = forced

        heading("Death ticks")
        row("Death ticks", P3Sim.deathTicksS.description) {
            DEATH_TICKS.forEachIndexed { i, (name, about) -> change(name, about, on = P3Sim.deathTicks == i) { P3Sim.deathTicksS.index = i } }
        }
        row("Masks", P3Sim.wornMaskS.description) {
            // Your own (the server puts them on you the moment they change: PartyServer.tick).
            flag("Real Masks", P3Sim.realMasksS.value, P3Sim.realMasksS.description) { P3Sim.realMasksS.value = !P3Sim.realMasksS.value }
            change("Spirit", P3Sim.wornMaskS.description, on = P3Sim.wornMaskS.index == 0) { P3Sim.wornMaskS.index = 0 }
            change("Bonzo", P3Sim.wornMaskS.description, on = P3Sim.wornMaskS.index != 0) { P3Sim.wornMaskS.index = 1 }
        }
        row("Pet", P3Sim.phoenixS.description) {
            change("Black Cat", P3Sim.phoenixS.description, on = !P3Sim.phoenixS.value) { P3Sim.phoenixS.value = false }
            change("Phoenix", P3Sim.phoenixS.description, on = P3Sim.phoenixS.value) { P3Sim.phoenixS.value = true }
        }

        // Terminals: random or one type for all.
        heading("Terminals")
        row("Type", P3Sim.terminalS.description) {
            change("Random", "Each terminal a random type, as on Hypixel.", on = forced == null) { P3Sim.terminalS.index = 0 }
            for (t in Terminals.Type.entries) {
                val (name, about) = TYPE_NAMES.getValue(t)
                change(name, about, on = forced == t) { lastType = t; P3Sim.terminalS.index = t.ordinal + 1 }
            }
        }
        row("Rules", "How terminals are dealt and clicked.") {
            flag("No Melodies", P3Sim.noMelodies, P3Sim.noMelodiesS.description) { P3Sim.noMelodiesS.value = !P3Sim.noMelodies }
            flag("Click Limit", P3Sim.clickLimitS.value, P3Sim.clickLimitS.description) { P3Sim.clickLimitS.value = !P3Sim.clickLimitS.value }
            flag("1st Click Melody", P3Sim.firstClickMelody, "Always First Click Melody: " + P3Sim.firstClickMelodyS.description) { P3Sim.firstClickMelodyS.value = !P3Sim.firstClickMelody }
        }
        luckRow("Terminal luck", P3Sim.termLuckS.description, { P3Sim.termLuckS.value }, { P3Sim.termLuckS.value = it })
        luckRow("SS luck", P3Sim.ssLuckS.description, { P3Sim.ssLuckS.value }, { P3Sim.ssLuckS.value = it })

        heading("You")
        row("Speed", P3Sim.speedS.description) {
            val s = P3Sim.speed
            slider("$s" + if (P3Sim.phoenixS.value) " (Phoenix out)" else " (${s + 100} with Black Cat)",
                (s - MIN_SPEED) / (MAX_SPEED - MIN_SPEED).toDouble(), P3Sim.speedS.description) { f ->
                setSpeed(MIN_SPEED + Math.round(f * (MAX_SPEED - MIN_SPEED) / 10).toInt() * 10)
            }
            stepper(null, "10 less speed.", "10 more speed.", { setSpeed(P3Sim.speed - 10) }, { setSpeed(P3Sim.speed + 10) })
        }
        row("Ping", P3Sim.pingS.description) {
            for (p in PINGS) change("${p}ms", P3Sim.pingS.description, on = P3Sim.ping == p) { P3Sim.pingS.value = p }
            flag("Jitter", P3Sim.jitterS.value, P3Sim.jitterS.description) { P3Sim.jitterS.value = !P3Sim.jitterS.value }
        }

        heading("Timings")
        row("Goldor kill", P3Sim.goldorKillS.description) {
            stepper("${P3Sim.goldorKill}t", "Less.", "More.",
                { P3Sim.goldorKillS.value = (P3Sim.goldorKill - 1).coerceAtLeast(10) }, { P3Sim.goldorKillS.value = (P3Sim.goldorKill + 1).coerceAtMost(120) }, P3Sim.goldorKillS.description)
        }
        row("Shortbow", P3Sim.shortbowCooldownS.description) {
            stepper("${P3Sim.shortbowCooldown}t", "Less.", "More.",
                { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown - 1).coerceAtLeast(1) }, { P3Sim.shortbowCooldownS.value = (P3Sim.shortbowCooldown + 1).coerceAtMost(20) }, P3Sim.shortbowCooldownS.description)
        }
        row("Hydra", P3Sim.hydraStartS.description) {
            stepper("${P3Sim.hydraStart}", "Less.", "More.",
                { P3Sim.hydraStartS.value = (P3Sim.hydraStart - 1).coerceAtLeast(0) }, { P3Sim.hydraStartS.value = (P3Sim.hydraStart + 1).coerceAtMost(10) }, P3Sim.hydraStartS.description)
        }
        row("Breaker", "The Dungeonbreaker.") {
            flag("Infinite Charges", P3Sim.breakerInfinite, "On: the Dungeonbreaker never runs out of charges.") { P3Sim.toggleBreakerInfinite() }
            // Off: the server brings the blocks back (PartyServer.tick).
            flag("Perma Break", P3Sim.breakerPerma, "On: blocks the Dungeonbreaker breaks stay broken (through restarts too) until you turn it off; then they all come back.") { P3Sim.toggleBreakerPerma() }
        }
        row("Mage beam", P3Sim.mageAllS.description) {
            flag("Mage On All", P3Sim.mageAll, "Mage Beam On All Classes: " + P3Sim.mageAllS.description) { P3Sim.mageAllS.value = !P3Sim.mageAll }
        }

        heading("Fight")
        row("Fight", "How the fight goes on.") {
            flag("Lava Bounce", P3Sim.lava, P3Sim.lavaS.description) { P3Sim.lavaS.value = !P3Sim.lava }
            flag("Start On Join", P3Sim.autoStart, P3Sim.autoStartS.description) { P3Sim.autoStartS.value = !P3Sim.autoStart }
            flag("Terror At Terms", P3Sim.terrorAtTerms, P3Sim.terrorAtTermsS.description) { P3Sim.toggleTerrorAtTerms() }
            flag("Black Cat Start", P3Sim.blackCatAtStart, "Black Cat At Start: " + P3Sim.blackCatS.description) { P3Sim.toggleBlackCatAtStart() }
            flag("Section Times", P3Sim.showTimes, P3Sim.showTimesS.description) { P3Sim.showTimesS.value = !P3Sim.showTimes }
            // Odin's Hide Players: only with Odin.
            if (P3Sim.odin) flag("Hide Players", P3Sim.hidePlayers, "On: other players out of sight.") { P3Sim.toggleHidePlayers() }
            flag("Debug Bots", P3Sim.debugBots, P3Sim.debugBotsS.description) { P3Sim.debugBotsS.value = !P3Sim.debugBots }
        }

        // Your hotbar: P1/P2's or P3's, whichever part of the fight you are in (HotbarLayout).
        heading("Other")
        val p3Part = PartyClient.phase != "P1" && PartyClient.phase != "P2"
        val part = if (p3Part) "P3" else "P1/P2"
        val saved = HotbarLayout.has(p3Part)
        val who = Roles.label(P3Sim.myClass)
        row("$part hotbar", "Your hotbar, inventory, gear and pet for the $who on ${P3Plan.skillName()}.") {
            act("Save", "Saves where your items are right now (hotbar and inventory), the slot you hold, what you wear and your pet, for the $who on ${P3Plan.skillName()} ($part). " +
                "Every start and restart lays it out (without one, your items stay as you left them).", on = saved) {
                SimActions.send("hotbarSave", "p3" to p3Part)
            }
            act("Default", "Forgets your saved $part hotbar for the $who on ${P3Plan.skillName()} and lays out the sim's default.", enabled = saved) {
                SimActions.send("hotbarReset", "p3" to p3Part)
            }
            act("Reset Items", "Your $part items, gear and pet back to your saved hotbar for the $who on ${P3Plan.skillName()} now (or the default, forgetting how you left them).") { SimActions.send("resetItems", "p3" to p3Part) }
        }
        row("Record", P3Sim.recordS.description) {
            flag("Record", P3Sim.record, P3Sim.recordS.description) { P3Sim.recordS.value = !P3Sim.record }
        }
        row("Leave", "Leaves the sim world.") {
            act("Leave", "Leaves the sim world.") { SimWorld.leave() }
        }
    }

    /** A luck setting (Terminal Luck, SS Luck), -100 to 100% in fives, read and written through [get] and [set]. */
    private fun luckRow(name: String, about: String, get: () -> Int, set: (Int) -> Unit) = row(name, about) {
        val luck = get()
        slider("${luck}%", (luck / 100.0 + 1) / 2, about, if (luck < 0) Theme.BAD else Theme.TEXT) { f ->
            val v = Math.round(f * 40).toInt() * 5 - 100
            if (v != get()) { set(v); P3Sim.saveSettings() }
        }
    }

    private fun setSpeed(v: Int) {
        val s = v.coerceIn(MIN_SPEED, MAX_SPEED)
        if (s == P3Sim.speed) return
        // Now, not at the next start: the server applies it the moment it changes (PartyServer.tick: Fight.applySpeed).
        P3Sim.speedS.value = s
    }

    // ------------------------------------------------------------------ teleport

    /**
     * A row per part of the run (P1, P2, S1-S4), its places left to right in the order you get to them. CEE3 is
     * the high EE3. The spots are here, not in [Spots], so a hotswap brings them in.
     */
    private fun teleportTab() {
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
        for ((group, spots) in groups) row(group, "Teleports: $group, in the order you get to them.") {
            for ((name, s) in spots) {
                if (s == null) opt(name, false, "Not set.", null)
                else act(name, "To ${if (s.name == name) "$group $name" else s.name}: ${xyz(s.x, s.y, s.z)}, facing ${facing(s.yaw)}.") { tp(s.x, s.y, s.z, s.yaw, s.pitch) }
            }
        }
    }

    private fun tp(x: Double, y: Double, z: Double, yaw: Float?, pitch: Float?) =
        SimActions.send("tp", "x" to x, "y" to y, "z" to z, "yaw" to yaw, "pitch" to pitch)

    private fun xyz(x: Double, y: Double, z: Double) = "%.1f, %.1f, %.1f".format(Locale.ROOT, x, y, z)

    private fun facing(yaw: Float) = when (Math.floorMod(Math.round(yaw / 90f), 4)) { 0 -> "south"; 1 -> "west"; 2 -> "north"; else -> "east" }

    // ------------------------------------------------------------------ practice

    /** Practice: your custom one first (its start, the section's jobs, checkpoints, the code), then each section's presets. */
    private fun practiceTab() {
        // Custom: your start, the section's jobs you pick, checkpoints in order.
        val start = Practice.customStart
        val cps = Practice.checkpoints.size
        heading("Custom")
        note("Your own practice: a start position (its section is where you stand), that section's jobs you pick and checkpoints to reach in order (within 1 block, the same height), all timed.")
        row("Custom", "Start it, or set where it starts.") {
            act("Start Custom", if (start != null) "Starts the custom practice from your start position: your jobs (every section's picks) then the $cps checkpoint(s), timed." else "Set the Start Position first.",
                enabled = start != null) { SimActions.send("practiceCustom") }
            change("Start Position", "Click: the custom practice starts where you stand, facing as you are; its section is the one you're in (or the nearest). " +
                (start?.let { "Now: S${Practice.customSection}, ${at(it)}." } ?: "Not set."), on = start != null) {
                here()?.let { h ->
                    val held = mc.player?.mainHandItem
                    val n = Practice.setStart(h, held?.let { Practice.itemKey(it) })
                    EngineerClient.msg("§7Custom practice start: §fS$n§7, ${at(h)}${held?.takeIf { !it.isEmpty }?.let { "§7, holding §f${it.hoverName.string}" } ?: ""}§7.")
                }
            }
            change("Clear", "Clears the custom practice: start, jobs, checkpoints and leap waypoints; the timer back to 0.") { Practice.clearCustom(); EngineerClient.msg("§7Custom practice cleared.") }
        }
        row("Waypoints", "Left click adds one where you stand; right click removes the last.") {
            clicks(if (cps > 0) "Checkpoint ($cps)" else "Checkpoint", cps > 0,
                "Left click: a checkpoint where you stand (reached within 1 block across, at this exact height; in order). Right click: removes the last one. Now: $cps.",
                left = { here()?.let { h -> Practice.addCheckpoint(h); EngineerClient.msg("§7Checkpoint §f${Practice.checkpoints.size}§7: ${at(h)}.") } },
                right = {
                    EngineerClient.msg(if (Practice.checkpoints.isEmpty()) "§7No checkpoints to remove." else "§7Checkpoint §f${Practice.checkpoints.size}§7 removed.")
                    Practice.removeCheckpoint()
                })
            val leaps = Practice.leapWaypoints.size
            clicks(if (leaps > 0) "Leap Waypoint ($leaps/4)" else "Leap Waypoint", leaps > 0,
                "Left click: where you stand becomes a leap's landing in the custom practice: the 1st the leap menu's top left, then top right, bottom left, bottom right. Right click: removes the last one. Now: $leaps/4.",
                left = {
                    here()?.let { h ->
                        val n = Practice.addLeapWaypoint(h)
                        EngineerClient.msg(if (n == 0) "§7All 4 leap waypoints are set (right click removes the last)." else "§7Leap waypoint §f$n§7 (${QUADRANTS[n - 1]}): ${at(h)}.")
                    }
                },
                right = {
                    val n = Practice.leapWaypoints.size
                    EngineerClient.msg(if (n == 0) "§7No leap waypoints to remove." else "§7Leap waypoint §f$n§7 (${QUADRANTS[n - 1]}) removed.")
                    Practice.removeLeapWaypoint()
                })
        }
        // Practice's start timer, 0 to 5 s in tenths.
        val delayAbout = "Every practice start: you stand on its start this long before anything is up (terminals, levers, Goldor) and the clock runs. 0: at once."
        row("Timer", delayAbout) {
            slider("${"%.1f".format(Locale.ROOT, Practice.startDelay)}s", Practice.startDelay / 5.0, delayAbout) { f -> Practice.setStartDelay(Math.round(f * 50) / 10.0) }
        }

        // The jobs: always the section you're in now (picks from other sections stay).
        val sec = mc.player?.let { Practice.sectionAt(it.x, it.y, it.z) } ?: 1
        val picked = Practice.customJobsIn(sec)
        val others = (1..4).filter { it != sec }.mapNotNull { s -> Practice.customJobsIn(s).takeIf { it.isNotEmpty() }?.let { j -> "S$s ${j.joinToString(" ") { COLUMNS[column(it)].first }}" } }.joinToString(", ")
        row("S$sec jobs", "The section you're in: go to another to pick its jobs too (the picks of every section stay)." +
            (others.takeIf { it.isNotEmpty() }?.let { " Picked elsewhere: $it." } ?: "")) {
            for (job in P3Plan.jobsIn(sec).sortedBy { column(it) }) {
                val on = job in picked
                change(COLUMNS[column(job)].first, "${jobName(job)}: ${if (on) "yours to do in the custom practice" else "done at its start"}. Click: ${if (on) "done at the start" else "yours"}.", on = on) { Practice.toggleJob(job) }
            }
        }
        row("Code", "Your custom practice as a short code, to send to someone or take theirs.") {
            change("Export", "Copies the custom practice as a short code (start, jobs, checkpoints, leap waypoints, start timer) to send to someone.") {
                val code = Practice.exportCode()
                mc.keyboardHandler.clipboard = code
                EngineerClient.msg("§7Custom practice code copied: §f$code")
            }
            change("Import", "Replaces your custom practice with the code on your clipboard.") {
                if (Practice.importCode(mc.keyboardHandler.clipboard)) EngineerClient.msg("§7Custom practice imported (S${Practice.customSection}, ${Practice.customJobs.size} job(s), ${Practice.checkpoints.size} checkpoint(s), ${Practice.leapWaypoints.size} leap waypoint(s)).")
                else EngineerClient.msg("§cYour clipboard doesn't hold a custom practice code.")
            }
        }

        // The section tabs, and the open section's presets (S1 when the menu opens), a row per group.
        heading("Presets")
        val s = practiceSec.takeIf { it in 1..4 } ?: 1
        row("Section", "Each section's own practices.") {
            for (k in 1..4) change("S$k", "Section $k's practices.", on = s == k) { practiceSec = k }
        }
        val groups = Practice.presetGroups(s)
        if (groups.isEmpty()) note("No presets for S$s.")
        for ((head, ps) in groups) row(head.ifEmpty { "S$s" }, "Section $s's presets: a start, the jobs and checkpoints (the rest of the section done), and their own start timer.") {
            for (p in ps) act(p.name, "Practice ${p.name} (S$s): ${p.jobs.joinToString(", ") { jobName(it) }}" +
                (if (p.checkpoints.isNotEmpty()) ", then ${p.checkpoints.size} checkpoint(s)" else "") +
                (p.delay?.let { ". Start timer ${"%.1f".format(Locale.ROOT, it)}s." } ?: ". Start timer: the slider's.")) { SimActions.send("practicePreset", "name" to p.name) }
        }
    }

    // ------------------------------------------------------------------ boss

    /** The boss fight: where the run stops. */
    private fun bossTab() {
        row("Stop", "Where the run stops on its own (as the menu's Stop: everything back as built).") {
            flag("Stop After Terms", P3Sim.termsOnly, P3Sim.termsOnlyS.description) { P3Sim.toggleStopAfter(terms = true) }
            flag("Stop After Goldor", P3Sim.p3Only, P3Sim.p3OnlyS.description) { P3Sim.toggleStopAfter(terms = false) }
        }
        note("Where the run stops on its own, as the menu's Stop does: everything back as built. With neither, it goes on to Necron.")
    }

    // ------------------------------------------------------------------ rows

    /** The tab's left edge and right end, and where the next control goes: [cx] across, [cy] down from a row's [rowX]. */
    private var cl = 0
    private var rowEnd = 0
    private var rowX = 0
    private var cx = 0
    private var cy = 0

    /** A row: [label] on the left ([about] on hover), then [body]'s controls left to right, wrapping under each other. */
    private fun row(label: String, about: String?, body: () -> Unit) {
        text(g, fit(label, LABEL_W - 6), cl, cy + 3, Theme.SILVER)
        tip(cl, cy, LABEL_W - 6, 13, about)
        rowX = cl + LABEL_W
        cx = rowX
        body()
        cy += 18
    }

    /** Where a control [w] wide goes in the row: next to the last, or at the start of a new line when it doesn't fit. */
    private fun place(w: Int): Int {
        if (cx + w > rowEnd && cx > rowX) newLine()
        val x = cx
        cx += w + 3
        return x
    }

    private fun newLine() {
        cx = rowX
        cy += 16
    }

    /** A group's heading, dim and in capitals as the rail's sections. */
    private fun heading(t: String) {
        if (cy > (view?.get(1) ?: 0) - scroll + 4) cy += 4
        text(g, t.uppercase(Locale.ROOT), cl, cy + 2, Theme.DIM)
        rect(g, cl + font.width(t.uppercase(Locale.ROOT)) + 6, cy + 6, rowEnd - cl - font.width(t.uppercase(Locale.ROOT)) - 6, 1, Theme.PANEL_EDGE)
        cy += 14
    }

    /** A dim note across the tab, wrapped. */
    private fun note(t: String) {
        for (line in wrap(t, rowEnd - cl)) {
            text(g, line, cl, cy, Theme.DIM)
            cy += 10
        }
        cy += 6
    }

    private fun gap() { cy += 6 }

    /** A boxed choice: gold when [on]; [action] null greys it out. */
    private fun opt(label: String, on: Boolean, about: String?, action: (() -> Unit)?) {
        val w = choiceWidth(label)
        val x = place(w)
        choice(g, mx, my, label, x, cy, on, action?.let { Runnable(it) })
        tip(x, cy, w, 13, about)
    }

    /** Changes something, then saves it; the menu stays open to show it. */
    private fun change(label: String, about: String, on: Boolean = false, run: () -> Unit) =
        opt(label, on, about) { run(); P3Sim.saveSettings() }

    /** Closes the menu, back in the game, and does it; greyed out unless [enabled]. */
    private fun act(label: String, about: String, on: Boolean = false, enabled: Boolean = true, run: () -> Unit) =
        opt(label, on, about, if (enabled) { { mc.gui.setScreen(null); run() } } else null)

    /** A switch and its name: [run] flips it, then it's saved. */
    private fun flag(name: String, on: Boolean, about: String, run: () -> Unit) {
        val w = font.width(name) + 32
        val x = place(w)
        val hover = hot(x, cy, w, 13)
        round(g, x, cy, w, 13, if (hover) Theme.ROW_HOVER else Theme.RAIL)
        toggle(g, "flag:$name", x + 5, cy + 3, on, Theme.GOOD)
        text(g, name, x + 27, cy + 3, if (on || hover) Theme.TEXT else Theme.MUTED)
        click(x, cy, w, 13) { run(); P3Sim.saveSettings() }
        tip(x, cy, w, 13, about)
    }

    /** "-  [value]  +": [down] and [up] change it (then it's saved); no value, just the two. */
    private fun stepper(value: String?, downAbout: String, upAbout: String, down: () -> Unit, up: () -> Unit, about: String? = null) {
        change("-", downAbout, run = down)
        if (value != null) {
            val w = font.width(value) + 8
            val x = place(w)
            textCenter(g, value, x + w / 2, cy + 3, Theme.TEXT)
            tip(x, cy, w, 13, about)
        }
        change("+", upAbout, run = up)
    }

    /** A box that takes right clicks too ([left] / [right]); lit when [on]. */
    private fun clicks(label: String, on: Boolean, about: String, left: () -> Unit, right: () -> Unit) {
        val w = choiceWidth(label)
        val x = place(w)
        cell(label, if (on) Theme.GOLD else Theme.MUTED, on, x, cy, w, about, left, right)
    }

    /** A cell 13 tall: [label] centred in [color], tinted when [lit]; [left] and [right] for each button. */
    private fun cell(label: String, color: Int, lit: Boolean, x: Int, y: Int, w: Int, about: String?, left: () -> Unit, right: () -> Unit) {
        val hover = hot(x, y, w, 13)
        round(g, x, y, w, 13, if (lit) Theme.alpha(color, 0x30) else if (hover) Theme.ROW_HOVER else Theme.RAIL)
        textCenter(g, fit(label, w - 4), x + w / 2, y + 3, if (hover && !lit) Theme.TEXT else color)
        click(x, y, w, 13, Runnable(left))
        rightClick(x, y, w, 13, Runnable(right))
        tip(x, y, w, 13, about)
    }

    /** A slider's track, the value after it; dragging calls [set] with where along it (0 to 1). */
    private fun slider(value: String, frac: Double, about: String, color: Int = Theme.TEXT, set: (Double) -> Unit) {
        val w = SLIDER_W + 6 + font.width(value)
        val x = place(w)
        val f = frac.coerceIn(0.0, 1.0)
        val grabbed = dragging?.let { it.x == x + 5 && it.y == cy } == true || (dragging == null && hot(x, cy, SLIDER_W, 13))
        round(g, x, cy, SLIDER_W, 13, Theme.RAIL)
        val hx = x + 5 + Math.round(f * (SLIDER_W - 10)).toInt()
        rect(g, x + 5, cy + 6, SLIDER_W - 10, 1, Theme.DIM)
        rect(g, x + 5, cy + 6, hx - x - 5, 1, Theme.GOLD)
        round(g, hx - 2, cy + 2, 5, 9, if (grabbed) Theme.TEXT else Theme.GOLD)
        text(g, value, x + SLIDER_W + 6, cy + 3, color)
        tip(x, cy, w, 13, about)
        // Only where it shows in the scrolled window can it be grabbed.
        val v = view
        if (v == null || (cy >= v[1] && cy + 13 <= v[1] + v[3])) building += Slider(x + 5, cy, SLIDER_W - 10, set)
    }

    /** A slider's track this frame: [w] wide from [x], on the row at [y]. */
    private class Slider(val x: Int, val y: Int, val w: Int, val set: (Double) -> Unit) {
        fun at(mx: Double) = ((mx - x) / w).coerceIn(0.0, 1.0)
    }

    /** The last whole frame's sliders, the one being drawn, and the one being dragged. */
    private var sliders: List<Slider> = emptyList()
    private var building = ArrayList<Slider>()
    private var dragging: Slider? = null

    /** The tab's scrolled window (x, y, w, h) while it's drawn, and how far it's scrolled. */
    private var view: IntArray? = null
    private var maxScroll = 0

    /** Under the mouse, and (in the tab) inside its window. */
    private fun hot(x: Int, y: Int, w: Int, h: Int): Boolean {
        val v = view
        return over(mx, my, x, y, w, h) && (v == null || over(mx, my, v[0], v[1], v[2], v[3]))
    }

    /** [about] as a tooltip while the mouse is over the area. */
    private fun tip(x: Int, y: Int, w: Int, h: Int, about: String?) {
        if (about != null && hot(x, y, w, h)) g.setComponentTooltipForNextFrame(font, wrap(about, 240).map { Component.literal(it) }, mx, my)
    }

    // ------------------------------------------------------------------ input

    override fun pressed(mx: Double, my: Double): Boolean {
        val s = sliders.firstOrNull { mx >= it.x - 5 && mx < it.x + it.w + 5 && my >= it.y && my < it.y + 13 } ?: return false
        dragging = s
        s.set(s.at(mx))
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val s = dragging ?: return super.mouseDragged(event, dx, dy)
        s.set(s.at(event.x()))
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = null
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        if (mx >= contentX() && mx < contentX() + contentW() + 6 && my >= py + 23 && my < py + ph - 28) {
            scroll = (scroll - Math.signum(scrollY).toInt() * 18).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY)
    }

    private fun onOff(b: Boolean) = if (b) "§aON" else "§cOFF"

    /** In a party: more than one player in the sim (starts and stops are everyone's). */
    private val inParty get() = PartyClient.party

    private companion object {
        const val MAX_W = 660
        const val MAX_H = 420
        const val RAIL_W = 118
        const val LABEL_W = 78
        const val SEC_W = 22
        const val LEAP_W = 80
        const val SLIDER_W = 120
        const val MIN_SPEED = 100
        const val MAX_SPEED = 750
        /** Another player's job in the plan: the light purple of their name. */
        const val MATE = 0xFFFF77FF.toInt()
        val QUADRANTS = listOf("top left", "top right", "bottom left", "bottom right")

        /** The tab open (Plan the first time). */
        private var tab = Tab.PLAN
        /** Practice's open section page, 1-4; the last one each time the menu opens. */
        private var practiceSec = 0
        /** How far the tab is scrolled (back to the top on another tab). */
        private var scroll = 0

        /** The type a terminal type pick last chose. */
        var lastType = Terminals.Type.ORDER

        val PINGS = listOf(0, 50, 100, 150, 200, 300)

        val SKILLS = listOf(
            Triple(0, "Normal PF", "The bots play at a normal party finder pace: P3 in about 34 s."),
            Triple(1, "Quality PF", "The bots play at a quality party finder pace: P3 in about 28 s."),
            Triple(2, "Optimal PF", "The bots play at an optimal party finder pace: P3 in about 22 s."),
            Triple(P3Plan.THEORETICAL, "Theoretical", "The fastest terms, in theory: a far EE2, a high EE3, an EE4 by the core."),
        )

        /** A class's colour by its name, as the ADS screens draw it. */
        fun classColor(name: String): Int =
            runCatching { 0xFF000000.toInt() or net.rustclear.mod.engine.DungeonClass.valueOf(name).rgb }.getOrDefault(Theme.MUTED)

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
