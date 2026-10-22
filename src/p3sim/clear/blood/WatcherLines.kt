package com.engineerclient.p3sim.clear.blood

/**
 * The line that ends the Watcher's stage, which the blood module says itself. What he says DURING the camp belongs to the camp
 * (`camp/CampLines`, through `BloodCampHost.watcherSays`).
 */
object WatcherLines {
    const val NAME = "The Watcher"

    /** The line that ends the Watcher's stage; the party may walk on to the boss. */
    const val PASS = "You have proven yourself. You may pass."

    /** A `[BOSS]` chat line in the form Hypixel sends (the whole name red, the text white; Maxor's layout is different). */
    fun chat(line: String, name: String = NAME): String = "§c[BOSS] $name§r§f: $line"
}
