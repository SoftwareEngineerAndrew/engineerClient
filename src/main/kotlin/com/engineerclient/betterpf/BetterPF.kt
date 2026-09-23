package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

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
    private val captureGeometry by BooleanSetting("Capture Geometry", true, desc = "Also records the dungeon's blocks (just the surfaces), so the replay has the actual rooms. Scanned a couple of chunks per tick.")
    private val uploadRuns by BooleanSetting("Upload Runs", true, desc = "Uploads each finished run to the Better PF viewer (cameronwilcox.com/betterpf). Needs the upload key.")
    private val uploadKey by StringSetting("Upload Key", "", 64, desc = "Key for uploading runs to the viewer. Ask undonecoffee for it.")

    private const val UPLOAD_URL = "https://www.cameronwilcox.com/betterpf/api/runs"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    private var session: RunRecorder? = null
    private val runsDir get() = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("betterpf").resolve("runs")

    init {
        // Chunk loads come from Fabric directly (Odin has no chunk event); ignored when off.
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (enabled) EngineerClient.safely("betterpf chunk") { session?.onChunkLoad(chunk.pos) }
        }

        on<LevelEvent.Load> {
            EngineerClient.safely("betterpf start") {
                session?.finish()
                session = RunRecorder(runsDir, captureGeometry, ::upload)
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

        on<BlockUpdateEvent> { EngineerClient.safely("betterpf block") { session?.onBlockUpdate(pos, updated) } }
        on<MessageEvent.Chat> { EngineerClient.safely("betterpf chat") { session?.onChat(message) } }
        on<RoomEnterEvent> { EngineerClient.safely("betterpf room") { session?.onRoomEnter(room?.name) } }
    }

    /**
     * Posts a finished run to the viewer. www directly: the bare domain 301s to www, and a redirected
     * POST turns into a GET. Runs on the recorder's writer thread, after the file is closed.
     */
    private fun upload(file: Path) {
        val key = uploadKey.trim()
        if (!uploadRuns || key.isEmpty()) return
        val request = HttpRequest.newBuilder(URI.create(UPLOAD_URL))
            .header("X-Upload-Key", key)
            .header("Content-Type", "application/octet-stream")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofFile(file))
            .build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { res, err ->
            when {
                err != null -> EngineerClient.chat("§8[§6EC§8]§c Better PF: upload failed (${err.javaClass.simpleName}). The run is still saved locally.")
                res.statusCode() == 200 -> EngineerClient.chat("§8[§6EC§8]§7 Better PF: uploaded - §fwww.cameronwilcox.com/betterpf")
                else -> EngineerClient.chat("§8[§6EC§8]§c Better PF: upload refused (${res.statusCode()}: ${res.body().trim().take(80)}). The run is still saved locally.")
            }
        }
    }

    override fun onDisable() {
        session?.finish()
        session = null
        super.onDisable()
    }
}
