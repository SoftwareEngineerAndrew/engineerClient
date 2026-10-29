package com.engineerclient.dungeonpack.pack

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/*
 * Where each data pack file comes from. The jar bundles all six (BRIEF decision 7); a copy of the same name in the packs folder
 * under the game's config folder wins, so a developer can try a pack without rebuilding the mod. The loaders read real files
 * (the room blocks are read by offset), so the bundled files are unpacked once into a cache folder named by their content hash
 * and reused until the jar's data changes. Nothing here touches Minecraft.
 */

/** Which copy of a pack file a [PackSource] answered with. */
enum class PackOrigin { OVERRIDE, JAR }

/** The files a jar bundles. [open] gives a fresh stream of one file, or null when it is not bundled. */
interface BundledPacks {
    fun open(name: String): InputStream?

    companion object {
        /** The data packs inside the running mod jar (`assets/engineerclient/dungeonpack/packs/`). */
        val JAR: BundledPacks = object : BundledPacks {
            override fun open(name: String): InputStream? = BundledPacks::class.java.classLoader.getResourceAsStream("${PackFiles.RESOURCE_ROOT}/$name")
        }

        /** Nothing bundled (tests of the override folder alone). */
        val NONE: BundledPacks = object : BundledPacks {
            override fun open(name: String): InputStream? = null
        }
    }
}

/**
 * The resolved location of every pack file of one load. [file] answers a path the loaders can open, or null when there is no
 * such file anywhere; [origin] says which copy it is.
 */
class PackSource private constructor(
    /** The packs folder whose files override the bundled ones (null for a source built from nothing). */
    val overrideDir: Path?,
    private val resolved: Map<String, Pair<Path, PackOrigin>>,
    /** Where the bundled files were unpacked, or null when none had to be. */
    val cacheDir: Path?,
    /** True when the jar's copies were consulted (so "missing" means missing from the jar too). */
    private val bundledLooked: Boolean = false,
    /** The cache folder the bundled files could not be unpacked into (read-only folder, full disk), or null when unpacking worked or was not needed. */
    val unpackFailedIn: Path? = null,
) {
    /** The path of [name] to read, or null. A file the bundle does not carry (an optional extra) is looked up in the override folder alone. */
    fun file(name: String): Path? =
        resolved[name]?.first ?: overrideDir?.resolve(name)?.takeIf { Files.isRegularFile(it) }

    fun origin(name: String): PackOrigin? = resolved[name]?.second ?: if (overrideDir?.resolve(name)?.let { Files.isRegularFile(it) } == true) PackOrigin.OVERRIDE else null

    /** For "X is missing from ...": the places a file was looked for. */
    val missingFrom: String get() = if (bundledLooked) "the jar and $overrideDir" else "$overrideDir"

    /** For "X is unreadable...": a note on which copy failed, empty when it is the plain file in the folder. */
    fun note(name: String): String = if (origin(name) == PackOrigin.JAR) " (the copy bundled in the jar)" else ""

    companion object {
        /** Every file read straight from [dir], as the loaders always did (tests, tools). */
        fun ofDir(dir: Path): PackSource = PackSource(dir, emptyMap(), null)

        /**
         * Resolves [names]: a file in [overrideDir] wins; the others come from [bundled], unpacked into a sub-folder of [cacheRoot]
         * named by the hash of all the bundled content (found there again on the next load, older hash folders removed). Never throws:
         * a bundled file that cannot be read or unpacked is simply absent from the result, with the reason in [report].
         */
        fun resolve(
            overrideDir: Path, bundled: BundledPacks, cacheRoot: Path, report: PackReport,
            names: List<String> = PackFiles.BUNDLED,
        ): PackSource {
            val found = LinkedHashMap<String, Pair<Path, PackOrigin>>()
            for (n in names) {
                val p = overrideDir.resolve(n)
                if (Files.isRegularFile(p)) found[n] = p to PackOrigin.OVERRIDE
            }
            val need = names.filter { it !in found }
            var cache: Path? = null
            var failedIn: Path? = null
            if (need.isNotEmpty()) {
                try {
                    cache = unpack(bundled, names, need, cacheRoot, report)?.also { dir -> for (n in need) if (Files.isRegularFile(dir.resolve(n))) found[n] = dir.resolve(n) to PackOrigin.JAR }
                } catch (e: Exception) {
                    // The exception's own message is often only the path (a file-system exception), so the class name says what went wrong.
                    report.error("ADS could not unpack its bundled data packs into $cacheRoot: ${e.javaClass.simpleName}: ${e.message ?: "no details"}. Check that this folder is writable and the disk is not full.")
                    failedIn = cacheRoot
                }
            }
            return PackSource(overrideDir, found, cache, bundledLooked = bundled !== BundledPacks.NONE, unpackFailedIn = failedIn)
        }

        /** The cache folder holding every file of [need] (null when the bundle has none of them at all). */
        private fun unpack(bundled: BundledPacks, all: List<String>, need: List<String>, cacheRoot: Path, report: PackReport): Path? {
            // The folder's name is the hash of every bundled file (name, size, SHA-256), so a rebuilt pack gets a fresh folder.
            val sizes = HashMap<String, Long>()
            val hashes = HashMap<String, String>()
            val outer = MessageDigest.getInstance("SHA-256")
            for (n in all) {
                val (size, hex) = (bundled.open(n) ?: continue).use { digest(it) }
                sizes[n] = size
                hashes[n] = hex
                outer.update("$n\n$size\n$hex\n".toByteArray())
            }
            if (need.none { it in sizes }) return null
            val id = outer.digest().joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
            val dir = cacheRoot.resolve(id)
            Files.createDirectories(dir)
            for (n in need) {
                val size = sizes[n] ?: continue
                val hex = hashes.getValue(n)
                val target = dir.resolve(n)
                if (isIntact(target, size, hex)) continue   // unpacked by an earlier load, and still the jar's bytes
                val tmp = Files.createTempFile(dir, n, ".part")
                try {
                    (bundled.open(n) ?: throw IOException("$n vanished from the jar")).use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                    if (Files.size(tmp) != size) throw IOException("$n was unpacked with the wrong size")
                    place(tmp, target, size, hex)
                } finally { Files.deleteIfExists(tmp) }
            }
            removeOlder(cacheRoot, id, report)
            return dir
        }

        /**
         * Puts the finished [tmp] at [target] in one step, so another game unpacking the same jar never finds the file missing. A
         * plain "replace existing" move deletes the old file first and leaves a gap; a rename does not. If the rename is refused
         * but the file already there is the jar's copy ([isIntact]), another game got there first and its copy is the same file.
         */
        private fun place(tmp: Path, target: Path, size: Long, sha256: String) {
            try {
                try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, target) }
            } catch (e: IOException) {
                if (!isIntact(target, size, sha256)) throw e
            }
        }

        /**
         * True when [file] is a regular file with the jar's size and SHA-256. The size alone would keep a damaged file of the same
         * length for ever; the hash is of about 3.5 MB in all, so checking it on each load is cheap.
         */
        private fun isIntact(file: Path, size: Long, sha256: String): Boolean = try {
            Files.isRegularFile(file) && Files.size(file) == size && Files.newInputStream(file).use { digest(it) }.second == sha256
        } catch (_: IOException) { false }

        /** Deletes the hash folders of earlier versions (best effort: a folder another game still uses just stays until it is free). */
        private fun removeOlder(cacheRoot: Path, keep: String, report: PackReport) {
            try {
                Files.list(cacheRoot).use { s ->
                    for (d in s.toList()) {
                        val name = d.fileName.toString()
                        if (name == keep || !Files.isDirectory(d) || name.length != HASH_CHARS || !name.all { it in "0123456789abcdef" }) continue
                        try { d.toFile().deleteRecursively() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { report.info("Old unpacked data packs in $cacheRoot were not cleaned up: ${e.message}") }
        }

        private fun digest(input: InputStream): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n }
            return total to md.digest().joinToString("") { "%02x".format(it) }
        }

        private const val HASH_CHARS = 16
    }
}
