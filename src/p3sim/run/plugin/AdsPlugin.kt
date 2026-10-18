package com.engineerclient.p3sim.run.plugin

/**
 * The plug-in seam. ADS (public) carries hooks only; anything that encodes strategy lives in an optional plug-in mod
 * (a separate jar) that implements this interface and declares it as a Fabric entrypoint:
 *
 * ```json
 * "entrypoints": { "engineerclient:ads": [ { "adapter": "kotlin", "value": "com.example.MyAdsPlugin" } ] }
 * ```
 * (a Kotlin `object` or a class with a public no-argument constructor; plain Java `"value": "com.example.MyAdsPlugin"` also works).
 * [AdsPlugins] reads the entrypoint with `FabricLoader.getEntrypoints("engineerclient:ads", AdsPlugin::class.java)` once per
 * game start, inside a try/catch: a plug-in that fails to load, or whose [apiVersion] is newer than the game's, is logged
 * and ignored. EVERYTHING is optional: with no plug-in installed ADS runs with neutral defaults (see [BloodCampFactory] and
 * [PartyStrategy] for what each default is).
 *
 * Compatibility rules (so a plug-in jar keeps working across public releases):
 *  - Interfaces a plug-in IMPLEMENTS ([AdsPlugin], [BloodCampFactory], [BloodCamp], [PartyStrategy]) only ever gain
 *    members that have default bodies. A plug-in never has to change because the public side grew.
 *  - Interfaces the public side implements and the plug-in CALLS ([BloodCampHost], [StrategyContext] and the data classes
 *    they carry) may gain members freely; a plug-in that does not know them ignores them.
 *  - Breaking either rule bumps [AdsPlugins.API_VERSION]; the plug-in states the version it was built against in [apiVersion].
 */
interface AdsPlugin {
    /** A short stable id for logs and settings (`"example"`). */
    val id: String

    /** The [AdsPlugins.API_VERSION] this plug-in was compiled against. A larger number than the game's means "too new": ignored. */
    val apiVersion: Int get() = 1

    /** Called once after the plug-in is loaded, before any run. */
    fun onLoad(ctx: PluginContext) {}

    /**
     * How the blood room's camp behaves: when the blood door opens, ADS asks the first plug-in that returns a factory here
     * (in entrypoint order) to run the camp. Null (the default) means this plug-in has none; with no factory anywhere ADS
     * runs its own Watcher's camp (`WatcherCamp`).
     */
    fun bloodCamp(): BloodCampFactory? = null

    /** Party strategy presets this plug-in offers (who does what in the clear); ADS lists them in the run settings next to the neutral default. */
    fun partyStrategies(): List<PartyStrategy> = emptyList()
}

/** What a plug-in may learn about the game when it loads. May gain members. */
interface PluginContext {
    /** The ADS API version of this game. */
    val apiVersion: Int
    /** Writes a line to the game log with the plug-in's id as prefix. */
    fun log(message: String)
}
