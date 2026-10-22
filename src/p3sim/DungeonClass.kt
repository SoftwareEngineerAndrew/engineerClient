package com.engineerclient.p3sim

/**
 * The five dungeon classes, as the sim knows them: Odin's own names and order (its EMPTY left out), so the sim runs
 * without Odin and saved plans, profiles and party messages (which keep classes by name) read as before. Where the
 * sim hands a class to Odin ([OdinSimBridge]) it goes over by name.
 */
enum class DungeonClass { ARCHER, BERSERK, HEALER, MAGE, TANK }
