package com.engineerclient.p3sim.run.plugin

import org.slf4j.LoggerFactory

/**
 * Finds and holds the installed plug-ins (the Fabric entrypoint `engineerclient:ads`, see [AdsPlugin]). Loaded once by
 * `AdsBootstrap.init()`. All reads are safe when no plug-in exists.
 */
object AdsPlugins {
    /** Bumped when a rule of the compatibility contract in [AdsPlugin] is broken. */
    const val API_VERSION = 1
    const val ENTRYPOINT = "engineerclient:ads"

    private val log = LoggerFactory.getLogger("engineerclient")

    @Volatile var plugins: List<AdsPlugin> = emptyList()
        private set

    /** Loads the entrypoint. Never throws; a bad plug-in is logged and skipped. Safe to call twice (the second call does nothing). */
    @Synchronized fun load() {
        if (loaded) return
        loaded = true
        val found = try {
            net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypoints(ENTRYPOINT, AdsPlugin::class.java)
        } catch (t: Throwable) { log.warn("[ads] could not read the '$ENTRYPOINT' entrypoint", t); emptyList() }
        val ok = ArrayList<AdsPlugin>()
        for (p in found) {
            try {
                if (p.apiVersion > API_VERSION) { log.warn("[ads] plug-in ${p.id} needs ADS API ${p.apiVersion} but this game has $API_VERSION; ignored"); continue }
                p.onLoad(object : PluginContext {
                    override val apiVersion: Int get() = API_VERSION
                    override fun log(message: String) { log.info("[ads:${p.id}] $message") }
                })
                ok += p
                log.info("[ads] plug-in ${p.id} loaded")
            } catch (t: Throwable) { log.warn("[ads] a plug-in failed to load and was ignored", t) }
        }
        plugins = ok
    }
    private var loaded = false

    /** For tests: installs plug-ins without Fabric. */
    fun installForTest(list: List<AdsPlugin>) { plugins = list; loaded = true }

    /** The first blood-camp factory that supports [room], or null (the Watcher's own camp then runs). */
    fun bloodCamp(room: BloodRoomSpec): BloodCampFactory? {
        for (p in plugins) {
            val f = try { p.bloodCamp() } catch (t: Throwable) { log.warn("[ads] plug-in ${p.id} failed in bloodCamp()", t); null } ?: continue
            if (try { f.supports(room) } catch (t: Throwable) { log.warn("[ads] plug-in ${p.id} failed in supports()", t); false }) return f
        }
        return null
    }

    /** Every strategy offered by any plug-in, in entrypoint order (the neutral default is the bots module's own). */
    fun strategies(): List<PartyStrategy> = plugins.flatMap { p ->
        try { p.partyStrategies() } catch (t: Throwable) { log.warn("[ads] plug-in ${p.id} failed in partyStrategies()", t); emptyList() }
    }

    fun strategy(id: String): PartyStrategy? = strategies().firstOrNull { it.id == id }
}
