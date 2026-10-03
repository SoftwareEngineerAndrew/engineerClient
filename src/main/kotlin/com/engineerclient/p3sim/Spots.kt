package com.engineerclient.p3sim

/** Named places in the arena (standable, checked against the built arena), for starts and the menu's teleports. */
object Spots {
    class Spot(val name: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float = 0f)

    /** Where you arrive: the red pad over P3's drop hole. */
    val LOBBY = Spot("Red pad (P3 drop)", 100.5, 169.0, 40.5, 0f)

    val P1 = Spot("P1 start", 73.5, 221.0, 14.5, 0f)
    val P2 = Spot("P2 (Storm floor)", 73.5, 165.0, 40.5, 0f)
    val P3_DROP = LOBBY
    val S1 = Spot("S1 start", 100.5, 116.0, 40.5, 0f)
    val SS = Spot("Simon Says", 108.5, 120.0, 94.0, -90f)
    val S2 = Spot("S2 start", 100.5, 115.0, 128.5, 90f)
    val S3 = Spot("S3 start", 12.5, 115.0, 131.5, 135f)
    val S4 = Spot("S4 start", 8.5, 115.0, 44.5, -90f)
    val CORE = Spot("Core (in front)", 54.5, 115.0, 51.5, 0f)
    val P4 = Spot("P4 (Necron)", 54.5, 64.0, 108.5, 180f)

    val PURPLE_PAD = Spot("Purple pad", 114.5, 170.0, 94.5, 90f)
    val YELLOW_PAD = Spot("Yellow pad", 32.5, 170.0, 94.5, -90f)
    val GREEN_PAD = Spot("Green pad", 32.5, 170.0, 12.5, -90f)
    val RED_PAD = Spot("Red pad", 114.5, 170.0, 12.5, 90f)
    val LIGHTS = Spot("Lights device", 60.5, 132.0, 140.0, 0f)

    /**
     * Where a P3 start puts you: [from] 1 = your role's S1 spot (fast parties leap into S1 before
     * Goldor speaks), 2-4 = that section's door, 5 = the core.
     */
    fun p3Start(from: Int): Spot = when (from) {
        2 -> S2; 3 -> S3; 4 -> S4; 5 -> CORE
        else -> when (Party.myRole) {
            Party.Role.I4 -> Spot("Target plate", 63.5, 127.0, 35.5, 0f)
            Party.Role.EE3 -> Spot("S1 T1", 109.1, 118.8, 79.6, 180f)
            Party.Role.GATES -> Spot("S1 T4", 92.1, 112.0, 92.7, 90f)
            Party.Role.CORE -> Spot("S1 T3", 110.3, 113.0, 73.8, 180f)
            else -> SS
        }
    }

    /** The menu's teleports, in order. */
    val teleports = listOf(P3_DROP, S1, SS, S2, LIGHTS, S3, S4, CORE, PURPLE_PAD, YELLOW_PAD, GREEN_PAD, RED_PAD, P1, P2, P4)
}
