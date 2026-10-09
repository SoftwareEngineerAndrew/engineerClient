package com.engineerclient.p3sim

/**
 * Named places in the arena (standable, checked against the built arena), for starts and the menu's
 * teleports. The section starts stand on the walkway's gray carpet (1/16 high), not in it.
 */
object Spots {
    class Spot(val name: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float = 0f)

    /** Where you arrive: the red pad over P3's drop hole. */
    val LOBBY = Spot("Red pad (P3 drop)", 100.5, 169.0, 40.5, 0f)

    val P1 = Spot("P1 start", 73.5, 221.0, 14.5, 0f)
    val P2 = Spot("P2 (Storm floor)", 73.5, 165.0, 40.5, 0f)
    val P3_DROP = LOBBY
    val S1 = Spot("S1 start", 100.5, 116.0625, 40.5, 0f)
    val SS = Spot("Simon Says", 108.5, 120.0, 94.0, -90f)
    val S2 = Spot("S2 start", 100.5, 115.0625, 128.5, 90f)
    val S3 = Spot("S3 start", 12.5, 115.0625, 131.5, 135f)
    val S4 = Spot("S4 start", 8.5, 115.0625, 44.5, -90f)
    val CORE = Spot("Core (in front)", 54.5, 115.0, 51.5, 0f)
    val P4 = Spot("P4 (Necron)", 54.5, 64.0, 108.5, 180f)

    val PURPLE_PAD = Spot("Purple pad", 114.5, 170.0, 94.5, 90f)
    val YELLOW_PAD = Spot("Yellow pad", 32.5, 170.0, 94.5, -90f)
    val GREEN_PAD = Spot("Green pad", 32.5, 170.0, 12.5, -90f)
    val RED_PAD = Spot("Red pad", 114.5, 170.0, 12.5, 90f)
    val LIGHTS = Spot("Lights device", 60.5, 132.0, 140.0, 0f)

    /**
     * Where a P3 start puts you: [from] 1 = the spawn set in Roles for this skill and class, else your first S1 terminal in your role's order (Tank's 21: T2, Archer's 43: T4), else your first S1 job's spot (the target plate if it's yours) (fast parties leap into S1 before
     * Goldor speaks), 2-4 = that section's door, 5 = the core.
     */
    fun p3Start(from: Int): Spot = when (from) {
        2 -> S2; 3 -> S3; 4 -> S4; 5 -> CORE
        else -> P3Plan.customSpot("spawn") ?: run {
            // Your first S1 terminal as your role lists it (Tank's 21: T2), else your first S1 job in menu order (the Mage's levers).
            val first = if (P3Plan.isMine("S4 Target")) "S4 Target"
                else P3Plan.mine().firstOrNull { it.startsWith("S1 T") } ?: P3Plan.jobsIn(1).firstOrNull { P3Plan.isMine(it) }
            first?.let { j -> Party.STANDS[j]?.let { startLook(j).let { l -> Spot(j, it.x, it.y, it.z, l.first, l.second) } } } ?: SS
        }
    }

    /**
     * How you face at P3's first tick on each job spot, as recorded: the S1 levers yaw 357-25 / pitch -22..-16, the S4 target 281-286 / 17-21,
     * T4 about 10-44 / 0-2, T2 -181 / 48 and Simon Says -131 / 1.4; the rest face 180 / 0 (not measured).
     */
    private fun startLook(job: String): Pair<Float, Float> = when (job) {
        "S1 east lever", "S1 west lever" -> 10f to -19f
        "S4 Target" -> 283f to 19f
        "S1 T4" -> 40f to 1f
        "S1 T2" -> 179f to 48f
        "S1 SS" -> -131f to 1.4f
        else -> 180f to 0f
    }

    /** The menus' teleports, by group (the main menu's Teleport shows the groups). */
    val teleportGroups = listOf(
        "P3" to listOf(P3_DROP, S1, SS, S2, LIGHTS, S3, S4, CORE),
        "Pads" to listOf(PURPLE_PAD, YELLOW_PAD, GREEN_PAD, RED_PAD),
        "Phases" to listOf(P1, P2, P4),
    )

    /** The menus' teleports, in order. */
    val teleports = teleportGroups.flatMap { it.second }
}
