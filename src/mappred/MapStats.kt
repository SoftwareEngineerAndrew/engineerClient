package com.engineerclient.mappred

/** The room names on the recorded F7/M7 maps on ADR (4494 complete maps, Sep-Oct 2026); the other floors' rooms are among them, with the same secrets. */
object MapStats {
    /** Name pools: the big normal shapes in [Shape] order, then normal 1x1s and the special types. */
    const val POOL_1X1 = 5
    const val POOL_PUZZLE = 6
    const val POOL_TRAP = 7
    const val POOL_YELLOW = 8
    const val POOL_RARE = 9
    const val POOL_BLOOD = 10
    const val POOL_FAIRY = 11
    const val POOL_ENTRANCE = 12
    const val POOLS = 13

    /**
     * Every room name on the recorded maps, with its pool, secrets and how many maps had it. A name comes up
     * at most once a map, and within a pool about equally often (yellow's Default twice as often, New Trap
     * twice Old Trap).
     */
    // 4494 complete F7/M7 maps: name, pool, secrets, times seen
    val NAMES = listOf(
        RoomName("Bridges", 0, 6, 509),
        RoomName("Gold", 0, 1, 504),
        RoomName("Crypt", 0, 5, 489),
        RoomName("Balcony", 0, 4, 480),
        RoomName("Mage", 0, 4, 479),
        RoomName("Grass Ruin", 0, 3, 477),
        RoomName("Redstone Warrior", 0, 3, 473),
        RoomName("Archway", 0, 3, 465),
        RoomName("Pedestal", 0, 5, 462),
        RoomName("Purple Flags", 0, 5, 461),
        RoomName("Doors", 0, 5, 461),
        RoomName("Pressure Plates", 0, 6, 457),
        RoomName("Skull", 0, 2, 455),
        RoomName("Grand Library", 0, 4, 448),
        RoomName("Deathmite", 1, 6, 967),
        RoomName("Catwalk", 1, 6, 943),
        RoomName("Gravel", 1, 6, 919),
        RoomName("Diagonal", 1, 4, 912),
        RoomName("Red Blue", 1, 4, 903),
        RoomName("Slime", 1, 5, 900),
        RoomName("Wizard", 1, 4, 890),
        RoomName("Hallway", 2, 3, 960),
        RoomName("Waterfall", 2, 8, 942),
        RoomName("Mossy", 2, 4, 919),
        RoomName("Pit", 2, 5, 915),
        RoomName("Quartz Knight", 2, 7, 899),
        RoomName("Pipes", 2, 7, 888),
        RoomName("Buttons", 3, 5, 723),
        RoomName("Stairs", 3, 4, 714),
        RoomName("Cathedral", 3, 8, 710),
        RoomName("Flags", 3, 7, 707),
        RoomName("Museum", 3, 5, 707),
        RoomName("Supertall", 3, 6, 697),
        RoomName("Rails", 3, 9, 688),
        RoomName("Mines", 3, 10, 671),
        RoomName("Atlas", 3, 6, 632),
        RoomName("Dino Site", 4, 4, 642),
        RoomName("Lava Ravine", 4, 6, 626),
        RoomName("Pirate", 4, 6, 616),
        RoomName("Layers", 4, 8, 610),
        RoomName("Market", 4, 5, 604),
        RoomName("Spider", 4, 9, 601),
        RoomName("Withermancer", 4, 4, 599),
        RoomName("Melon", 4, 7, 596),
        RoomName("Chambers", 4, 5, 587),
        RoomName("Well", 4, 7, 584),
        RoomName("Altar", 4, 6, 538),
        RoomName("Double Diamond", 5, 3, 613),
        RoomName("Andesite", 5, 2, 607),
        RoomName("Chains", 5, 2, 607),
        RoomName("Basement", 5, 1, 607),
        RoomName("Long Hall", 5, 3, 605),
        RoomName("Mushroom", 5, 1, 604),
        RoomName("Scaffolding", 5, 2, 603),
        RoomName("Painting", 5, 2, 602),
        RoomName("Mural", 5, 1, 596),
        RoomName("Staircase", 5, 3, 594),
        RoomName("Small Waterfall", 5, 2, 589),
        RoomName("Redstone Crypt", 5, 3, 587),
        RoomName("Prison Cell", 5, 1, 585),
        RoomName("Perch", 5, 2, 585),
        RoomName("Blue Skulls", 5, 1, 579),
        RoomName("Raccoon", 5, 4, 578),
        RoomName("Zodd", 5, 1, 570),
        RoomName("Locked Away", 5, 1, 567),
        RoomName("Tomioka", 5, 0, 555),
        RoomName("Big Red Flag", 5, 2, 551),
        RoomName("Red Green", 5, 3, 494),
        RoomName("Overgrown", 5, 3, 485),
        RoomName("Temple", 5, 3, 484),
        RoomName("Leaves", 5, 1, 457),
        RoomName("Sloth", 5, 1, 450),
        RoomName("Dome", 5, 2, 450),
        RoomName("Cage", 5, 1, 449),
        RoomName("Beams", 5, 2, 446),
        RoomName("Dip", 5, 2, 439),
        RoomName("Banners", 5, 1, 430),
        RoomName("Duncan", 5, 1, 411),
        RoomName("Lots Of Floors", 5, 3, 401),
        RoomName("Steps", 5, 1, 375),
        RoomName("Small Stairs", 5, 2, 370),
        RoomName("Dueces", 5, 3, 365),
        RoomName("Ritual", 5, 3, 363),
        RoomName("Arrow Trap", 5, 1, 361),
        RoomName("Jumping Skulls", 5, 1, 359),
        RoomName("Cell", 5, 1, 358),
        RoomName("Silver Sword", 5, 1, 354),
        RoomName("Sarcophagus", 5, 3, 351),
        RoomName("Hall", 5, 0, 350),
        RoomName("Water", 5, 2, 350),
        RoomName("Admin", 5, 0, 348),
        RoomName("Overgrown Chains", 5, 2, 344),
        RoomName("Mirror", 5, 1, 343),
        RoomName("Cages", 5, 2, 341),
        RoomName("End", 5, 2, 339),
        RoomName("Slabs", 5, 2, 335),
        RoomName("Redstone Key", 5, 3, 331),
        RoomName("Spikes", 5, 3, 331),
        RoomName("Drop", 5, 2, 326),
        RoomName("Criss-Cross", 5, 1, 320),
        RoomName("Granite", 5, 2, 319),
        RoomName("Cobble Wall Pillar", 5, 2, 109),
        RoomName("Knight", 5, 3, 98),
        RoomName("Multicolored", 5, 1, 96),
        RoomName("Black Flag", 5, 3, 96),
        RoomName("Logs", 5, 4, 94),
        RoomName("Carpets", 5, 1, 93),
        RoomName("Quad Lava", 5, 2, 89),
        RoomName("Golden Oasis", 5, 1, 78),
        RoomName("Ice Fill", 6, 0, 1366),
        RoomName("Creeper Beams", 6, 0, 1337),
        RoomName("Three Weirdos", 6, 0, 1336),
        RoomName("Ice Path", 6, 0, 1333),
        RoomName("Quiz", 6, 0, 1329),
        RoomName("Water Board", 6, 0, 1321),
        RoomName("Teleport Maze", 6, 0, 1319),
        RoomName("Tic Tac Toe", 6, 1, 1294),
        RoomName("Boulder", 6, 0, 1266),
        RoomName("Lower Blaze", 6, 1, 1191),
        RoomName("Higher Blaze", 6, 1, 1108),
        RoomName("New Trap", 7, 3, 2931),
        RoomName("Old Trap", 7, 4, 1563),
        RoomName("Default", 8, 0, 1746),
        RoomName("Shadow Assassin", 8, 0, 945),
        RoomName("King Midas", 8, 0, 903),
        RoomName("Dragon", 8, 0, 900),
        RoomName("Vinny 8 Ball", 9, 1, 20),
        RoomName("Stone Window", 9, 2, 13),
        RoomName("Rare Pillars", 9, 1, 12),
        RoomName("Rare Overgrown", 9, 3, 12),
        RoomName("Lava Pit", 9, 3, 11),
        RoomName("Trinity", 9, 4, 9),
        RoomName("Rail Track", 9, 3, 8),
        RoomName("Tombstone", 9, 2, 8),
        RoomName("Three Floors", 9, 1, 8),
        RoomName("Sand Dragon", 9, 1, 7),
        RoomName("Rare Carpets", 9, 1, 1),
        RoomName("Blood", 10, 0, 4494),
        RoomName("Fairy", 11, 0, 4494),
        RoomName("Entrance", 12, 0, 4494),
    )

    /**
     * Each pool's average secrets over its names but [skip] (the names the map already has, the names the floor never
     * has), each as often as it comes up; a pool [skip] empties keeps its average over every name.
     */
    fun meanSecrets(skip: Set<String> = emptySet()): DoubleArray = DoubleArray(POOLS) { p ->
        val left = NAMES.filter { it.pool == p && it.name !in skip }.ifEmpty { NAMES.filter { it.pool == p } }
        val seen = left.sumOf { it.seen.toDouble() }
        if (seen > 0) left.sumOf { it.secrets * it.seen.toDouble() } / seen else 0.0
    }
}

/** A room name: its pool (MapStats.POOL_*), its secrets, and how many recorded maps had it. */
class RoomName(val name: String, val pool: Int, val secrets: Int, val seen: Int)
