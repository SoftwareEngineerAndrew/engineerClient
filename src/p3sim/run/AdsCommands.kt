package com.engineerclient.p3sim.run

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.SimNet
import com.engineerclient.p3sim.pack.Packs
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * What a command sees on the client thread. [reply] writes a line to the player's chat. [args] are the words after the command name.
 */
class AdsClientContext(val args: List<String>, val reply: (String) -> Unit) {
    fun arg(i: Int): String? = args.getOrNull(i)
}

/** What a command sees on the sim server's thread, run as the player who typed it. [reply] goes to that player. */
class AdsServerContext(val player: ServerPlayer, val args: List<String>) {
    fun arg(i: Int): String? = args.getOrNull(i)

    /** The current run, or null (no run is on: today's boss practice, or nothing started). */
    val run: Run? get() = Runs.current

    fun reply(text: String) { Sim.note(text) }
}

/**
 * One `/ads dev <name>` or `/ads run <name>` command. Modules return these from `devCommands()` / `runCommands()`; the
 * registry attaches them to `/ads` -- no shared file is edited to add one.
 *
 * A command may have a [client] part (runs on the client thread at once: probes of Odin, screens, reading the packs folder),
 * a [server] part (runs on the sim server thread: the world, the run), or both. If it has a server part the arguments travel
 * to the server over `SimNet` (kind `adscmd`) and run there as the player; the player must be in the ADS world, otherwise
 * the command answers "Open ADS first" (unless [requiresSim] is false, which is only for commands that read files).
 * Names are lower-case, one word. Two commands with the same name in one group: the first wins, the rest are logged.
 */
class AdsCommand(
    val name: String,
    /** One line: `/ads dev rooms`, with the arguments in angle brackets. */
    val usage: String,
    /** One plain sentence for `/ads dev` (the list). */
    val summary: String,
    val requiresSim: Boolean = true,
    val client: ((AdsClientContext) -> Unit)? = null,
    val server: ((AdsServerContext) -> Unit)? = null,
)

/**
 * The command registry. `/ads dev` lists the dev commands, `/ads dev <name> [args...]` runs one; `/ads run` and
 * `/ads run <name> [args...]` do the same for run commands. [attach] is called once from `P3Sim.init()` for each of
 * `/ads` and `/p3sim`; [onInit] registers the server-side receiver.
 */
object AdsCommands {
    private val log = org.slf4j.LoggerFactory.getLogger("engineerclient")
    private const val NET_KIND = "adscmd"

    /** Commands the skeleton itself provides (usable before any module is filled in). */
    private val builtinDev = listOf(
        AdsCommand("packs", "/ads dev packs", "Reloads the ADS data packs and says what loaded and what is missing.", requiresSim = false, client = { ctx ->
            Packs.loadAsync().thenAccept { set ->
                net.minecraft.client.Minecraft.getInstance().execute {
                    val lines = ArrayList<String>()
                    lines += "Data packs in ${set.dir}:"
                    lines += "  rooms: " + (set.rooms?.let { "${it.designs.size} designs, ${it.cores.size} cores" } ?: "not loaded")
                    lines += "  layouts: " + (set.layouts?.let { "${it.layouts.size} layouts, ${it.pool().size} in the default pool" } ?: "not loaded")
                    lines += "  mobs: " + (set.mobs?.let { "${it.archetypes.size} archetypes, ${it.rooms.size} rooms" } ?: "not loaded")
                    lines += "  run-state: " + (set.runstate?.let { "${it.rooms.size} rooms" + if (it.problems.isEmpty()) "" else ", ${it.problems.size} section problems" } ?: "not loaded")
                    lines += set.userLines()
                    lines.forEach(ctx.reply)
                }
            }
        }),
        AdsCommand("events", "/ads dev events", "Prints the last events the run bus delivered.", client = null, server = { ctx ->
            val run = ctx.run
            if (run == null) ctx.reply("No run is on.") else for (e in run.bus.recentEvents().takeLast(15)) ctx.reply(e.toString())
        }),
    )

    private fun collect(label: String, list: List<AdsCommand>): List<AdsCommand> {
        val seen = LinkedHashMap<String, AdsCommand>()
        for (c in list) {
            if (seen.putIfAbsent(c.name, c) != null) log.warn("[ads] two /ads $label commands are named '${c.name}'; the first wins")
        }
        return seen.values.toList()
    }

    /** All `/ads dev` commands: the skeleton's, then each module's in module order. */
    val dev: List<AdsCommand> by lazy { collect("dev", builtinDev + AdsModules.all.flatMap { m -> safeList("${m.id}.devCommands") { m.devCommands() } }) }

    /** All `/ads run` commands. */
    val run: List<AdsCommand> by lazy { collect("run", AdsModules.all.flatMap { m -> safeList("${m.id}.runCommands") { m.runCommands() } }) }

    private fun safeList(what: String, f: () -> List<AdsCommand>): List<AdsCommand> =
        try { f() } catch (t: Throwable) { log.error("[ads] $what failed", t); emptyList() }

    /** Adds the `dev` and `run` groups under an `/ads` (or `/p3sim`) builder. Called from `P3Sim.init()`. */
    fun attach(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(group("dev", dev)).then(group("run", run))
    }

    private fun group(label: String, cmds: List<AdsCommand>): LiteralArgumentBuilder<FabricClientCommandSource> {
        val g = ClientCommands.literal(label).executes { c -> list(label, cmds) { c.source.sendFeedback(Component.literal(it)) }; 1 }
        for (cmd in cmds) {
            g.then(ClientCommands.literal(cmd.name)
                .executes { c -> exec(label, cmd, emptyList()) { c.source.sendFeedback(Component.literal(it)) }; 1 }
                .then(ClientCommands.argument("args", StringArgumentType.greedyString()).executes { c ->
                    exec(label, cmd, StringArgumentType.getString(c, "args").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) { c.source.sendFeedback(Component.literal(it)) }
                    1
                }))
        }
        return g
    }

    private fun list(label: String, cmds: List<AdsCommand>, out: (String) -> Unit) {
        if (cmds.isEmpty()) { out("No /ads $label commands are installed."); return }
        out("§6/ads $label§7 commands:")
        for (c in cmds) out("§f${c.usage}§7  ${c.summary}")
    }

    private fun exec(label: String, cmd: AdsCommand, args: List<String>, reply: (String) -> Unit) {
        EngineerClient.safely("ads $label ${cmd.name}") {
            if (cmd.requiresSim && !P3Sim.inSim) { reply("§8[§6ADS§8] §7Open ADS first (the title screen button, or /ads)."); return@safely }
            cmd.client?.invoke(AdsClientContext(args) { reply("§8[§6ADS§8] §7$it") })
            if (cmd.server != null) SimNet.send(NET_KIND, JsonObject().also { o ->
                o.addProperty("group", label); o.addProperty("name", cmd.name)
                o.add("args", JsonArray().also { a -> args.forEach(a::add) })
            }.toString())
        }
    }

    /** Registers the server-side receiver for commands with a server part. Called from `AdsBootstrap.init()`. */
    fun onInit() {
        SimNet.onServer(NET_KIND) { p, data ->
            val o = try { JsonParser.parseString(data).asJsonObject } catch (e: Exception) { return@onServer }
            val group = o.get("group")?.asString ?: return@onServer
            val name = o.get("name")?.asString ?: return@onServer
            val args = o.getAsJsonArray("args")?.map { it.asString }.orEmpty()
            val cmd = (if (group == "dev") dev else if (group == "run") run else emptyList()).firstOrNull { it.name == name } ?: return@onServer
            val server = cmd.server ?: return@onServer
            // Anyone in a hosted world can send this message, and a modified client skips the client-side checks: only the host runs the commands that
            // restore, force or stop the run. A joiner may only read the channel dump.
            if (!Sim.isHost(p) && !(group == "dev" && name == "hud" && args.firstOrNull() == "dump")) {
                Sim.asPlayer(p) { Sim.note("Only the host can use /ads $group $name.") }
                return@onServer
            }
            try { server(AdsServerContext(p, args)) } catch (t: Throwable) { log.error("[ads] $group $name failed", t) }
        }
    }
}
