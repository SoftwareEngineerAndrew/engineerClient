package com.engineerclient.adr

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test

/**
 * Packs saved runs the way an upload does, for looking at what goes up (and the viewer's reading
 * of it): ADR_PACK_IN is the runs (paths, ':' between), ADR_PACK_OUT the folder the packed ones go
 * in (<name>.xz), ADR_PACK_SIBLING a party member's upload to pack against, ADR_PACK_CAT a folder with
 * the site's catalogues (Catalogue). Nothing to do without them.
 */
class PackRunsTest {
    @Test fun packGiven() {
        val inputs = System.getenv("ADR_PACK_IN")?.split(':')?.filter { it.isNotEmpty() } ?: return
        val outDir = Path.of(System.getenv("ADR_PACK_OUT") ?: return)
        val sibling = System.getenv("ADR_PACK_SIBLING")?.let { Files.readAllBytes(Path.of(it)) }
        val cat = System.getenv("ADR_PACK_CAT")?.let { Catalogue.load(Path.of(it)) { null } }
        Files.createDirectories(outDir)
        for (input in inputs) {
            val file = Path.of(input)
            val t0 = System.nanoTime()
            val scan = UploadPacker.scan(file, false)
            val (packed, left) = UploadPacker.pack(file, scan, sibling, cat = cat)
            val dest = outDir.resolve(file.fileName.toString().removeSuffix(".gz").removeSuffix(".jsonl") + ".xz")
            Files.move(packed, dest, StandardCopyOption.REPLACE_EXISTING)
            Files.writeString(outDir.resolve("report.txt"), "packed ${file.fileName}: ${Files.size(file)} -> ${Files.size(dest)} bytes, $left mob lives left out, ${(System.nanoTime() - t0) / 1_000_000} ms\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }
    }
}
