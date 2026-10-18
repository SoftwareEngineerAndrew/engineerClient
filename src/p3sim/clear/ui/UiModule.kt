package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.run.AdsClientContext
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsModule
import com.engineerclient.p3sim.run.AdsServerContext
import com.engineerclient.p3sim.run.RunStage

/**
 * The player-facing way to start and control a run: the start screen and layout browser ([RunStartScreen], [LayoutBrowserScreen]),
 * `/ads run [random|seed <n>|layout <id>]`, `/ads run restart [room]`, savepoint controls, the run settings ([UiSettings]), and the
 * chat message when the data packs are missing ([RunStarter]).
 * FILLED BY: the `savepoints` work item (owns `src/p3sim/clear/ui/` and below).
 *
 * The module has no per-tick work and no state of its own: starting is a client matter (the world is rebuilt for a new dungeon),
 * and restarting is the engine's savepoint store, which these commands only choose from ([Restarts]).
 */
object UiModule : AdsModule {
    override val id = "ui"
    override val owner = "savepoints"

    override fun registerSettings(host: com.odtheking.odin.features.Module) = UiSettings.register(host)

    /** Opens the start screen (client thread; safe to call from a command or a key). */
    fun openStartScreen() {
        mc.execute { mc.gui.setScreen(RunStartScreen()) }
    }

    override fun runCommands(): List<AdsCommand> = listOf(
        AdsCommand("start", "/ads run start", "Opens the run start screen: layout, party, bots, lag, no deaths.", requiresSim = false,
            client = { openStartScreen() }),
        AdsCommand("random", "/ads run random [options]", "Starts a full run on a random recorded layout. Options: ${RunArgs.OPTIONS}.", requiresSim = false,
            client = { start("random", it) }),
        AdsCommand("seed", "/ads run seed <n> [options]", "Starts a full run on the layout that seed picks; the same seed gives the same run.", requiresSim = false,
            client = { start("seed", it) }),
        AdsCommand("layout", "/ads run layout <id> [options]", "Starts a full run on a recorded layout by its id (a start of the id is enough).", requiresSim = false,
            client = { start("layout", it) }),
        AdsCommand("restart", "/ads run restart [room]", "Goes back to the run's start, or with \"room\" to when you entered the room you are in. Same dungeon, no rebuild.",
            server = { restart(it) }),
        AdsCommand("savepoint", "/ads run savepoint", "Takes a savepoint now.", server = { savepoint(it) }),
        AdsCommand("savepoints", "/ads run savepoints", "Lists the run's savepoints, oldest first.", server = { list(it) }),
        AdsCommand("restore", "/ads run restore <number>", "Goes back to a savepoint from /ads run savepoints.", server = { restore(it) }),
    )

    // ------------------------------------------------------------------ client

    private fun start(mode: String, ctx: AdsClientContext) {
        val d = UiSettings.defaults()
        when (val p = RunArgs.parse(mode, ctx.args, d) { drawSeed() }) {
            is RunArgs.Parsed.Bad -> ctx.reply(p.message)
            is RunArgs.Parsed.Ok -> mc.execute { RunStarter.start(p.config, ctx.reply) }
        }
    }

    /** A fresh run seed, positive and short enough to read out. */
    fun drawSeed(): Long = (System.nanoTime() xor System.currentTimeMillis()).let { Math.floorMod(it, 1_000_000_000L) }

    // ------------------------------------------------------------------ server (the sim server thread, as the player)

    private fun restart(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply(NO_RUN)
        val all = run.savepoints.list()
        val choice = if (ctx.arg(0)?.lowercase() == "room") {
            val here = run.state.roomAt(ctx.player.x, ctx.player.z)?.index
            Restarts.room(all, here)
        } else if (ctx.arg(0) == null) Restarts.start(all)
        else return ctx.reply("Use /ads run restart for the start, or /ads run restart room for this room.")
        goTo(ctx, choice)
    }

    private fun restore(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply(NO_RUN)
        val word = ctx.arg(0) ?: return ctx.reply("Give the savepoint's number from /ads run savepoints.")
        val sp = Restarts.find(run.savepoints.list(), word) ?: return ctx.reply("There is no savepoint $word. /ads run savepoints lists them.")
        goTo(ctx, Restarts.Choice.Use(sp))
    }

    private fun goTo(ctx: AdsServerContext, choice: Restarts.Choice) {
        val run = ctx.run ?: return
        when (choice) {
            is Restarts.Choice.None -> ctx.reply(choice.message)
            is Restarts.Choice.Use -> {
                choice.note?.let(ctx::reply)
                if (run.stage == RunStage.END || run.isOver) return ctx.reply("The run is over. Start another with /ads run start.")
                if (!run.stage.inDungeon) return ctx.reply("A restart is only possible before the boss. Start another run with /ads run start.")
                if (run.savepoints.restore(run, choice.savepoint.id)) ctx.reply("Back to ${choice.savepoint.label} (${Restarts.clock(choice.savepoint.t)}).")
                else ctx.reply("That savepoint could not be restored.")
            }
        }
    }

    private fun savepoint(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply(NO_RUN)
        val room = run.state.roomAt(ctx.player.x, ctx.player.z)
        val label = if (room != null) "Manual: ${room.plan.name}" else "Manual"
        val sp = run.savepoint(label, null)
        if (sp == null) ctx.reply("Saving is not available in this run.")
        else ctx.reply("Savepoint ${run.savepoints.list().indexOfFirst { it.id == sp.id } + 1} taken at ${Restarts.clock(sp.t)}.")
    }

    private fun list(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply(NO_RUN)
        val lines = Restarts.lines(run.savepoints.list())
        if (lines.isEmpty()) ctx.reply("There are no savepoints yet.")
        else { ctx.reply("Savepoints (restore with /ads run restore <number>):"); lines.takeLast(MAX_LISTED).forEach(ctx::reply) }
    }

    private const val NO_RUN = "No full run is on. Start one with /ads run start."
    private const val MAX_LISTED = 25

}
