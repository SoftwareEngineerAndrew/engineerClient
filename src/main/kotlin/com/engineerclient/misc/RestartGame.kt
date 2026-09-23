package com.engineerclient.misc

import com.mojang.blaze3d.platform.InputConstants
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.alert

/**
 * F7: closes this instance and has PrismLauncher start it back up.
 *
 * The relaunch is a `sleep 3 && prismlauncher -l <instance>` handed to a detached shell, not a
 * timer in this JVM - PrismLauncher is single-instance and treats an extra invocation as an IPC
 * message to the launcher process that's already running, and it needs to see this instance's
 * process actually gone before it'll agree to start a fresh one. Doing the wait inside the
 * spawned shell means it survives however abruptly Minecraft's own shutdown behaves, instead of
 * racing a thread in a JVM that's mid-[Module.mc].stop.
 */
object RestartGame : Module(
    name = "Restart Game",
    key = InputConstants.KEY_F7,
    category = Category.custom("Blood Rush"),
    description = "F7: closes and relaunches this PrismLauncher instance.",
) {
    private const val INSTANCE_NAME = "26.1.2 BRW"

    override fun onKeybind() {
        alert("§cRestarting in 3 seconds...")
        ProcessBuilder("bash", "-c", "sleep 3 && prismlauncher -l '$INSTANCE_NAME'")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        mc.execute { mc.stop() }
    }
}
