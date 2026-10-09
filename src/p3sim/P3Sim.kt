package com.engineerclient.p3sim

import com.engineerclient.index
import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.Island
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import com.mojang.blaze3d.platform.InputConstants

/**
 * P3 Sim: F7's boss fight in a singleplayer world of its own ("p3sim"), to practice it alone.
 *
 * The world is the real arena at Hypixel's coordinates; the fight runs on the world's own
 * (integrated) server ([SimServer], [Fight]): Goldor, terminals, levers, devices, gates, death
 * ticks, and Maxor/Storm/Necron around it. The menu ([SimScreen]: the keybind, `/p3sim`, or the
 * SkyBlock Menu star in the hotbar) starts any phase or section and teleports anywhere.
 *
 * Nothing of it exists anywhere else: every piece checks [inSim] (the client) or
 * [SimServer.isSim] (the server), both of which are only true in that one singleplayer world.
 */
object P3Sim : Module(
    name = "P3 Sim",
    category = Category.custom("Engineer Client", 860, 10),
    description = "F7's boss in a singleplayer world of its own: /p3sim (or the title screen button) opens it. Only ever active in that world.",
    key = null,
) {
    val menuKey by KeybindSetting("Menu Keybind", InputConstants.UNKNOWN, "Opens the P3 Sim menu (a big Restart) in the sim world, as /p3sim and the SkyBlock Menu star in your hotbar do; the full menu is in Esc. Outside it, opens the sim.").onPress { openMenuOrSim() }
    val restartKey by KeybindSetting("Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts whatever you last started again (P3, S2, P2...), from scratch; in practice, the practice.").onPress {
        if (inSim) SimServer.run("restart") { if (Practice.active) Practice.restart() else Fight.start(Fight.lastStart) }
    }
    val practiceKey by KeybindSetting("Practice Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts your practice (the menu's Practice tab) again. A left click with the Infinileap or in its menu does too.").onPress {
        if (inSim) SimServer.run("practice restart") { Practice.restart() }
    }
    enum class ClassOption { HEALER, BERSERK, ARCHER, TANK, MAGE }
    enum class DeathTickOption { OFF, WARN, MASKS }
    enum class TerminalOption { RANDOM, ORDER, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }
    enum class MaskOption { SPIRIT, BONZO }

    // Kept as objects (not delegates) so the sim's own menu can change them.
    val classS = +SelectorSetting("Your Class", ClassOption.ARCHER, desc = "Your dungeon class (Odin's party list and leap menu). The four bots are the other classes. What you do in P3 is the menu's Plan tab.")
    val speedS = +NumberSetting("Speed", 600, 100..750, 10, desc = "Your Skyblock speed without Black Cat. Black Cat adds 100 (and 100 to the speed cap), so 450 is 550 with it out, as in recorded P3 starts (1.55 blocks a tick sprinting); Phoenix out has no Black Cat bonus.")
    val botsS = +BooleanSetting("Party Bots", true, desc = "Four bots do the rest of the party's terminals, levers, devices and gates at the pace of fast Better PF runs. Off: you do everything.")
    val deathTicksS = +SelectorSetting("Death Ticks", DeathTickOption.MASKS, desc = "Goldor's death tick (every 60 ticks, hits anyone in a section ahead): Warn only says so; Masks uses your Spirit Mask, Bonzo's Mask and Phoenix as Hypixel does, and with none left you die (back to the section's start).")
    val terminalS = +SelectorSetting("Terminals", TerminalOption.RANDOM, desc = "Every terminal as this type, or random as on Hypixel.")
    val pingS = +NumberSetting("Simulated Ping", 0, 0..300, 10, unit = "ms", desc = "Delays the server's answer to your clicks and items by this much, like playing on Hypixel with that ping.")
    val jitterS = +BooleanSetting("Ping Jitter", true, desc = "Simulated Ping varies like a real connection: usually a few ms either way, now and then 30+ ms more, rarely a lag spike (spread recorded on Hypixel). Off: a fixed delay.")
    val goldorKillS = +NumberSetting("Goldor Kill Time", 20, 10..120, 1, unit = " ticks", desc = "How long after Goldor starts flying to the core his \"....\" line comes, i.e. he dies (recorded runs: median 43, range 17-74).")
    val shortbowCooldownS = +NumberSetting("Shortbow Cooldown", 2, 1..20, 1, unit = " ticks", desc = "Ticks between shots of the Terminator, Spirit Shortbow and Mosquito Shortbow: 5 at 100% attack speed, on every bow and whatever Terror armor you wear (recordings: Terminator 5, Mosquito 5). A click inside it fires when it ends. Nasty Bite has its own 10.")
    val hydraStartS = +NumberSetting("Hydra Stacks At Start", 10, 0..10, 1, desc = "Hydra Strike stacks every start from the menu (P1, P3, a section...) begins with. Going on from one phase to the next keeps what you have.")
    val clickLimitS = +BooleanSetting("Terminal Click Limit", true, desc = "As on Hypixel: a terminal takes at most 5 clicks in any 10 ticks; the rest are dropped without an answer.")
    val noMelodiesS = +BooleanSetting("No Melodies", true, desc = "Random terminals are never melodies.")
    val recordS = +BooleanSetting("Record Runs", false, desc = "Writes each run, tick by tick (you, the bots, what's left, chat), to config/engineerclient/p3sim-runs (last 20 kept), to look at what went wrong.")
    val debugBotsS = +BooleanSetting("Debug Bots", false, desc = "Chat lines for everything the P3 bots do: where they head and why, jobs, leaps, early enters (on the spot, who they wait for, why they move on).")
    val breakerRefillS = +NumberSetting("Dungeonbreaker Refill", 3, 1..10, 1, unit = "/s", desc = "Charges back each second (20 max), in irregular +2 steps. Main server: ~6 a second; alpha ~2.")
    val breakerRegenS = +NumberSetting("Dungeonbreaker Regen", 11.0, 1.0..30.0, 0.5, unit = "s", desc = "How long a broken block stays broken (recordings: ~11 s; the 21st break brings back the oldest 41 ticks later).")
    val realMasksS = +BooleanSetting("Real Masks", true, desc = "Masks are real helmets: only the one you wear can save you, swap them in /stats (cooldowns stay with each mask). Off: whichever is ready saves you.")
    val wornMaskS = +SelectorSetting("Starting Mask", MaskOption.SPIRIT, desc = "Real Masks: the mask you wear (/stats swaps it).")
    val phoenixS = +BooleanSetting("Phoenix Pet", false, desc = "Your pet: Phoenix (saves you from a death, no Black Cat speed bonus) or Black Cat (+100 speed). The Pet Rod swaps them.")
    val realMovesS = +BooleanSetting("Real Bot Movement", true, desc = "P3 bots move as real players do: along routes recorded in Better PF runs (sprints, jumps, lava bounces, Bonzo boosts, stonks), sped up only when a job's time needs it. Off: the old straight walk and Hyperion blinks.")
    val terrorAtTermsS = +BooleanSetting("Terror At Terms", false, desc = "Every P3 start puts the Terror loadout on (Terror armour, Bonzo's Mask, Black Cat) over your saved gear.")
    val lavaS = +BooleanSetting("Lava Bounce", true, desc = "Lava bounces you up as on Hypixel. Off: plain vanilla lava (no damage).")
    val p3OnlyS = +BooleanSetting("Stop After P3", false, desc = "End at Goldor's death instead of going on to Necron.")
    val autoStartS = +BooleanSetting("Start On Join", false, desc = "Start P3 as soon as you join the sim world.")
    val showTimesS = +BooleanSetting("Section Times", true, desc = "Each section's time in chat as it ends, and a summary at the core.")

    /** Practice: each task's time as you do it (like the Simon Says sim's). */
    private val practiceHud by HUD("Practice Splits", "In practice (the menu's Practice tab): the time, and each of your tasks with the time it was done.", true, 10, 210, 1.5f) { example ->
        val lines = if (example) listOf("§6Practice S2 §f6.45", "§7Lights §a2.10", "§7T3 §a4.85", "§7EE3 §e...")
        else {
            val mode = Practice.mode
            if (!inSim || mode == null || Practice.tasks.isEmpty()) return@HUD 0 to 0
            val next = Practice.tasks.firstOrNull { it.at < 0 }
            listOf("§6Practice $mode ${if (Practice.endTicks >= 0) "§a" else "§f"}${Practice.secs(Practice.ticks)}") +
                Practice.tasks.map { t -> "§7${t.label} " + if (t.at >= 0) "§a${Practice.secs(t.at)}" else if (t === next) "§e..." else "§8-" }
        }
        lines.forEachIndexed { i, l -> text(l, 0, i * 10, com.odtheking.odin.utils.Colors.WHITE, shadow = true) }
        (lines.maxOf { mc.font.width(it) }) to lines.size * 10
    }

    /** Practice: the final time, large, as Term Info's section times look. */
    private val practiceTimeHud by HUD("Practice Time", "A practice's final time, large (as Term Info's Section Time), until it starts again.", true, 420, 300, 5f) { example ->
        val t = if (example) "§514.35" else Practice.endTicks.takeIf { inSim && Practice.active && it >= 0 }?.let { "§5${Practice.secs(it)}" } ?: return@HUD 0 to 0
        text(t, 0, 0, com.odtheking.odin.utils.Colors.WHITE, shadow = true)
        mc.font.width(t) to 10
    }

    init {
        // Shown by default (Odin starts a toggleable HUD hidden); a saved config still decides.
        practiceHud.enabled = true; practiceTimeHud.enabled = true
    }

    val autoStart: Boolean get() = autoStartS.value
    val showTimes: Boolean get() = showTimesS.value
    val myClass: DungeonClass get() = Party.CLASSES[classS.index.coerceIn(0, 4)]
    val speed: Int get() = speedS.value.toInt()
    val bots: Boolean get() = botsS.value
    val deathTicks: Int get() = deathTicksS.index
    val ping: Int get() = pingS.value.toInt()
    val jitter: Boolean get() = jitterS.value
    val goldorKill: Int get() = goldorKillS.value.toInt()
    val p3Only: Boolean get() = p3OnlyS.value
    val shortbowCooldown: Int get() = shortbowCooldownS.value.toInt()
    /** Terror armor pieces worn right now (TERROR_* ids in the four armour slots, helmet included): 0 to 4. Hydra Strike follows it. */
    val terrorPieces: Int get() = Sim.player?.let { p ->
        listOf(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET)
            .count { SimItems.idOf(p.getItemBySlot(it))?.startsWith("TERROR_") == true }
    } ?: 0
    val hydraStart: Int get() = hydraStartS.value.toInt()
    val lava: Boolean get() = lavaS.value
    val noMelodies: Boolean get() = noMelodiesS.value
    val clickLimit: Boolean get() = clickLimitS.value
    val debugBots: Boolean get() = debugBotsS.value
    val record: Boolean get() = recordS.value

    /** Hide Players is Odin's own (its module and its Hide All / Distance settings); in the sim its rule hides the bots too. */
    val hidePlayers: Boolean get() = com.odtheking.odin.features.impl.render.HidePlayers.enabled
    fun toggleHidePlayers() = com.odtheking.odin.features.impl.render.HidePlayers.toggle()

    /** A bot (a mannequin in the sim) Odin's Hide Players would hide if it were a player. Client thread. */
    @JvmStatic
    fun hideBot(e: net.minecraft.world.entity.Entity): Boolean {
        if (!hidePlayers || !inSim || e !is net.minecraft.world.entity.decoration.Mannequin) return false
        val hp = com.odtheking.odin.features.impl.render.HidePlayers
        if ((hp.settings["Hide all"] as? BooleanSetting)?.value == true) return true
        val d = (hp.settings["Distance"] as? NumberSetting<*>)?.value?.toDouble() ?: 3.0
        val me = mc.player ?: return false
        return e.distanceToSqr(me) <= d * d
    }
    val breakerRefill: Int get() = breakerRefillS.value.toInt()
    val breakerRegen: Double get() = breakerRegenS.value.toDouble()
    val realMasks: Boolean get() = realMasksS.value
    val phoenix: Boolean get() = phoenixS.value
    // Null-safe: a hotswapped game has the setting null until it is relaunched (off till then).
    val realMoves: Boolean get() = (realMovesS as BooleanSetting?)?.value != false
    fun toggleRealMoves() { (realMovesS as BooleanSetting?)?.let { it.value = !it.value } }
    val terrorAtTerms: Boolean get() = (terrorAtTermsS as BooleanSetting?)?.value == true
    fun toggleTerrorAtTerms() { (terrorAtTermsS as BooleanSetting?)?.let { it.value = !it.value } }
    val forcedTerminal: Terminals.Type? get() = terminalS.index.let { if (it == 0) null else Terminals.Type.entries[it - 1] }

    /** True only in the p3sim singleplayer world (client side). */
    @JvmStatic
    val inSim: Boolean
        get() {
            val s = SimServer.server ?: return false
            return mc.hasSingleplayerServer() && mc.singleplayerServer === s && mc.level != null
        }

    fun init() {
        SimServer.register()
        P3Plan.load()
        // /p3sim: the menu in the sim (or opens the sim); /p3sim <start> starts it; /p3sim rebuild remakes the world.
        // /stats: Hypixel's equipment window, here to swap masks. On the sim's own server only (a
        // server command, so Hypixel's /stats is never touched).
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(net.minecraft.commands.Commands.literal("stats")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Masks.openStats(p) }; 1 })
            // /loadouts: Hypixel's Loadouts window, same sim-server-only rule.
            dispatcher.register(net.minecraft.commands.Commands.literal("loadouts")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Loadouts.open(p) }; 1 })
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val cmd = ClientCommands.literal("p3sim").executes { openMenuOrSim(); 1 }
            for (s in Fight.Start.entries) cmd.then(ClientCommands.literal(s.name.lowercase()).executes {
                if (inSim) SimServer.run("cmd start") { Fight.start(s) } else SimWorld.open(); 1
            })
            cmd.then(ClientCommands.literal("stop").executes { SimServer.run("cmd stop") { Fight.end() }; 1 })
            cmd.then(ClientCommands.literal("rebuild").executes { SimWorld.rebuild(); 1 })
            dispatcher.register(cmd)
        }
        ClientTickEvents.START_CLIENT_TICK.register { EngineerClient.safely("p3sim bridge") { bridge(); SimItems.clientTick() } }
        ScreenEvents.AFTER_INIT.register { _, screen, w, _ ->
            // The title screen's Minecraft Realms button becomes two: P3 Sim on the left half, Join
            // Hypixel on the right. No Realms button (another mod's menu): a small P3 Sim button in the corner.
            if (screen is TitleScreen) EngineerClient.safely("p3sim title button") {
                val widgets = Screens.getWidgets(screen)
                val realms = widgets.filterIsInstance<Button>()
                    .firstOrNull { (it.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key == "menu.online" }
                if (realms == null) {
                    widgets.add(Button.builder(Component.literal("P3 Sim")) { SimWorld.open() }.bounds(w - 64, 4, 60, 16).build())
                    return@safely
                }
                val half = (realms.width - 4) / 2
                widgets.remove(realms)
                widgets.add(Button.builder(Component.literal("P3 Sim")) { SimWorld.open() }
                    .bounds(realms.x, realms.y, half, realms.height).build())
                widgets.add(Button.builder(Component.literal("Join Hypixel")) { com.engineerclient.misc.RandomStuff.joinHypixel(screen) }
                    .bounds(realms.x + realms.width - half, realms.y, half, realms.height).build())
            }
            // In the sim, Esc has two menus. P3 Sim Menu (the big Restart) takes Open to LAN's place (right
            // under Save and Quit if that isn't there), and the whole menu shifts so the cursor, which
            // opening it puts in the middle of the screen, is already on it. P3 Sim Full Menu (every tab)
            // goes under Save and Quit.
            if (screen is net.minecraft.client.gui.screens.PauseScreen && inSim) EngineerClient.safely("p3sim pause button") {
                val widgets = Screens.getWidgets(screen)
                val buttons = widgets.filterIsInstance<Button>()
                fun key(b: Button) = (b.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key
                val lan = buttons.firstOrNull { key(it) in LAN_KEYS }
                val quit = buttons.firstOrNull { key(it) in QUIT_KEYS } ?: buttons.maxByOrNull { it.y }
                val main = Button.builder(Component.literal("§6P3 Sim Menu")) { mc.gui.setScreen(SimRestartScreen()) }
                val b = when {
                    lan != null -> { widgets.remove(lan); main.bounds(lan.x, lan.y, lan.width, lan.height) }
                    quit != null -> main.bounds(quit.x, quit.y + quit.height + 4, quit.width, 20)
                    else -> main.bounds(4, 4, 90, 20)
                }.build()
                widgets.add(b)
                if (lan != null || quit != null) {
                    val dy = screen.height / 2 - (b.y + b.height / 2)
                    for (w in widgets) w.y += dy
                }
                // Under Save and Quit, or under the main one when that took Save and Quit's spot.
                val above = if (lan != null && quit != null) quit else b
                widgets.add(Button.builder(Component.literal("§6P3 Sim Full Menu")) { mc.gui.setScreen(SimScreen()) }
                    .bounds(above.x, above.y + above.height + 4, above.width, 20).build())
            }
        }
    }

    /** The Esc menu's Save and Quit button (Disconnect if it's shown that way). */
    private val QUIT_KEYS = setOf("menu.returnToMenu", "menu.disconnect")
    /** Open to LAN: vanilla's, or Clean Menus' multiplayer options in its place. */
    private val LAN_KEYS = setOf("menu.shareToLan", "menu.multiplayerOptions.button")

    fun openMenuOrSim() {
        if (inSim) mc.execute { mc.gui.setScreen(SimRestartScreen()) } else SimWorld.open()
    }

    // ------------------------------------------------------------------ Odin

    private var bridged = false

    /**
     * Tells Odin it is in F7's boss with a party of five, every tick while in the sim (Odin clears
     * it all on each world load). Odin's dungeon features (terminal solver, leap menu, Simon Says,
     * splits...) then work in the sim as they do on Hypixel.
     */
    private fun bridge() {
        if (!inSim) {
            if (bridged) { bridged = false; unbridge() }
            return
        }
        bridged = true
        // On in the sim, so its HUDs (practice) show: the module's switch does nothing else.
        if (!enabled) toggle()
        val me = mc.player?.name?.string ?: return
        setArea(Island.Dungeon)
        DungeonListener.floor = Floor.F7
        DungeonListener.inBoss = true
        if (DungeonListener.dungeonTeammates.size != 5 || DungeonListener.dungeonTeammates.none { it.name == me } || teamClass != classS.index || roster != Party.bots().joinToString { it.name }) {
            teamClass = classS.index
            roster = Party.bots().joinToString { it.name }
            val mine = myClass
            val team = arrayListOf(DungeonPlayer(me, mine, 50, mc.player?.skin))
            Party.bots().forEach { team += DungeonPlayer(it.name, it.clazz, 50, null) }
            val others = team.filter { it.name != me }
            DungeonListener.dungeonTeammates = team
            DungeonListener.dungeonTeammatesNoSelf = others
            // Odin's leap menu quadrants in the plan's leap slot order (slot 1 = top left ... 4 = bottom right).
            DungeonListener.leapTeammates = others
        }
    }

    private var teamClass = -1
    private var roster = ""

    private fun unbridge() {
        teamClass = -1
        DungeonListener.floor = null
        DungeonListener.inBoss = false
        DungeonListener.dungeonTeammates = arrayListOf()
        DungeonListener.dungeonTeammatesNoSelf = emptyList()
        DungeonListener.leapTeammates = emptyList()
        setArea(Island.Unknown)
    }

    // Resolved once: if Odin renames them, the bridge stays off instead of failing every tick.
    private val areaField = runCatching { LocationUtils::class.java.getDeclaredField("currentArea").apply { isAccessible = true } }.getOrNull()
    private val skyblockField = runCatching { LocationUtils::class.java.getDeclaredField("isInSkyblock").apply { isAccessible = true } }.getOrNull()

    private fun setArea(area: Island) {
        val areaField = areaField ?: return
        val skyblockField = skyblockField ?: return
        if (LocationUtils.currentArea != area) areaField.set(null, area)
        if (skyblockField.getBoolean(null) != (area == Island.Dungeon)) skyblockField.setBoolean(null, area == Island.Dungeon)
    }
}
