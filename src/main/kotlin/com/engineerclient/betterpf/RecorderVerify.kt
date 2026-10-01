package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState

/**
 * VERIFICATION ONLY - Better PF's verify mode (a VERIFY_RECORDER file next to the runs folder):
 * every call goes to the recorder and to the 0.6.13 one ([LegacyRunRecorder], writing to runs-legacy/),
 * so the two files of the same run can be diffed. The old one costs what it always did while this is
 * on. Its failures are logged once and never reach the real recording. Delete with LegacyRunRecorder.
 */
internal class TeeRecorder(private val main: RecorderApi, private val check: RecorderApi) : RecorderApi {
    private var checkFailed = false

    private inline fun both(call: RecorderApi.() -> Unit) {
        main.call()
        if (checkFailed) return
        try {
            check.call()
        } catch (t: Throwable) {
            checkFailed = true
            EngineerClient.logger.error("[ec] betterpf verify: the old recorder failed; verify copy stopped", t)
        }
    }

    override val abandoned get() = main.abandoned
    override fun onTick(level: ClientLevel) = both { onTick(level) }
    override fun onFrame(partialTick: Float, yaw: Float, pitch: Float) = both { onFrame(partialTick, yaw, pitch) }
    override fun onBlockUpdate(pos: BlockPos, state: BlockState) = both { onBlockUpdate(pos, state) }
    override fun onServerTick() = both { onServerTick() }
    override fun onSlotClick(slot: Int, button: Int, type: String) = both { onSlotClick(slot, button, type) }
    override fun onBlockUse(pos: BlockPos) = both { onBlockUse(pos) }
    override fun onTeleport(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, rel: List<String>) = both { onTeleport(x, y, z, yaw, pitch, rel) }
    override fun onBatSound(x: Double, y: Double, z: Double, volume: Float) = both { onBatSound(x, y, z, volume) }
    override fun onPickup(itemId: Int, collectorId: Int) = both { onPickup(itemId, collectorId) }
    override fun onChat(message: String, colored: String?) = both { onChat(message, colored) }
    override fun onChestEvent(pos: BlockPos, openCount: Int) = both { onChestEvent(pos, openCount) }
    override fun onRoomEnter(name: String?) = both { onRoomEnter(name) }
    override fun onGuiClose() = both { onGuiClose() }
    override fun onContainerOpen(title: String, menu: String, w: Int, h: Int, slots: List<IntArray>) = both { onContainerOpen(title, menu, w, h, slots) }
    override fun onContainerTick(items: List<ItemStack>, carried: ItemStack) = both { onContainerTick(items, carried) }
    override fun onContainerMouse(partialTick: Float, x: Float, y: Float) = both { onContainerMouse(partialTick, x, y) }
    override fun onContainerClick(x: Float, y: Float, button: Int) = both { onContainerClick(x, y, button) }
    override fun finish() = both { finish() }
}
