package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
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
import org.lwjgl.glfw.GLFW

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
    category = Category.custom("Engineer Client"),
    description = "F7's boss in a singleplayer world of its own: /p3sim (or the title screen button) opens it. Only ever active in that world.",
) {
    val menuKey by KeybindSetting("Menu Keybind", GLFW.GLFW_KEY_UNKNOWN, "Opens the P3 Sim menu in the sim world (so do /p3sim and the SkyBlock Menu star in your hotbar). Outside it, opens the sim.").onPress { openMenuOrSim() }
    val restartKey by KeybindSetting("Restart Keybind", GLFW.GLFW_KEY_UNKNOWN, "In the sim: starts whatever you last started again (P3, S2, P2...), from scratch.").onPress {
        if (inSim) SimServer.run("restart") { Fight.start(Fight.lastStart) }
    }
    // Kept as objects (not delegates) so the sim's own menu can change them.
    val classS = +SelectorSetting("Your Class", "Berserk", arrayListOf("Healer", "Berserk", "Archer", "Tank", "Mage"), desc = "Your dungeon class (Odin's party list and leap menu). The four bots are the other classes. What you do in P3 is the menu's Plan tab.")
    val speedS = +NumberSetting("Speed", 500, 100, 750, 10, desc = "Your Skyblock speed in the sim. Most players run boss at 500 (the cap): 1.40 blocks a tick sprinting, as measured in Better PF runs.")
    val botsS = +BooleanSetting("Party Bots", true, desc = "Four bots do the rest of the party's terminals, levers, devices and gates at the pace of fast Better PF runs. Off: you do everything.")
    val deathTicksS = +SelectorSetting("Death Ticks", "Masks", arrayListOf("Off", "Warn", "Masks"), desc = "Goldor's death tick (every 60 ticks, hits anyone in a section ahead): Warn only says so; Masks uses your Spirit Mask, Bonzo's Mask and Phoenix as Hypixel does, and with none left you die (back to the section's start).")
    val terminalS = +SelectorSetting("Terminals", "Random", arrayListOf("Random", "Order", "Panes", "Rubix", "Starts With", "Select", "Melody"), desc = "Every terminal as this type, or random as on Hypixel.")
    val pingS = +NumberSetting("Simulated Ping", 0, 0, 300, 10, unit = "ms", desc = "Delays the server's answer to your clicks and items by this much, like playing on Hypixel with that ping.")
    val goldorKillS = +NumberSetting("Goldor Kill Time", 57, 10, 120, 1, unit = " ticks", desc = "How long after Goldor leaves for the core he dies (median of 201 recorded kills: 57).")
    val shortbowCooldownS = +NumberSetting("Shortbow Cooldown", 5, 1, 20, 1, unit = " ticks", desc = "Ticks between shots of the Terminator, Spirit Shortbow and Mosquito Shortbow: 5 at full attack speed (recordings: Terminator 5, Mosquito 5 with Terror on, 7 without). A click inside it fires when it ends. Nasty Bite has its own 10.")
    val terrorS = +SelectorSetting("Terror Armor", "3 Pieces", arrayListOf("Off", "3 Pieces", "4 Pieces"), desc = "Hydra Strike: +1 stack per boss hit (every 0.2 s at most), one lost every 8 s (3 pieces, a mask on your head) or 11 s (4 pieces) without a hit; +1% arrow speed a stack and, at 10, two more arrows at ±8°. Off: no stacks.")
    val hydraStartS = +NumberSetting("Hydra Stacks At Start", 10, 0, 10, 1, desc = "Hydra Strike stacks every start from the menu (P1, P3, a section...) begins with. Going on from one phase to the next keeps what you have.")
    val noMelodiesS = +BooleanSetting("No Melodies", false, desc = "Random terminals are never melodies.")
    val recordS = +BooleanSetting("Record Runs", true, desc = "Writes each run, tick by tick (you, the bots, what's left, chat), to config/engineerclient/p3sim-runs (last 20 kept), to look at what went wrong.")
    val debugBotsS = +BooleanSetting("Debug Bots", false, desc = "Chat lines for everything the P3 bots do: where they head and why, jobs, leaps, early enters (on the spot, who they wait for, why they move on).")
    val breakerRefillS = +NumberSetting("Dungeonbreaker Refill", 3, 1, 10, 1, unit = "/s", desc = "Charges back each second (20 max). Recordings: ~3 a second; the wiki says 2.")
    val breakerRegenS = +NumberSetting("Dungeonbreaker Regen", 11.0, 1.0, 30.0, 0.5, unit = "s", desc = "How long a broken block stays broken (recordings: ~11 s; the 21st break brings back the oldest at once).")
    val realMasksS = +BooleanSetting("Real Masks", false, desc = "Masks are real helmets: only the one you wear can save you, swap them in /stats (cooldowns stay with each mask). Off: whichever is ready saves you.")
    val wornMaskS = +SelectorSetting("Starting Mask", "Spirit", arrayListOf("Spirit", "Bonzo"), desc = "Real Masks: the mask you wear (/stats swaps it).")
    val phoenixS = +BooleanSetting("Phoenix Pet", true, desc = "Your pet: Phoenix (saves you from a death, 100 less speed) or Black Cat (your full speed). The Pet Rod swaps them.")
    val lavaS = +BooleanSetting("Lava Bounce", true, desc = "Lava bounces you up as on Hypixel. Off: plain vanilla lava (no damage).")
    val p3OnlyS = +BooleanSetting("Stop After P3", true, desc = "End at Goldor's death instead of going on to Necron.")
    val autoStartS = +BooleanSetting("Start On Join", false, desc = "Start P3 as soon as you join the sim world.")
    val showTimesS = +BooleanSetting("Section Times", true, desc = "Each section's time in chat as it ends, and a summary at the core.")

    val autoStart: Boolean get() = autoStartS.value
    val showTimes: Boolean get() = showTimesS.value
    val myClass: DungeonClass get() = Party.CLASSES[classS.value.coerceIn(0, 4)]
    val speed: Int get() = speedS.value.toInt()
    val bots: Boolean get() = botsS.value
    val deathTicks: Int get() = deathTicksS.value
    val ping: Int get() = pingS.value.toInt()
    val goldorKill: Int get() = goldorKillS.value.toInt()
    val p3Only: Boolean get() = p3OnlyS.value
    val shortbowCooldown: Int get() = shortbowCooldownS.value.toInt()
    /** Terror armor pieces worn: 0 (off), 3 or 4. */
    val terrorPieces: Int get() = when (terrorS.value) { 1 -> 3; 2 -> 4; else -> 0 }
    val hydraStart: Int get() = hydraStartS.value.toInt()
    val lava: Boolean get() = lavaS.value
    val noMelodies: Boolean get() = noMelodiesS.value
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
    val forcedTerminal: Terminals.Type? get() = terminalS.value.let { if (it == 0) null else Terminals.Type.entries[it - 1] }

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
        ClientTickEvents.START_CLIENT_TICK.register { EngineerClient.safely("p3sim bridge") { bridge() } }
        ScreenEvents.AFTER_INIT.register { _, screen, w, _ ->
            if (screen is TitleScreen) EngineerClient.safely("p3sim title button") {
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("P3 Sim")) { SimWorld.open() }.bounds(w - 64, 4, 60, 16).build()
                )
            }
            // In the sim, Esc has the menu too: right under Save and Quit (the bottom button if that isn't found).
            if (screen is net.minecraft.client.gui.screens.PauseScreen && inSim) EngineerClient.safely("p3sim pause button") {
                val widgets = Screens.getWidgets(screen)
                val buttons = widgets.filterIsInstance<Button>()
                val quit = buttons.firstOrNull { (it.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key in QUIT_KEYS }
                    ?: buttons.maxByOrNull { it.y }
                val b = if (quit != null) Button.builder(Component.literal("§6P3 Sim Menu")) { mc.setScreen(SimScreen()) }.bounds(quit.x, quit.y + quit.height + 4, quit.width, 20)
                    else Button.builder(Component.literal("§6P3 Sim Menu")) { mc.setScreen(SimScreen()) }.bounds(4, 4, 90, 20)
                widgets.add(b.build())
            }
        }
    }

    /** The Esc menu's Save and Quit button (Disconnect if it's shown that way). */
    private val QUIT_KEYS = setOf("menu.returnToMenu", "menu.disconnect")

    fun openMenuOrSim() {
        if (inSim) mc.execute { mc.setScreen(SimScreen()) } else SimWorld.open()
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
        val me = mc.player?.name?.string ?: return
        setArea(Island.Dungeon)
        DungeonListener.floor = Floor.F7
        DungeonListener.inBoss = true
        if (DungeonListener.dungeonTeammates.size != 5 || DungeonListener.dungeonTeammates.none { it.name == me } || teamClass != classS.value || roster != Party.bots().joinToString { it.name }) {
            teamClass = classS.value
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
