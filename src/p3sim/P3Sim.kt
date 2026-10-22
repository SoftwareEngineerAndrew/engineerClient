package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.settings.AdsSettingsRegistry
import com.engineerclient.ads.settings.SettingsPanel
import com.engineerclient.ads.settings.BooleanSetting
import com.engineerclient.ads.settings.KeybindSetting
import com.engineerclient.ads.settings.NumberSetting
import com.engineerclient.ads.settings.SelectorSetting
import com.engineerclient.ads.settings.Setting
import com.engineerclient.ads.settings.Settings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import com.engineerclient.ads.hud.AdsHud
import com.engineerclient.ads.hud.AdsHuds
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
 * ADS (Advanced Dungeon Simulator, once P3 Sim): F7's boss fight in a singleplayer world of its own ("p3sim"), to
 * practice it alone. The code, files and world keep the p3sim name (saved runs, plans and settings stay where they are).
 *
 * The world is the real arena at Hypixel's coordinates; the fight runs on the world's own
 * (integrated) server ([SimServer], [Fight]): Goldor, terminals, levers, devices, gates, death
 * ticks, and Maxor/Storm/Necron around it. The menu ([SimRestartScreen]: the keybind, `/ads menu`, `/p3sim`, or the
 * SkyBlock Menu star in the hotbar) starts any phase or section and teleports anywhere; `/ads` opens its settings.
 *
 * It needs no Odin: its settings are its own ([Settings], config/engineerclient/ads.json). With Odin installed
 * ([odin]), Odin's dungeon features also see the sim as F7 ([OdinSimBridge]).
 *
 * Nothing of it exists anywhere else: every piece checks [inSim] (the client) or
 * [SimServer.isSim] (the server), both of which are only true in that one singleplayer world.
 */
object P3Sim : Settings("ADS", "ads.json") {
    /** The short name, on buttons and in chat. */
    const val NAME = "ADS"
    /** The full name. */
    const val FULL_NAME = "Advanced Dungeon Simulator"

    /**
     * Odin is installed. The sim runs without it; with it, Odin's dungeon features join in ([OdinSimBridge], only
     * ever reached behind this).
     */
    @JvmStatic
    val odin: Boolean = FabricLoader.getInstance().isModLoaded("odin")

    /** Writes ADS's settings to disk (config/engineerclient/ads.json); the one place that knows where they live. */
    fun saveSettings() = save()

    val menuKey by KeybindSetting("Menu Keybind", InputConstants.UNKNOWN, "Opens the ADS menu (a big Restart) in the sim world, as /ads menu and the SkyBlock Menu star in your hotbar do; the full menu is in Esc. Outside it, opens the sim.").onPress { openMenuOrSim() }
    val restartKey by KeybindSetting("Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts whatever you last started again (P3, S2, P2...), from scratch; in practice, the practice.").onPress {
        if (inSim) SimActions.send("restart")
    }
    val practiceKey by KeybindSetting("Practice Restart Keybind", InputConstants.UNKNOWN, "In the sim: starts your practice (the menu's Practice tab) again. A left click with the Infinileap does too.").onPress {
        if (inSim) SimActions.send("practiceRestart")
    }
    enum class ClassOption { HEALER, BERSERK, ARCHER, TANK, MAGE }
    enum class DeathTickOption { OFF, WARN, MASKS }
    enum class TerminalOption { RANDOM, ORDER, PANES, RUBIX, STARTS_WITH, SELECT, MELODY }
    enum class MaskOption { SPIRIT, BONZO }

    // Kept as objects (not delegates) so the sim's own menu can change them.
    val classS = +SelectorSetting("Your Class", ClassOption.ARCHER, desc = "Your dungeon class (with Odin: its party list and leap menu). The four bots are the other classes. What you do in P3 is the menu's Plan tab.")
    val speedS = +NumberSetting("Speed", 600, 100..750, 10, desc = "Your Skyblock speed without Black Cat. Black Cat adds 100 (and 100 to the speed cap), so 450 is 550 with it out, as in recorded P3 starts (1.55 blocks a tick sprinting); Phoenix out has no Black Cat bonus.")
    val botsS = +BooleanSetting("Party Bots", true, desc = "The rest of the party's terminals, levers, devices and gates are always done at the plan's times; on, four bots show it (a late one is put on its job). Off: nobody's shown, the plan still runs.")
    val deathTicksS = +SelectorSetting("Death Ticks", DeathTickOption.MASKS, desc = "Goldor's death tick (every 60 ticks, hits anyone in a section ahead): Warn only says so; Masks uses your Spirit Mask, Bonzo's Mask and Phoenix as Hypixel does, and with none left you die (back to the section's start).")
    val terminalS = +SelectorSetting("Terminals", TerminalOption.RANDOM, desc = "Every terminal as this type, or random as on Hypixel.")
    val pingS = +NumberSetting("Simulated Ping", 0, 0..300, 10, unit = "ms", desc = "Delays the server's answer to your clicks and items by this much, like playing on Hypixel with that ping.")
    val jitterS = +BooleanSetting("Ping Jitter", true, desc = "Simulated Ping varies like a real connection: usually a few ms either way, now and then 30+ ms more, rarely a lag spike (spread recorded on Hypixel). Off: a fixed delay.")
    val goldorKillS = +NumberSetting("Goldor Kill Time", 20, 10..120, 1, unit = " ticks", desc = "How long after Goldor starts flying to the core his \"....\" line comes, i.e. he dies (recorded runs: median 43, range 17-74).")
    val shortbowCooldownS = +NumberSetting("Shortbow Cooldown", 2, 1..20, 1, unit = " ticks", desc = "Ticks between shots of the Terminator, Spirit Shortbow and Mosquito Shortbow: 5 at 100% attack speed, on every bow and whatever Terror armor you wear (recordings: Terminator 5, Mosquito 5). A click inside it fires when it ends. Nasty Bite has its own 10.")
    val hydraStartS = +NumberSetting("Hydra Stacks At Start", 10, 0..10, 1, desc = "Hydra Strike stacks every start from the menu (P1, P3, a section...) begins with. Going on from one phase to the next keeps what you have.")
    val clickLimitS = +BooleanSetting("Terminal Click Limit", true, desc = "As on Hypixel: a terminal takes at most 5 clicks in any 10 ticks; the rest are dropped without an answer.")
    val noMelodiesS = +BooleanSetting("No Melodies", true, desc = "Random terminals are never melodies.")
    val termLuckS = +NumberSetting("Terminal Luck", 0, -100..100, 5, unit = "%", desc = "0: terminals as Hypixel deals them. Higher: Click in Order's numbers closer together (a neat path at 100%), and fewer clicks in the others (more panes already on, fewer Rubix clicks, fewer items to pick); lower (bad luck): the other way. Toward 40% either way it picks Hypixel's easiest (or hardest) deals; past it, beyond anything Hypixel deals, down to 1 click at 100% and up to the worst at -100% (no pane on, a 10-click Rubix, 16 items to pick, 12 to select, the numbers all over). Melody: each row's purple where the green gets soonest (latest with bad luck). The terminal type: Melodies rarer (more common with bad luck), when they're on.")
    val ssLuckS = +NumberSetting("SS Luck", 0, -100..100, 5, unit = "%", desc = "0: Simon Says as Hypixel runs it. Higher: the 4 lamps closer together, steadily all the way up (4 neighbours in a line at 100%), and after a wrong press the quick restart (no stray lamp) more often, always at 100%. Lower (bad luck): the lamps spread across the board (corner to corner at -100%) and the slow restart, with a stray lamp.")
    val mageAllS = +BooleanSetting("Mage Beam On All Classes", false, desc = "Mage Beam (a left click with a sword or the Bonzo Staff) and Guided Sheep whatever your class. Ctrl+Q fires the sheep (as Archer it stays Explosive Shot: 3 quick beam clicks fire the sheep).")
    val firstClickMelodyS = +BooleanSetting("Always First Click Melody", false, desc = "Melody's first row always has its purple in the first slot, where the green starts: lock it at once. The other rows are as usual.")
    val recordS = +BooleanSetting("Record Runs", false, desc = "Writes each run, tick by tick (you, the bots, what's left, chat), to config/engineerclient/p3sim-runs, to look at what went wrong. After 50 runs in a row it turns itself off (nothing's deleted); turn it back on for 50 more.")
    val debugBotsS = +BooleanSetting("Debug Bots", false, desc = "Chat lines for everything the P3 bots do: where they head and why, jobs, leaps, early enters (on the spot, who they wait for, why they move on).")
    val breakerRefillS = +NumberSetting("Dungeonbreaker Refill", 3, 1..10, 1, unit = "/s", desc = "Charges back each second (20 max), in irregular +2 steps. Main server: ~6 a second; alpha ~2.")
    val breakerRegenS = +NumberSetting("Dungeonbreaker Regen", 11.0, 1.0..30.0, 0.5, unit = "s", desc = "How long a broken block stays broken (recordings: ~11 s; the 21st break brings back the oldest 41 ticks later).")
    val breakerInfiniteS = +BooleanSetting("Infinite Breaker Charges", false, desc = "The Dungeonbreaker never runs out: every break is free (20 charges always).")
    val breakerPermaS = +BooleanSetting("Perma Break", false, desc = "Blocks the Dungeonbreaker breaks stay broken (through restarts too) until this is turned off; then they all come back.")
    val realMasksS = +BooleanSetting("Real Masks", true, desc = "Masks are real helmets: only the one you wear can save you, swap them in /stats (cooldowns stay with each mask). Off: whichever is ready saves you.")
    val wornMaskS = +SelectorSetting("Starting Mask", MaskOption.SPIRIT, desc = "Real Masks: the mask you wear (/stats swaps it).")
    val phoenixS = +BooleanSetting("Phoenix Pet", false, desc = "Your pet: Phoenix (saves you from a death, no Black Cat speed bonus) or Black Cat (+100 speed). The Pet Rod swaps them.")
    val realMovesS = +BooleanSetting("Real Bot Movement", true, desc = "P3 bots move as you did: each plays your best run as its class for the skill (P3 from S1), timed so its jobs are done at the plan's times (faster where the plan's tighter). A class with no run of yours: along routes recorded in ADR runs. Off: the old straight walk and Hyperion blinks.")
    val terrorAtTermsS = +BooleanSetting("Terror At Terms", false, desc = "Every P3 start puts the Terror loadout on (Terror armour, Bonzo's Mask, Black Cat) over your saved gear.")
    val blackCatS = +BooleanSetting("Black Cat At Start", true, desc = "Every start and restart puts Black Cat out. Off: the pet you had stays (as your items and gear do).")
    val lavaS = +BooleanSetting("Lava Bounce", true, desc = "Lava bounces you up as on Hypixel. Off: plain vanilla lava (no damage).")
    val termsOnlyS = +BooleanSetting("Stop After Terms", false, desc = "The run stops (as Stop does) when the core opens: no Goldor fight.")
    val p3OnlyS = +BooleanSetting("Stop After Goldor", false, desc = "The run stops (as Stop does) when Necron would speak, after Goldor's death, instead of going on to Necron.")
    val autoStartS = +BooleanSetting("Start On Join", false, desc = "Start P3 as soon as you join the sim world.")
    val showTimesS = +BooleanSetting("Section Times", true, desc = "Each section's time in chat as it ends, and a summary at the core.")

    val practiceHudS = +BooleanSetting("Practice Splits HUD", true, desc = "In practice (the menu's Practice tab): the time, and each of your tasks with the time it was done.")
    val practiceTimeHudS = +BooleanSetting("Practice Time HUD", true, desc = "A practice's final time, large (as Term Info's Section Time), for 4 seconds.")
    val practiceStartHudS = +BooleanSetting("Practice Start Timer HUD", true, desc = "A practice's start timer counting down, large, until terminals and the rest are up.")

    /**
     * The practice HUDs, ADS HUDs ([AdsHud]: placed in the HUD editor, `/ads hud`), at the places and sizes Odin's HUD
     * editor once gave them. Each draws its lines (null: nothing) in the sim only; [example] is what the editor shows.
     */
    private fun practiceHud(id: String, name: String, on: BooleanSetting, x: Int, y: Int, scale: Float, example: List<String>, lines: () -> List<String>?) =
        AdsHuds.register(AdsHud(id, name, on.description, x, y, scale, on) { ex ->
            val shown = if (ex) example else if (inSim) lines() ?: emptyList() else emptyList()
            shown.forEachIndexed { i, l -> text(mc.font, l, 0, i * 10, -1, true) }
            (shown.maxOfOrNull { mc.font.width(it) } ?: 0) to shown.size * 10
        })

    private fun registerHuds() {
        // Practice: each task's time as you do it (like the Simon Says sim's).
        practiceHud("practice_splits", "Practice Splits", practiceHudS, 10, 210, 1.5f,
            listOf("§6Practice S1 §f12.35", "§7Lever §a3.10", "§7Terminal 1 §a6.85", "§7Terminal 2 §e...", "§7Device §8-")) {
            val mode = Practice.mode
            // Nothing during the start timer.
            if (mode == null || Practice.ticks < 0 || Practice.tasks.isEmpty()) return@practiceHud null
            val next = Practice.tasks.firstOrNull { it.at < 0 }
            listOf("§6Practice $mode ${if (Practice.endTicks >= 0) "§a" else "§f"}${Practice.secs(Practice.ticks)}") +
                Practice.tasks.map { t -> "§7${t.label} " + if (t.at >= 0) "§a${Practice.secs(t.at)}" else if (t === next) "§e..." else "§8-" }
        }
        // Practice: the final time, large, as Term Info's section times look.
        practiceHud("practice_time", "Practice Time", practiceTimeHudS, 420, 300, 5f, listOf("§512.35")) {
            Practice.endTicks.takeIf { Practice.active && it >= 0 && System.currentTimeMillis() - Practice.endMs < 4000 }?.let { listOf("§5${Practice.secs(it)}") }
        }
        // Practice: the start timer counting down, large.
        practiceHud("practice_start", "Practice Start Timer", practiceStartHudS, 440, 250, 4f, listOf("§e2.5")) {
            Practice.ticks.takeIf { Practice.active && it < 0 }?.let { listOf("§e${"%.1f".format(java.util.Locale.ROOT, -it / 20.0)}") }
        }
    }

    val autoStart: Boolean get() = autoStartS.value
    val showTimes: Boolean get() = mine("showTimes", { showTimesS.value }) { it.asBoolean }

    /**
     * A personal setting ([PartySettings.PERSONAL]) for the player it's about: on the sim's server, while a party's
     * joiner acts ([Sim.asPlayer]), theirs as their game sent it; else (the host, any game's own menu) this game's.
     */
    private inline fun <T> mine(key: String, own: () -> T, read: (com.google.gson.JsonElement) -> T): T =
        Players.joiner()?.vals?.get(key)?.let { runCatching { read(it) }.getOrNull() } ?: own()

    /** This game's own class (its menu's). */
    val ownClass: DungeonClass get() = Party.CLASSES[classS.index.coerceIn(0, 4)]
    /** The class of the player it's about: a party's joiner while they act, else this game's own. */
    val myClass: DungeonClass get() = Sim.acting?.takeIf { !Sim.isHost(it) }?.let { Players.classOf(it) } ?: ownClass
    val speed: Int get() = mine("speed", { speedS.value.toInt() }) { it.asDouble.toInt() }
    val bots: Boolean get() = botsS.value
    val deathTicks: Int get() = deathTicksS.index
    /** The simulated ping; a joiner's real round trip to the host already counts towards theirs. */
    val ping: Int get() {
        val v = mine("ping", { pingS.value.toInt() }) { it.asDouble.toInt() }
        val j = Sim.acting?.takeIf { Players.joiner() != null } ?: return v
        return (v - j.connection.latency()).coerceAtLeast(0)
    }
    val jitter: Boolean get() = mine("jitter", { jitterS.value }) { it.asBoolean }
    val goldorKill: Int get() = goldorKillS.value.toInt()
    val p3Only: Boolean get() = p3OnlyS.value
    val termsOnly: Boolean get() = (termsOnlyS as BooleanSetting?)?.value == true

    /** Stop After Terms and Stop After Goldor: at most one on (turning one on turns the other off). */
    fun toggleStopAfter(terms: Boolean) {
        val (it, other) = if (terms) (termsOnlyS as BooleanSetting?) to p3OnlyS else p3OnlyS to (termsOnlyS as BooleanSetting?)
        it ?: return
        it.value = !it.value
        if (it.value) other?.value = false
    }
    val shortbowCooldown: Int get() = mine("shortbow", { shortbowCooldownS.value.toInt() }) { it.asDouble.toInt() }
    /** Terror armor pieces worn right now (TERROR_* ids in the four armour slots, helmet included): 0 to 4. Hydra Strike follows it. */
    val terrorPieces: Int get() = Sim.player?.let { p ->
        listOf(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET)
            .count { SimItems.idOf(p.getItemBySlot(it))?.startsWith("TERROR_") == true }
    } ?: 0
    val hydraStart: Int get() = mine("hydra", { hydraStartS.value.toInt() }) { it.asDouble.toInt() }
    val lava: Boolean get() = lavaS.value
    val noMelodies: Boolean get() = noMelodiesS.value
    /** Terminal Luck, -1 to 1 (null-safe: a hotswapped field starts null). */
    /** SS Luck, -1 to 1. */
    val ssLuck: Double get() = ((ssLuckS as NumberSetting<*>?)?.value?.toDouble() ?: 0.0) / 100.0
    val termLuck: Double get() = ((termLuckS as NumberSetting<*>?)?.value?.toDouble() ?: 0.0) / 100.0
    val mageAll: Boolean get() = mine("mageAll", { (mageAllS as BooleanSetting?)?.value == true }) { it.asBoolean }
    val firstClickMelody: Boolean get() = (firstClickMelodyS as BooleanSetting?)?.value == true
    val clickLimit: Boolean get() = clickLimitS.value
    val debugBots: Boolean get() = debugBotsS.value
    val record: Boolean get() = recordS.value

    /** Hide Players is Odin's own (its module and its Hide All / Distance settings); in the sim its rule hides the bots too. Without Odin, off. */
    val hidePlayers: Boolean get() = odin && OdinSimBridge.hidePlayers
    fun toggleHidePlayers() { if (odin) OdinSimBridge.toggleHidePlayers() }

    /** A bot (a mannequin in the sim) Odin's Hide Players would hide if it were a player. Client thread. */
    @JvmStatic
    fun hideBot(e: net.minecraft.world.entity.Entity): Boolean {
        if (!odin || !inSim || e !is net.minecraft.world.entity.decoration.Mannequin) return false
        // The bot you watch (Spectate Route) in third person: never hidden (it's always within Distance of you).
        if (e === mc.cameraEntity) return false
        return OdinSimBridge.hidesBot(e)
    }
    val breakerRefill: Int get() = breakerRefillS.value.toInt()
    val breakerRegen: Double get() = breakerRegenS.value.toDouble()
    // Null-safe: a hotswapped game has new settings null until it is relaunched.
    val breakerInfinite: Boolean get() = mine("infinite", { (breakerInfiniteS as BooleanSetting?)?.value == true }) { it.asBoolean }
    val breakerPerma: Boolean get() = (breakerPermaS as BooleanSetting?)?.value == true
    fun toggleBreakerInfinite() { (breakerInfiniteS as BooleanSetting?)?.let { it.value = !it.value } }
    fun toggleBreakerPerma() { (breakerPermaS as BooleanSetting?)?.let { it.value = !it.value } }
    val realMasks: Boolean get() = mine("realMasks", { realMasksS.value }) { it.asBoolean }
    val phoenix: Boolean get() = mine("phoenix", { phoenixS.value }) { it.asBoolean }
    /** Real Masks' worn mask, 0 Spirit / 1 Bonzo, for the player it's about. */
    val wornMask: Int get() = mine("wornMask", { wornMaskS.index }) { it.asInt }

    /** Sets the pet of the player it's about (a joiner's: theirs, and their game's menu follows). */
    fun setPhoenix(on: Boolean) = setMine("phoenix", com.google.gson.JsonPrimitive(on)) { phoenixS.value = on }
    fun setWornMask(i: Int) = setMine("wornMask", com.google.gson.JsonPrimitive(i)) { wornMaskS.index = i }

    private fun setMine(key: String, v: com.google.gson.JsonElement, own: () -> Unit) {
        val p = Sim.acting?.takeIf { !Sim.isHost(it) }
        // The server changed it itself (a loadout, /stats, the Pet Rod): not a menu change to act on again.
        if (p == null) { own(); Sim.player?.let { PartyServer.notePersonal(it) }; return }
        Players.profile(p).vals[key] = v
        SimNet.send(p, "set", com.google.gson.JsonObject().also { it.add(key, v) }.toString())
        PartyServer.notePersonal(p)
    }
    // Null-safe: a hotswapped game has the setting null until it is relaunched (off till then).
    val realMoves: Boolean get() = (realMovesS as BooleanSetting?)?.value != false
    fun toggleRealMoves() { (realMovesS as BooleanSetting?)?.let { it.value = !it.value } }
    val terrorAtTerms: Boolean get() = mine("terror", { (terrorAtTermsS as BooleanSetting?)?.value == true }) { it.asBoolean }
    fun toggleTerrorAtTerms() { (terrorAtTermsS as BooleanSetting?)?.let { it.value = !it.value } }
    val blackCatAtStart: Boolean get() = mine("blackCat", { (blackCatS as BooleanSetting?)?.value != false }) { it.asBoolean }
    fun toggleBlackCatAtStart() { (blackCatS as BooleanSetting?)?.let { it.value = !it.value } }
    val forcedTerminal: Terminals.Type? get() = terminalS.index.let { if (it == 0) null else Terminals.Type.entries[it - 1] }

    /** True only in the p3sim singleplayer world, your own (client side). */
    @JvmStatic
    val localSim: Boolean
        get() {
            val s = SimServer.server ?: return false
            return mc.hasSingleplayerServer() && mc.singleplayerServer === s && mc.level != null
        }

    /** True in a P3 Sim: your own, or a party's you joined ([P3Party]) (client side). */
    @JvmStatic
    val inSim: Boolean
        get() = localSim || (P3Party.joined && mc.level != null)

    fun init() {
        if (!load()) {
            migrateFromOdin()
            save()
        }
        registerKeybinds()
        registerHuds()
        SimNet.register()
        PartyServer.register()
        PartyClient.register()
        SimServer.register()
        P3Plan.load()
        Practice.load()
        // /p3sim: the menu in the sim (or opens the sim); /p3sim <start> starts it; /p3sim rebuild remakes the world.
        // /stats: Hypixel's equipment window, here to swap masks. On the sim's own server only (a
        // server command, so Hypixel's /stats is never touched).
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(net.minecraft.commands.Commands.literal("stats")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Sim.asPlayer(p) { Masks.openStats(p) } }; 1 })
            // /loadouts: Hypixel's Loadouts window, same sim-server-only rule.
            dispatcher.register(net.minecraft.commands.Commands.literal("loadouts")
                .requires { it.server === SimServer.server }
                .executes { ctx -> ctx.source.player?.let { p -> Sim.asPlayer(p) { Loadouts.open(p) } }; 1 })
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /ads: ADS's settings; /ads menu: the sim's menu (or opens the sim), as /p3sim on its own does.
            for (name in listOf("ads", "p3sim")) {
                val cmd = ClientCommands.literal(name).executes { if (name == "ads") openSettings() else openMenuOrSim(); 1 }
                cmd.then(ClientCommands.literal("menu").executes { openMenuOrSim(); 1 })
                // /ads hud: the HUD editor, to place ADS's HUDs.
                cmd.then(ClientCommands.literal("hud").executes { com.engineerclient.ads.hud.AdsHudEditor.open(); 1 })
                for (s in Fight.Start.entries) cmd.then(ClientCommands.literal(s.name.lowercase()).executes {
                    if (inSim) SimActions.send("start", "what" to s.name) else SimWorld.open(); 1
                })
                cmd.then(ClientCommands.literal("stop").executes { SimActions.send("stop"); 1 })
                cmd.then(ClientCommands.literal("rebuild").executes { SimWorld.rebuild(); 1 })
                dispatcher.register(cmd)
            }
            // /pos: where you stand and look (and the block you look at), copied, to set spots from.
            dispatcher.register(ClientCommands.literal("pos").executes { pos(); 1 })
        }
        ClientTickEvents.START_CLIENT_TICK.register {
            // Each on its own: Odin drift breaking the bridge must not stop the party's hello and roster.
            if (odin) EngineerClient.safely("p3sim bridge") { OdinSimBridge.tick() }
            EngineerClient.safely("p3sim client items") { SimItems.clientTick() }
            EngineerClient.safely("p3sim party client") { PartyClient.tick() }
            EngineerClient.safely("p3sim ghost guard") { GhostGuard.clientTick() }
        }
        ScreenEvents.AFTER_INIT.register { _, screen, w, _ ->
            // The title screen: the Minecraft Realms button becomes Join Hypixel (Random Stuff's, so with Odin). The ADS
            // button above Singleplayer is the menu's (net.rustclear.mod.engine.Engine); P3 practice is in its Practice page.
            if (screen is TitleScreen && odin) EngineerClient.safely("ads title button") {
                val widgets = Screens.getWidgets(screen)
                fun key(b: Button) = (b.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key
                val buttons = widgets.filterIsInstance<Button>()
                val realms = buttons.firstOrNull { key(it) == "menu.online" } ?: return@safely
                widgets.remove(realms)
                widgets.add(Button.builder(Component.literal("Join Hypixel")) { OdinSimBridge.joinHypixel(screen) }
                    .bounds(realms.x, realms.y, realms.width, realms.height).build())
            }
            // In the sim, Esc's ADS Menu takes Open to LAN's place (right under Save and Quit if that
            // isn't there), and the whole menu shifts so the cursor, which opening it puts in the middle of
            // the screen, is already on it.
            if (screen is net.minecraft.client.gui.screens.PauseScreen && inSim) EngineerClient.safely("p3sim pause button") {
                val widgets = Screens.getWidgets(screen)
                val buttons = widgets.filterIsInstance<Button>()
                fun key(b: Button) = (b.message.contents as? net.minecraft.network.chat.contents.TranslatableContents)?.key
                val lan = buttons.firstOrNull { key(it) in LAN_KEYS }
                val quit = buttons.firstOrNull { key(it) in QUIT_KEYS } ?: buttons.maxByOrNull { it.y }
                val main = Button.builder(Component.literal("§6$NAME Menu")) { mc.gui.setScreen(SimRestartScreen()) }
                val b = when {
                    lan != null -> { widgets.remove(lan); main.bounds(lan.x, lan.y, lan.width, lan.height) }
                    quit != null -> main.bounds(quit.x, quit.y + quit.height + 4, quit.width, 20)
                    else -> main.bounds(4, 4, 110, 20)
                }.build()
                widgets.add(b)
                if (lan != null || quit != null) {
                    val dy = screen.height / 2 - (b.y + b.height / 2)
                    for (w in widgets) w.y += dy
                }
            }
        }
    }

    /** The Esc menu's Save and Quit button (Disconnect if it's shown that way). */
    private val QUIT_KEYS = setOf("menu.returnToMenu", "menu.disconnect")
    /** Open to LAN: vanilla's, or Clean Menus' multiplayer options in its place. */
    private val LAN_KEYS = setOf("menu.shareToLan", "menu.multiplayerOptions.button")

    /** Your position (y to the hundredth, as spots are set), yaw and pitch, and the block you look at: in chat and copied. */
    private fun pos() {
        val p = mc.player ?: return
        val f = { v: Double -> String.format(java.util.Locale.ROOT, "%.2f", v) }
        val y = Math.floor(p.y * 100 + 1e-6) / 100
        var line = "${f(p.x)}, ${f(y)}, ${f(p.z)}, yaw ${f(net.minecraft.util.Mth.wrapDegrees(p.yRot).toDouble())}, pitch ${f(p.xRot.toDouble())}"
        (mc.hitResult as? net.minecraft.world.phys.BlockHitResult)?.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }?.blockPos?.let { b ->
            line += ", looking at ${b.x}, ${b.y}, ${b.z}"
        }
        mc.keyboardHandler.clipboard = line
        EngineerClient.msg("§f$line §8(copied)")
    }

    fun openMenuOrSim() {
        if (inSim) mc.execute { mc.gui.setScreen(SimRestartScreen()) } else SimWorld.open()
    }

    /** `/ads`: every ADS setting, in panels as Odin's Click GUI lays a module's out (the sim's are the first). */
    fun openSettings() = AdsSettingsRegistry.open()

    init {
        AdsSettingsRegistry.register { groups().map { (title, settings) -> SettingsPanel(this, title, settings) } }
    }

    private fun groups(): List<Pair<String, List<Setting<*>>>> {
        val groups = listOf(
            "You" to listOf(classS, speedS, phoenixS, blackCatS, realMasksS, wornMaskS, terrorAtTermsS, mageAllS, hydraStartS, shortbowCooldownS),
            "Bots" to listOf(botsS, realMovesS),
            "Terminals & Devices" to listOf(terminalS, noMelodiesS, firstClickMelodyS, clickLimitS, termLuckS, ssLuckS),
            "Ping" to listOf(pingS, jitterS),
            "Goldor & Run" to listOf(deathTicksS, goldorKillS, lavaS, termsOnlyS, p3OnlyS, autoStartS, showTimesS),
            "Dungeonbreaker" to listOf(breakerRefillS, breakerRegenS, breakerInfiniteS, breakerPermaS),
            "Recording & Debug" to listOf(recordS, debugBotsS),
            "Keybinds" to all.filterIsInstance<KeybindSetting>(),
            "HUDs" to listOf(practiceHudS, practiceTimeHudS, practiceStartHudS),
        )
        // A setting added later without a panel here still shows, in the last one.
        val placed = groups.flatMap { it.second }.toSet()
        val rest = all.filter { it !in placed }
        return if (rest.isEmpty()) groups else groups.dropLast(1) + (groups.last().first to groups.last().second + rest)
    }

    /**
     * A first start with no ads.json: ADS was an Odin module, so its settings are in Odin's file for this mod
     * (config/odin/addons/engineerclient.json), under the module's name: ADS, or P3 Sim Beta / P3 Sim before that
     * (ConfigMigration renames those, but only once Odin's file is there). The values are written the same way;
     * the HUDs were Odin HUDs, of which only the on/off carries over.
     */
    private fun migrateFromOdin() {
        val file = mc.gameDirectory.toPath().resolve("config").resolve("odin").resolve("addons").resolve("engineerclient.json")
        try {
            if (!java.nio.file.Files.exists(file)) return
            val modules = JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonArray.mapNotNull { it.takeIf { m -> m.isJsonObject }?.asJsonObject }
            val module = listOf("ADS", "P3 Sim Beta", "P3 Sim").firstNotNullOfOrNull { n -> modules.firstOrNull { it["name"]?.asString == n } } ?: return
            val old = module.getAsJsonObject("settings") ?: return
            readFrom(old)
            for ((hud, setting) in listOf("Practice Splits" to practiceHudS, "Practice Time" to practiceTimeHudS, "Practice Start Timer" to practiceStartHudS)) {
                (old[hud] as? JsonObject)?.get("enabled")?.let { runCatching { setting.value = it.asBoolean } }
            }
            EngineerClient.logger.info("[ads] settings moved from Odin's config to ads.json")
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] reading the old settings from Odin's config failed, keeping defaults", t)
        }
    }
}
