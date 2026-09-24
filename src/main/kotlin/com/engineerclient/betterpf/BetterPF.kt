package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.level.block.Blocks
import java.util.Optional
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Better PF, step 1: record everything about a dungeon run, from instance load to leaving, so it
 * can later be replayed/simulated in the browser (tools/betterpf-viewer).
 *
 * A session starts on every world load while this is on; [RunRecorder] keeps it only if the world
 * turns out to be a dungeon. Runs land in config/engineerclient/betterpf/runs/.
 */
object BetterPF : Module(
    name = "Better PF",
    category = Category.custom("Blood Rush"),
    description = "Records everything about each dungeon run (players, mobs, blocks, chat, rooms) for replaying it in the browser.",
) {
    private val captureGeometry by BooleanSetting("Capture Geometry", true, desc = "Captures each dungeon room once (every block) for the viewer's shared room library, plus the doors/walls between rooms each run. Rooms the library already has are skipped.")
    private val uploadRuns by BooleanSetting("Upload Runs", true, desc = "Uploads each finished run to the Better PF viewer (undonecoffee.com/betterpf). Needs the upload key.")
    private val uploadKey by StringSetting("Upload Key", "", 64, desc = "Key for uploading runs to the viewer. Ask undonecoffee for it.")

    private const val SITE = "undonecoffee.com"
    private const val RUNS_URL = "https://$SITE/betterpf/api/runs"
    private const val ROOMS_URL = "https://$SITE/betterpf/api/rooms"

    /** Rooms the server's library already has ("Name|ROTATION"); null until the fetch lands. */
    @Volatile private var libraryKeys: Set<String>? = null
    private val CONTROL_CODES = Regex("\u00a7.")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    private var session: RunRecorder? = null
    private val runsDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("betterpf").resolve("runs")

    init {
        on<LevelEvent.Load> {
            EngineerClient.safely("betterpf start") {
                session?.finish()
                libraryKeys = null
                fetchLibraryKeys()
                session = RunRecorder(runsDir, captureGeometry, { libraryKeys }, ::upload)
            }
        }

        on<LevelEvent.Unload> {
            EngineerClient.safely("betterpf end") { session?.finish(); session = null }
        }

        on<TickEvent.End> {
            val s = session ?: return@on
            EngineerClient.safely("betterpf tick") { s.onTick(level) }
            if (s.abandoned) session = null
        }

        // Every rendered frame: your own camera, so POV replays show exactly what you saw.
        on<RenderEvent.Last> {
            val s = session ?: return@on
            val player = EngineerClient.mc.player ?: return@on
            EngineerClient.safely("betterpf frame") { s.onFrame(EngineerClient.mc.deltaTracker.getGameTimeDeltaPartialTick(true), player.yRot, player.xRot) }
        }

        on<BlockUpdateEvent> { EngineerClient.safely("betterpf block") { session?.onBlockUpdate(pos, updated) } }
        // Chat straight off the network, before any mod can hide it (chat cleaners hide the terminal /
        // device / gate messages the report needs). Handed to the client thread, where ticks happen.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            val colored = legacyText(content)
            EngineerClient.mc.execute { EngineerClient.safely("betterpf chat") { session?.onChat(text, colored) } }
        }
        // Chests opening and closing (the lid's block event: how many players have it open), so the
        // viewer can open the chests people looted.
        onReceive<ClientboundBlockEventPacket> {
            if (b0 != 1 || (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.ENDER_CHEST)) return@onReceive
            val at = pos.immutable(); val open = b1
            EngineerClient.mc.execute { EngineerClient.safely("betterpf chest") { session?.onChestEvent(at, open) } }
        }
        on<RoomEnterEvent> { EngineerClient.safely("betterpf room") { session?.onRoomEnter(room?.name) } }

        // Container screens you open (terminal GUIs among them), for exact terminal times.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            EngineerClient.safely("betterpf gui") { session?.onGuiOpen(screen.title.string) }
            ScreenEvents.remove(screen).register { EngineerClient.safely("betterpf gui close") { session?.onGuiClose() } }
        }
    }

    /**
     * A chat line with its formatting as § codes: colour (the nearest of the 16 chat colours, or
     * §#rrggbb for any other), then bold/italic/underline/strikethrough/obfuscated, written again
     * wherever the style changes. § codes already inside the text are kept as they are.
     */
    internal fun legacyText(message: Component): String {
        val sb = StringBuilder()
        var last = ""
        message.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            if (text.isNotEmpty()) {
                val codes = styleCodes(style)
                if (codes != last) { if (last.isNotEmpty()) sb.append("§r"); sb.append(codes); last = codes }
                sb.append(text)
            }
            Optional.empty()
        }, Style.EMPTY)
        return sb.toString()
    }

    private fun styleCodes(style: Style): String {
        val sb = StringBuilder()
        style.color?.let { sb.append(colorCode(it)) }
        if (style.isObfuscated) sb.append("§k")
        if (style.isBold) sb.append("§l")
        if (style.isStrikethrough) sb.append("§m")
        if (style.isUnderlined) sb.append("§n")
        if (style.isItalic) sb.append("§o")
        return sb.toString()
    }

    private fun colorCode(color: TextColor): String {
        val legacy = ChatFormatting.entries.firstOrNull { it.isColor && it.color == color.value }
        return if (legacy != null) "§" + legacy.char else "§#" + String.format(java.util.Locale.ROOT, "%06x", color.value and 0xFFFFFF)
    }

    private fun fetchLibraryKeys() {
        val request = HttpRequest.newBuilder(URI.create(ROOMS_URL)).timeout(Duration.ofSeconds(15)).GET().build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { res, err ->
            if (err != null || res.statusCode() != 200) return@whenComplete
            EngineerClient.safely("betterpf library keys") {
                val keys = JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("keys")
                libraryKeys = keys.map { it.asString }.toSet()
            }
        }
    }

    /**
     * Sends a finished run to the viewer, off the game thread: first the room captures the library
     * didn't have (one request each), then the run itself with its summary in a header - the site
     * stores files as-is and never unpacks them, so it needs to be told what the list shows.
     */
    private fun upload(file: Path) {
        val key = uploadKey.trim()
        if (!uploadRuns || key.isEmpty()) return
        Thread.ofVirtual().name("betterpf-upload").start {
            try {
                val (summary, rooms) = readForUpload(file)
                for ((roomKey, line) in rooms) {
                    val req = HttpRequest.newBuilder(URI.create(ROOMS_URL))
                        .header("X-Upload-Key", key).header("X-Room-Key", roomKey).header("Content-Type", "application/json")
                        .timeout(Duration.ofMinutes(2)).POST(HttpRequest.BodyPublishers.ofString(line)).build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                    if (res.statusCode() != 200) EngineerClient.logger.warn("[ec] betterpf: room $roomKey refused (${res.statusCode()})")
                }
                val req = HttpRequest.newBuilder(URI.create(RUNS_URL))
                    .header("X-Upload-Key", key)
                    .header("X-Run-Summary", summary.toString())
                    .header("Content-Type", "application/octet-stream")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofFile(file))
                    .build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200) {
                    val id = runCatching { JsonParser.parseString(res.body()).asJsonObject["id"].asString }.getOrDefault("")
                    EngineerClient.chat("§8[§6EC§8]§7 Better PF: uploaded - §f$SITE/betterpf/$id")
                } else {
                    EngineerClient.chat("§8[§6EC§8]§c Better PF: upload refused (${res.statusCode()}: ${res.body().trim().take(80)}). The run is still saved locally.")
                }
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] betterpf upload failed", t)
                EngineerClient.chat("§8[§6EC§8]§c Better PF: upload failed (${t.javaClass.simpleName}). The run is still saved locally.")
            }
        }
    }

    private val KIND = Regex("""^\{"k":"([a-z]+)"""")
    private val TICK = Regex(""""t":(\d+)""")

    /** One pass over the run file: the list summary (self, startMs, floor, party, ticks) and its room captures. */
    private fun readForUpload(file: Path): Pair<JsonObject, List<Pair<String, String>>> {
        val summary = JsonObject()
        val rooms = ArrayList<Pair<String, String>>()
        var ticks = 0
        BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(file)), Charsets.UTF_8), 1 shl 16).useLines { lines ->
            for (line in lines) {
                val kind = KIND.find(line)?.groupValues?.get(1) ?: continue
                TICK.find(line.take(48))?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > ticks) ticks = it }
                when (kind) {
                    "meta" -> JsonParser.parseString(line).asJsonObject.let { summary.add("self", it["self"]); summary.add("startMs", it["startMs"]) }
                    "floor" -> summary.add("floor", JsonParser.parseString(line).asJsonObject["floor"])
                    "party" -> summary.add("party", JsonParser.parseString(line).asJsonObject["m"])
                    "lib" -> JsonParser.parseString(line).asJsonObject["key"]?.asString?.let { rooms += it to line }
                }
            }
        }
        if (!summary.has("floor")) summary.addProperty("floor", "")
        if (!summary.has("party")) summary.add("party", JsonArray())
        summary.addProperty("ticks", ticks)
        return summary to rooms
    }

    override fun onDisable() {
        session?.finish()
        session = null
        super.onDisable()
    }
}
