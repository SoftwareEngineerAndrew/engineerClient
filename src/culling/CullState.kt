package com.engineerclient.culling

/**
 * What the two Sodium hooks share: the chunk columns to draw now ([mask]) and what the last render list left out. Nothing
 * of the game or of Odin in here: [culls] is asked once for every section in view each time a render list is made.
 */
object CullState {
    /** The chunk columns to draw, or null: draw everything. Set on the render thread ([ChunkCulling.frame]). */
    @Volatile @JvmField var mask: CullMask? = null

    /** Each hook has run this launch: Sodium is there and built the way the hooks expect (render thread). */
    var frameHook = false; private set
    var listHook = false; private set

    // sections offered and left out while a render list is made, and the last finished list's totals
    private var offered = 0
    private var dropped = 0
    var lastOffered = 0; private set
    var lastDropped = 0; private set

    /** Whether the section in chunk column ([cx], [cz]) stays off the render list. From VisibleChunkCollector.visit. */
    @JvmStatic
    fun culls(cx: Int, cz: Int): Boolean {
        listHook = true
        val m = mask ?: return false
        offered++
        if (m.keeps(cx, cz)) return false
        dropped++
        return true
    }

    /** A frame's terrain setup begins: the list made since the last one is finished, its counts kept. */
    fun newFrame() {
        frameHook = true
        if (offered > 0) { lastOffered = offered; lastDropped = dropped; offered = 0; dropped = 0 }
    }
}
