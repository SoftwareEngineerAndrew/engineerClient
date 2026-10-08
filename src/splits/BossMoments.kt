package com.engineerclient.splits

/**
 * The in-boss moments the module's world watchers key off: whether the Watcher's dialog is over
 * and his move still to come, and whether everyone is in Goldor's core yet. Each watcher only runs
 * while its moment is still to be found.
 *
 * Nothing here touches Minecraft: the module feeds it chat and what it sees.
 */
class BossMoments {

    // Blood, split the way Devonian does: the dialog (his first line to "Let's see how you can handle
    // this."), the move (that line to the Watcher's first move at least 45 server ticks after it -
    // 55-148 ticks in recorded runs, depending on the camp), then the clear.
    /** "Let's see how you can handle this." */
    var watcherHandle: Stamp? = null
        private set
    private var watcherMoved: Stamp? = null

    // Goldor: everyone in the core.
    private var allIn: Stamp? = null

    fun reset() {
        watcherHandle = null; watcherMoved = null
        allIn = null
    }

    fun onChat(msg: String, at: Stamp) {
        if (msg == WATCHER_HANDLE && watcherHandle == null) watcherHandle = at
    }

    /** The Watcher's move: his first, at least 45 server ticks after the dialog ends. */
    fun onWatcherMoved(at: Stamp) { if (watcherHandle != null && watcherMoved == null) watcherMoved = at }

    /** The dialog is over and the Watcher's move is still to come. */
    val waitingForWatcher: Boolean get() = watcherHandle != null && watcherMoved == null

    fun onEveryoneInCore(at: Stamp) { if (allIn == null) allIn = at }

    /** Whether everyone-in is still to be found. */
    val waitingForCore: Boolean get() = allIn == null

    private companion object {
        const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
    }
}
