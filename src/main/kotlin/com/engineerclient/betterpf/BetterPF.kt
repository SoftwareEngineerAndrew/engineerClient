package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents

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
                session = RunRecorder(runsDir, captureGeometry)
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

    override fun onDisable() {
        session?.finish()
        session = null
        super.onDisable()
    }
}
