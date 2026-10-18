package com.engineerclient.p3sim.pack

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loading all packs together and the messages the player sees when they are missing. */
class PacksTest {
    @Test fun aMissingFolderIsOneClearMessageWithThePath(@TempDir tmp: Path) {
        val dir = tmp.resolve("config/engineerclient/ads/packs")
        val set = Packs.load(dir)
        assertNull(set.rooms); assertNull(set.layouts); assertNull(set.mobs); assertNull(set.runstate)
        assertFalse(set.canBuildDungeon)
        val lines = set.userLines()
        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines[0].contains(dir.toString()), "the message names the folder")
        assertTrue(lines[0].contains("rooms.index.json.gz") && lines[0].contains("runstate.json.gz"))
        Packs.unload()
    }

    @Test fun anEmptyFolderIsTheSameMessage(@TempDir dir: Path) {
        val set = Packs.load(dir)
        assertEquals(4, set.missing().size)
        assertEquals(1, set.userLines().size)
        assertTrue(set.userLines()[0].contains(dir.toString()))
        Packs.unload()
    }

    @Test fun someMissingPacksAreNamed(@TempDir dir: Path) {
        Files.write(dir.resolve("layouts.json.gz"), Fixtures.gzip("""{"format":"ads-layouts","version":1,"layouts":[]}"""))
        Files.write(dir.resolve("mobs.json.gz"), Fixtures.gzip("""{"format":"ads-mobs","version":1,"archetypes":[]}"""))
        val set = Packs.load(dir)
        assertNotNull(set.layouts); assertNotNull(set.mobs); assertNull(set.rooms); assertNull(set.runstate)
        assertFalse(set.canBuildDungeon, "no rooms, no dungeon")
        val text = set.userLines().joinToString("\n")
        assertTrue(text.contains("room library") && text.contains("run-state tables"), text)
        assertTrue(text.contains("A full run needs the room library and the layout pool"), text)
        assertEquals(set, Packs.current)
        Packs.unload()
        assertNull(Packs.current)
    }

    @Test fun framePathUsesTheConfigFolder() {
        assertEquals(Path.of("/g/config/engineerclient/ads/packs"), PackFiles.dirFor(Path.of("/g")))
    }
}
