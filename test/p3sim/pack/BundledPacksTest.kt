package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.clear.traps.TrapTimings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which copy of each data pack file the game uses: the override folder's, else the one bundled in the jar (unpacked once into a
 * folder named by the content hash). Synthetic packs; the last tests also read the real bundled files, printing nothing.
 */
class BundledPacksTest {
    /** A fake jar: these bytes under these names. */
    private class MapBundle(val files: Map<String, ByteArray>) : BundledPacks {
        var opens = 0
        override fun open(name: String): InputStream? = files[name]?.let { opens++; ByteArrayInputStream(it) }
    }

    private fun validPacks(tag: String = "a"): Map<String, ByteArray> = mapOf(
        PackFiles.ROOMS_INDEX to Fixtures.gzip("""{"format":"ads-rooms","version":1,"designs":[],"note":"$tag"}"""),
        PackFiles.ROOMS_BLOCKS to "blocks-$tag".toByteArray(),
        PackFiles.LAYOUTS to Fixtures.gzip("""{"format":"ads-layouts","version":1,"layouts":[],"note":"$tag"}"""),
        PackFiles.MOBS to Fixtures.gzip("""{"format":"ads-mobs","version":1,"archetypes":[],"note":"$tag"}"""),
        PackFiles.RUNSTATE to Fixtures.gzip("""{"format":"ads-runstate","version":1,"note":"$tag"}"""),
        PackFiles.TRAPS to """{"default":{"ticksPerBlock":4}}""".toByteArray(),
    )

    private fun dirs(tmp: Path): Pair<Path, Path> = tmp.resolve("config/engineerclient/ads/packs") to tmp.resolve("config/engineerclient/ads/packs-bundled")
    private fun hashFolders(cache: Path): List<Path> = if (!Files.isDirectory(cache)) emptyList() else Files.list(cache).use { it.toList() }.filter { Files.isDirectory(it) }

    @AfterEach fun cleanUp() { Packs.unload() }

    @Test fun theBundledPacksLoadWithNoOverrideFolderAtAll(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        val set = Packs.load(dir, MapBundle(validPacks()))
        assertNotNull(set.rooms); assertNotNull(set.layouts); assertNotNull(set.mobs); assertNotNull(set.runstate)
        assertTrue(set.canBuildDungeon)
        assertFalse(set.report.hasErrors(), set.report.errors().toString())
        assertTrue(set.missing().isEmpty())
        for (n in PackFiles.BUNDLED) assertEquals(PackOrigin.JAR, set.source.origin(n), n)
        val folder = hashFolders(cache).single()
        assertEquals(16, folder.fileName.toString().length)
        for (n in PackFiles.BUNDLED) assertTrue(Files.isRegularFile(folder.resolve(n)), n)
        assertFalse(Files.exists(dir), "the override folder is never created")
    }

    @Test fun aFileInTheOverrideFolderWinsAndTheRestComesFromTheJar(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        Files.createDirectories(dir)
        Files.write(dir.resolve(PackFiles.MOBS), Fixtures.gzip("""{"format":"ads-mobs","version":1,"archetypes":[],"note":"mine"}"""))
        Files.write(dir.resolve(PackFiles.TRAPS), """{"default":{"ticksPerBlock":9}}""".toByteArray())
        val set = Packs.load(dir, MapBundle(validPacks()))
        assertEquals(PackOrigin.OVERRIDE, set.source.origin(PackFiles.MOBS))
        assertEquals(PackOrigin.OVERRIDE, set.source.origin(PackFiles.TRAPS))
        assertEquals(dir.resolve(PackFiles.MOBS), set.source.file(PackFiles.MOBS))
        for (n in listOf(PackFiles.ROOMS_INDEX, PackFiles.ROOMS_BLOCKS, PackFiles.LAYOUTS, PackFiles.RUNSTATE)) assertEquals(PackOrigin.JAR, set.source.origin(n), n)
        assertNotNull(set.mobs); assertTrue(set.canBuildDungeon)
        val lines = set.originLines()
        assertEquals(6, lines.size)
        assertTrue(lines.single { it.startsWith(PackFiles.MOBS) }.contains("overrides the jar"), lines.toString())
        assertTrue(lines.single { it.startsWith(PackFiles.LAYOUTS) }.contains("bundled in the jar"), lines.toString())
        assertEquals(dir.resolve(PackFiles.TRAPS), set.source.file(PackFiles.TRAPS))
        assertTrue(TrapTimings.load(set.source) !== TrapTimings.NONE, "the override's traps.json is the one read")
    }

    @Test fun anOverrideForEveryFileNeverTouchesTheJar(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Files.createDirectories(dir)
        for ((n, b) in validPacks("mine")) Files.write(dir.resolve(n), b)
        val jar = MapBundle(validPacks())
        val set = Packs.load(dir, jar)
        assertEquals(0, jar.opens)
        assertTrue(hashFolders(cache).isEmpty())
        assertTrue(PackFiles.BUNDLED.all { set.source.origin(it) == PackOrigin.OVERRIDE })
    }

    @Test fun aSecondLoadReusesTheUnpackedFilesAndCleansOlderFolders(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        val old = cache.resolve("0123456789abcdef"); Files.createDirectories(old); Files.write(old.resolve("junk"), byteArrayOf(1))
        val other = cache.resolve("my-notes"); Files.createDirectories(other)
        val set1 = Packs.load(dir, MapBundle(validPacks()))
        val folder = hashFolders(cache).single { it.fileName.toString() != "my-notes" }
        assertFalse(Files.exists(old), "an older hash folder is removed")
        assertTrue(Files.exists(other), "a folder that is not a hash folder is left alone")

        val blocks = folder.resolve(PackFiles.ROOMS_BLOCKS)
        val stamp = FileTime.fromMillis(1_000_000L)
        Files.setLastModifiedTime(blocks, stamp)
        val set2 = Packs.load(dir, MapBundle(validPacks()))
        assertEquals(stamp, Files.getLastModifiedTime(blocks), "the file was not unpacked again")
        assertEquals(blocks, set2.source.file(PackFiles.ROOMS_BLOCKS))
        assertEquals(blocks, set1.source.file(PackFiles.ROOMS_BLOCKS))
        assertTrue(set2.canBuildDungeon)
    }

    @Test fun aRebuiltPackGetsAFreshFolderAndTheOldOneGoes(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Packs.load(dir, MapBundle(validPacks("a")))
        val first = hashFolders(cache).single()
        Packs.load(dir, MapBundle(validPacks("b")))
        val second = hashFolders(cache).single()
        assertNotEquals(first, second)
        assertEquals("blocks-b", Files.readString(second.resolve(PackFiles.ROOMS_BLOCKS)))
    }

    @Test fun aDamagedBundledFileIsAPlainMessageNotACrash(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        val files = validPacks().toMutableMap(); files[PackFiles.MOBS] = "this is not gzip".toByteArray()
        val set = Packs.load(dir, MapBundle(files))
        assertNull(set.mobs)
        assertNotNull(set.rooms); assertNotNull(set.layouts); assertNotNull(set.runstate)
        val text = set.userLines().joinToString("\n")
        assertTrue(text.contains("mob tables"), text)
        assertTrue(text.contains("bundled in the jar"), text)
    }

    @Test fun aDamagedBundledRoomLibraryFailsTheDungeonWithTheMessage(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        val files = validPacks().toMutableMap(); files[PackFiles.ROOMS_INDEX] = byteArrayOf(0, 1, 2)
        val set = Packs.load(dir, MapBundle(files))
        assertNull(set.rooms); assertFalse(set.canBuildDungeon)
        val text = set.userLines().joinToString("\n")
        assertTrue(text.contains("room library") && text.contains("A full run needs the room library"), text)
    }

    @Test fun anOverrideRepairsADamagedBundledFile(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        Files.createDirectories(dir)
        Files.write(dir.resolve(PackFiles.MOBS), validPacks()[PackFiles.MOBS]!!)
        val files = validPacks().toMutableMap(); files[PackFiles.MOBS] = "garbage".toByteArray()
        val set = Packs.load(dir, MapBundle(files))
        assertNotNull(set.mobs)
    }

    @Test fun aJarWithoutThePacksAndNoOverrideGivesTheRareMessage(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        val set = Packs.load(dir, MapBundle(emptyMap()))
        assertFalse(set.canBuildDungeon)
        val lines = set.userLines()
        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines[0].contains("jar") && lines[0].contains(dir.toString()) && lines[0].contains("installing it again"), lines[0])
        assertTrue(set.originLines().all { it.endsWith("not found") })
    }

    @Test fun aJarMissingOneFileNamesIt(@TempDir tmp: Path) {
        val (dir, _) = dirs(tmp)
        val files = validPacks().toMutableMap(); files.remove(PackFiles.LAYOUTS)
        val set = Packs.load(dir, MapBundle(files))
        assertNull(set.layouts); assertFalse(set.canBuildDungeon)
        val text = set.userLines().joinToString("\n")
        assertTrue(text.contains("layout pool") && text.contains("missing from the jar and $dir"), text)
    }

    @Test fun aCacheFolderThatCannotBeMadeIsAMessageNotACrash(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Files.createDirectories(cache.parent); Files.write(cache, byteArrayOf(1))   // a file where the folder should be
        val set = Packs.load(dir, MapBundle(validPacks()))
        assertFalse(set.canBuildDungeon)
        val lines = set.userLines()
        assertTrue(lines[0].contains("could not load its data packs"), lines.toString())
        assertTrue(lines.any { it.contains("could not unpack") }, lines.toString())
    }

    @Test fun anUnpackThatFailsSaysWhyAndDoesNotBlameTheInstall(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Files.createDirectories(cache.parent); Files.write(cache, byteArrayOf(1))
        val set = Packs.load(dir, MapBundle(validPacks()))
        assertEquals(cache, set.source.unpackFailedIn)
        val lines = set.userLines()
        assertTrue(lines[0].contains("copying them out into $cache"), lines.toString())
        assertFalse(lines.any { it.contains("damaged") || it.contains("installing it again") }, "a reinstall would not help: $lines")
        val cause = lines.single { it.contains("could not unpack") }
        assertTrue(Regex("[A-Za-z]+Exception: ").containsMatchIn(cause), "the error names what the file system said, not only a path: $cause")
        assertTrue(cause.contains("writable") && cause.contains("disk is not full"), cause)
    }

    @Test fun aDamagedUnpackedFileOfTheRightSizeIsUnpackedAgain(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Packs.load(dir, MapBundle(validPacks()))
        val folder = hashFolders(cache).single()
        val blocks = folder.resolve(PackFiles.ROOMS_BLOCKS)
        val bytes = Files.readAllBytes(blocks)
        bytes[0] = (bytes[0] + 1).toByte()   // same length, different content
        Files.write(blocks, bytes)
        assertFalse(validPacks()[PackFiles.ROOMS_BLOCKS]!!.contentEquals(Files.readAllBytes(blocks)))
        val set = Packs.load(dir, MapBundle(validPacks()))
        assertTrue(validPacks()[PackFiles.ROOMS_BLOCKS]!!.contentEquals(Files.readAllBytes(blocks)), "the file is the jar's again")
        assertEquals(blocks, set.source.file(PackFiles.ROOMS_BLOCKS))
        assertEquals(PackFiles.BUNDLED.size, Files.list(folder).use { it.count() }.toInt(), "no half-written leftovers")
    }

    @Test fun overrideFilesStillWorkWhenTheCacheIsBroken(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        Files.createDirectories(cache.parent); Files.write(cache, byteArrayOf(1))
        Files.createDirectories(dir)
        for ((n, b) in validPacks()) Files.write(dir.resolve(n), b)
        assertTrue(Packs.load(dir, MapBundle(validPacks())).canBuildDungeon)
    }

    @Test fun manyLoadsAtOnceAllSucceedAndLeaveOneIntactFolder(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        val jar = MapBundle(validPacks())
        val all = (1..12).map { Packs.loadAsync(dir, jar) }
        CompletableFuture.allOf(*all.toTypedArray()).get()
        assertTrue(all.all { it.get().canBuildDungeon })
        val folder = hashFolders(cache).single()
        for ((n, b) in validPacks()) assertTrue(b.contentEquals(Files.readAllBytes(folder.resolve(n))), n)
        assertEquals(PackFiles.BUNDLED.size, Files.list(folder).use { it.count() }.toInt(), "no half-written leftovers")
    }

    @Test fun twoGamesUnpackingTheSameJarAtOnceDoNotCorruptEachOther(@TempDir tmp: Path) {
        val jar = MapBundle(validPacks())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            // Many rounds, each with a fresh cache and all eight starting together: what this guards against is a gap of a few
            // microseconds in which a file being put in place is not there, so one round alone passes by luck.
            for (round in 1..40) {
                val (dir, cache) = dirs(tmp.resolve("round$round"))
                val go = java.util.concurrent.CountDownLatch(1)
                val jobs = (1..8).map { pool.submit<PackSource> { go.await(); PackSource.resolve(dir, jar, cache, PackReport()) } }
                go.countDown()
                for (r in jobs.map { it.get() }) for (n in PackFiles.BUNDLED) {
                    val file = assertNotNull(r.file(n), "round $round: $n was reported missing")
                    assertTrue(validPacks()[n]!!.contentEquals(Files.readAllBytes(file)), "round $round: $n")
                }
                assertEquals(PackFiles.BUNDLED.size, Files.list(hashFolders(cache).single()).use { it.count() }.toInt(), "round $round: no half-written leftovers")
            }
        } finally { pool.shutdown() }
    }

    // The real bundled files (packaged from src/resources/assets/engineerclient/ads/packs/). Counts only.

    @Test fun theJarCarriesAllSixFiles() {
        for (n in PackFiles.BUNDLED) {
            val bytes = BundledPacks.JAR.open(n)?.use { it.readBytes() }
            assertNotNull(bytes, "$n is not in the resources"); assertTrue(bytes.size > 1000, "$n is suspiciously small")
        }
    }

    @Test fun theRealBundledPacksBuildADungeonFromAnEmptyConfigFolder(@TempDir tmp: Path) {
        val (dir, cache) = dirs(tmp)
        val set = Packs.load(dir)
        assertTrue(set.canBuildDungeon, set.userLines().toString())
        assertNotNull(set.mobs); assertNotNull(set.runstate)
        assertTrue(set.rooms!!.designs.size > 100, "designs: ${set.rooms.designs.size}")
        assertTrue(set.layouts!!.pool().isNotEmpty())
        assertTrue(PackFiles.BUNDLED.all { set.source.origin(it) == PackOrigin.JAR })
        assertTrue(TrapTimings.load(set.source) !== TrapTimings.NONE, "traps.json was found and read")
        // A room volume decodes from the unpacked blocks file.
        val design = set.rooms.designs.first { it.vol != null }
        assertNotNull(set.rooms.volumeFor(design, null))
        assertEquals(1, hashFolders(cache).size)
    }
}
