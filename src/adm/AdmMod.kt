package com.adm

import com.adm.commands.*
import com.adm.events.EventDispatcher
import com.adm.events.core.EventBus
import com.adm.features.ModuleManager
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.utils.IrisCompatability
import com.adm.utils.ServerUtils
import com.adm.utils.handlers.TickTasks
import com.adm.utils.render.ItemStateRenderer
import com.adm.utils.render.NoDepthFeatureRenderer
import com.adm.utils.skyblock.*
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.ui.widget.CustomGUIImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.FeatureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

object AdmMod : ClientModInitializer {

    val logger: Logger = LogManager.getLogger("ADM")

    @JvmStatic
    val mc: Minecraft = Minecraft.getInstance()

    /**
     * Main config file location.
     * @see com.adm.config.ModuleConfig
     * @see com.adm.config.DungeonWaypointConfig
     */
    val configFile: File = File(mc.gameDirectory, "config/adm/").apply {
        try {
            moveOldConfig(this)
            if (isFile) delete() // Delete old bugged files that prevent creating the directory
            if (!exists()) mkdirs()
        } catch (e: Exception) {
            println("Error initializing module config\n${e.message}")
            logger.error("Error initializing module config", e)
        }
    }

    const val MOD_ID = "engineerclient"

    val version: Version by lazy { FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().metadata.version }
    val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)

    /** Settings from before the file was adm-config.json. Odin's are imported once by EngineerClient's ModTakeover. */
    private fun moveOldConfig(dir: File) {
        val file = File(dir, "adm-config.json")
        if (!file.exists()) File(dir, "config.json").takeIf { it.isFile }?.renameTo(file)
    }

    override fun onInitializeClient() {
        FeatureRendererRegistry.register(NoDepthFeatureRenderer.TYPE) { NoDepthFeatureRenderer() }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            arrayOf(
                soopyCommand,
                dungeonWaypointsCommand, cataCommand
            ).forEach { commodore -> commodore.register(dispatcher) }
        }

        listOf(
            this, LocationUtils, TickTasks, KuudraUtils,
            ActionBarListener, ServerUtils, EventDispatcher,
            DungeonListener, PartyUtils,
            DungeonUtils, SplitsManager, DungeonScan,
            IrisCompatability,
            ModuleManager, CustomGUIImpl,
        ).forEach { EventBus.subscribe(it) }

        PictureInPictureRendererRegistry.register { ItemStateRenderer() }
    }
}