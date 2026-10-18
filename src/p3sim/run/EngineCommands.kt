package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.SimServer
import com.engineerclient.p3sim.SimWorld
import net.minecraft.world.Difficulty
import net.minecraft.world.level.LightLayer

/**
 * The engine's `/ads dev` commands: the three spikes (`difficulty`, `cost`, `lag`) and the tools for driving a run by hand while the other
 * modules are still being built (`launch`, `stubrun`, `stage`, `boss`, `savepoint`, `restore`, `runinfo`), and `worldenv`, which says what the world
 * looks like from both sides (the dimension, the sky light, the chunk window, what the Hypixel mod API was told). In-game steps and expected
 * results are in the engine work item's report.
 */
internal object EngineCommands {
    fun dev(): List<AdsCommand> = listOf(
        AdsCommand("difficulty", "/ads dev difficulty [normal|easy|hard|peaceful]",
            "Puts the world on NORMAL (or the one you name) and watches the boss sim for 20 s: does anything vanish, target you or hurt you? Start a boss phase first.",
            server = { ctx ->
                val server = SimServer.server
                val to = when (ctx.arg(0)?.lowercase()) { null, "normal" -> Difficulty.NORMAL; "easy" -> Difficulty.EASY; "hard" -> Difficulty.HARD; "peaceful" -> Difficulty.PEACEFUL; else -> null }
                if (server == null || to == null) ctx.reply("Say normal, easy, hard or peaceful.") else EngineSpikes.difficulty(server, to)
            }),
        AdsCommand("cost", "/ads dev cost [base] [own] [quick]",
            "Rebuilds the world with a made-up dungeon (169 roofed chunks) beside the arena and measures: time to playable, server ms per tick with 0, 300 and 750 static mobs at the pinned distance (6), client FPS with 430 entities, Arena.resync. 'base' builds the plain arena for the comparison, 'own' keeps your own view distance, 'quick' shortens the windows.",
            requiresSim = false, client = { ctx ->
                val base = "base" in ctx.args
                EngineSpikes.beginWorldClock(); EngineSpikes.expectPlayable()
                EngineSpikes.costOnJoin = EngineSpikes.CostOptions(pin = "own" !in ctx.args, quick = "quick" in ctx.args)
                if (base) { WorldRegions.unregister(StubDungeon.NAME); WorldRegions.unregister(ArenaRegion.NAME) }
                else RunLauncher.registerDungeon(StubDungeon.region())
                ctx.reply(if (base) "Rebuilding the plain arena world and measuring. Stand still when it opens." else "Rebuilding the world with the made-up dungeon and measuring. Stand still when it opens.")
                if (P3Sim.inSim) SimWorld.rebuild(forRun = true) else SimWorld.open(forRun = true)
            }),
        AdsCommand("costnow", "/ads dev costnow [own] [quick]", "The cost measurements in the world you are in now (no rebuild). Stand in a room.",
            server = { ctx -> EngineSpikes.costNow(EngineSpikes.CostOptions(pin = "own" !in ctx.args, quick = "quick" in ctx.args)) }),
        AdsCommand("lag", "/ads dev lag <off|good|typical|bad|slot|report> [seconds]",
            "Turns the lag layer on for this session and, after the seconds (default 60), says what the server's ticks looked like: freezes, the bursts after them, the loss a minute.",
            server = { ctx ->
                val word = ctx.arg(0)?.lowercase()
                val secs = ctx.arg(1)?.toIntOrNull()?.coerceIn(5, 3600)
                val preset = when (word) { "off" -> LagPreset.OFF; "good" -> LagPreset.GOOD; "typical" -> LagPreset.TYPICAL; "bad" -> LagPreset.BAD; "slot", "high" -> LagPreset.HIGH_SLOT; else -> null }
                when {
                    word == "report" -> EngineSpikes.lagReport(secs ?: 60)
                    preset != null -> EngineSpikes.lag(preset, secs ?: 60)
                    else -> ctx.reply("Say off, good, typical, bad, slot or report.")
                }
            }),
        AdsCommand("launch", "/ads dev launch [random|seed <n>|layout <id>] [lag good|typical|bad|slot] [solo] [nodeaths] [strategy auto|neutral|<id>]",
            "Starts a full run from the data packs (rebuilds the world). The same call the run screen makes.",
            requiresSim = false, client = { ctx ->
                var layout: LayoutSource = LayoutSource.Random
                var lag = LagPreset.OFF; var solo = false; var noDeaths = false; var strategy: String? = null
                var i = 0
                while (i < ctx.args.size) {
                    when (ctx.args[i].lowercase()) {
                        "random" -> layout = LayoutSource.Random
                        "seed" -> { ctx.args.getOrNull(++i)?.toLongOrNull()?.let { layout = LayoutSource.Seed(it) } ?: run { ctx.reply("seed needs a number."); return@AdsCommand } }
                        "layout" -> { ctx.args.getOrNull(++i)?.let { layout = LayoutSource.Recorded(it) } ?: run { ctx.reply("layout needs an id."); return@AdsCommand } }
                        "lag" -> lag = when (ctx.args.getOrNull(++i)?.lowercase()) { "good" -> LagPreset.GOOD; "typical" -> LagPreset.TYPICAL; "bad" -> LagPreset.BAD; "slot" -> LagPreset.HIGH_SLOT; else -> LagPreset.OFF }
                        "solo" -> solo = true
                        "nodeaths" -> noDeaths = true
                        "strategy" -> {
                            // The same words the start screen and `/ads run` take: auto, neutral or a preset a plug-in offers.
                            val offered = com.engineerclient.p3sim.clear.bots.BotStrategies.available()
                            val choice = com.engineerclient.p3sim.clear.ui.StrategyChoice.normalize(ctx.args.getOrNull(++i), offered)
                                ?: run { ctx.reply("There is no party strategy called '${ctx.args.getOrNull(i) ?: ""}'. Choose from: ${com.engineerclient.p3sim.clear.ui.StrategyChoice.words(offered)}."); return@AdsCommand }
                            strategy = com.engineerclient.p3sim.clear.ui.StrategyChoice.resolve(choice, offered)
                        }
                        else -> { ctx.reply("I do not know '${ctx.args[i]}'."); return@AdsCommand }
                    }
                    i++
                }
                RunLauncher.launch(RunConfig(layout = layout, lag = lag, bots = !solo, partySize = if (solo) 1 else 5, noDeaths = noDeaths, strategy = strategy), ctx.reply)
            }),
        AdsCommand("stubrun", "/ads dev stubrun [lag good|typical|bad|slot]",
            "Starts a run in a made-up dungeon (no data packs needed) to try the run container, stages and savepoints by hand.",
            requiresSim = false, client = { ctx ->
                val lag = if (ctx.arg(0)?.lowercase() == "lag") when (ctx.arg(1)?.lowercase()) { "good" -> LagPreset.GOOD; "typical" -> LagPreset.TYPICAL; "bad" -> LagPreset.BAD; "slot" -> LagPreset.HIGH_SLOT; else -> LagPreset.OFF } else LagPreset.OFF
                val config = RunConfig(lag = lag, bots = false, partySize = 1)
                RunLauncher.registerDungeon(StubDungeon.region())
                RunLauncher.setPending(config, StubDungeon.plan(config.seed))
                ctx.reply("Building the made-up dungeon...")
                RunLauncher.launchWorld()
            }),
        AdsCommand("stage", "/ads dev stage <clear|blood|entry|boss|end>", "Moves the run to the next stage by hand (stages go forward one at a time; end from anywhere).",
            server = { ctx ->
                val run = ctx.run
                val to = when (ctx.arg(0)?.lowercase()) { "clear" -> RunStage.CLEAR; "blood" -> RunStage.BLOOD; "entry" -> RunStage.BOSS_ENTRY; "boss" -> RunStage.BOSS; "end" -> RunStage.END; else -> null }
                when {
                    run == null -> ctx.reply("No run is on.")
                    to == null -> ctx.reply("Say clear, blood, entry, boss or end.")
                    to == RunStage.BOSS -> { run.beginBoss(); ctx.reply("Stage is now ${run.stage}.") }
                    else -> ctx.reply(if (run.requestStage(to)) "Stage is now ${run.stage}." else "Not allowed from ${run.stage}.")
                }
            }),
        AdsCommand("boss", "/ads dev boss", "Hands the run to the boss sim now (walks the stages forward first).",
            server = { ctx -> val run = ctx.run; if (run == null) ctx.reply("No run is on.") else { run.beginBoss(); ctx.reply("Stage is now ${run.stage}.") } }),
        AdsCommand("savepoint", "/ads dev savepoint [label]", "Takes a savepoint now.",
            server = { ctx ->
                val run = ctx.run
                val sp = run?.savepoint(ctx.args.joinToString(" ").ifBlank { "Manual" })
                ctx.reply(if (sp == null) "No savepoint (no run, or not in the dungeon)." else "Savepoint ${sp.id} \"${sp.label}\" at t=${sp.t}.")
            }),
        AdsCommand("savepoints", "/ads dev savepoints", "Lists the run's savepoints.",
            server = { ctx ->
                val run = ctx.run
                if (run == null) ctx.reply("No run is on.") else run.savepoints.list().takeLast(15).forEach { ctx.reply("${it.id}  t=${it.t}  ${it.stage}  ${it.label}") }
            }),
        AdsCommand("restore", "/ads dev restore [id]", "Restores a savepoint (the last one with no id): stage, clock, state, blocks, mobs and your gear and place.",
            server = { ctx ->
                val run = ctx.run
                if (run == null) { ctx.reply("No run is on."); return@AdsCommand }
                val id = ctx.arg(0) ?: run.savepoints.list().lastOrNull()?.id
                ctx.reply(if (id != null && run.savepoints.restore(run, id)) "Restored $id." else "Could not restore ${id ?: "anything"}.")
            }),
        AdsCommand("runinfo", "/ads dev runinfo", "Says where the run is: stage, clock, party, lag, view distance pin, world regions.",
            server = { ctx ->
                val run = ctx.run
                ctx.reply(if (run == null) "No run is on (the boss practice)." else "Run ${run.id}: ${run.stage}, t=${run.t}, tick ${run.serverTick}, ${run.state.players.size} in the party, layout ${run.plan.layoutId}.")
                ctx.reply("Lag: ${LagRuntime.effective.label}. View pin: ${ViewPin.saved?.let { "on (yours were ${it.first}/${it.second})" } ?: "off"}. Regions: ${WorldRegions.regions.joinToString { it.name }.ifBlank { "none" }}.")
            }),
        AdsCommand("worldenv", "/ads dev worldenv", "Says what the world is: its height and sky light, the biome, the chunk window, the pinned distances, the keep-alives, and what the Hypixel mod API was told.",
            client = { ctx ->
                val level = mc.level
                val player = mc.player
                if (level == null || player == null) { ctx.reply("Not in a world."); return@AdsCommand }
                val dim = level.dimensionType()
                ctx.reply("Client: dimension y ${dim.minY()}..${dim.minY() + dim.height() - 1} (height ${dim.height()}), sky light ${if (dim.hasSkyLight()) "ON" else "off"}, ${level.chunkSource.loadedChunksCount} chunks held.")
                ctx.reply("Client: biome ${level.getBiome(player.blockPosition()).unwrapKey().map { it.identifier().toString() }.orElse("?")}, server brand \"${mc.connection?.serverBrand() ?: "?"}\".")
                ctx.reply("Client: ${HypixelModApi.status()}.")
            },
            server = { ctx ->
                val server = SimServer.server ?: return@AdsCommand
                val level = SimServer.level ?: return@AdsCommand
                val pos = ctx.player.blockPosition()
                ctx.reply("Server: view distance ${server.playerList.viewDistance}, simulation distance ${server.playerList.simulationDistance}, ${level.chunkSource.loadedChunksCount} chunks loaded, ${EngineModule.heldChunkCount} held for the arena, clock ${if (server.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)) "running" else "frozen"}.")
                ctx.reply("Server: light at your feet sky ${level.lightEngine.getLayerListener(LightLayer.SKY).getLightValue(pos)}, block ${level.lightEngine.getLayerListener(LightLayer.BLOCK).getLightValue(pos)}. ${HypixelModApi.serverStatus()}.")
            }),
    )
}
