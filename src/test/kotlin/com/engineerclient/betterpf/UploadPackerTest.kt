package com.engineerclient.betterpf

import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadPackerTest {

    private fun gz(lines: List<String>): ByteArray = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(lines.joinToString("\n").toByteArray()) } }.toByteArray()

    // Mob 1: both saw it, the sibling more. Mob 2: only this recording. Mob 3: both, this one more.
    private val mine = listOf(
        """{"k":"meta","self":"a"}""",
        """{"k":"spawn","t":1,"id":1,"type":"minecraft:zombie"}""",
        """{"k":"spawn","t":1,"id":2,"type":"minecraft:skeleton"}""",
        """{"k":"spawn","t":1,"id":3,"type":"minecraft:zombie"}""",
        """{"k":"e","t":2,"d":[[1,0,0,0,0,0],[2,1,1,1,0,0],[3,2,2,2,0,0]]}""",
        """{"k":"e","t":3,"d":[[3,2,2,3,0,0]]}""",
        """{"k":"p","t":2,"d":[["b",1,2,3,0,0,"",4,"",0]]}""",
        """{"k":"gone","t":4,"id":1}""",
    )
    private val sibling = listOf(
        """{"k":"spawn","t":1,"id":1,"type":"minecraft:zombie"}""",
        """{"k":"e","t":2,"d":[[1,0,0,0,0,0]]}""",
        """{"k":"e","t":3,"d":[[1,0,0,1,0,0]]}""",
        """{"k":"gone","t":4,"id":1}""",
        """{"k":"spawn","t":1,"id":3,"type":"minecraft:zombie"}""",
    )

    private fun unxz(path: java.nio.file.Path) = XZInputStream(Files.newInputStream(path)).readBytes().toString(Charsets.UTF_8).trim().lines()

    @Test
    fun `without a sibling it is the same lines, as xz`() {
        val file = Files.createTempFile("run", ".jsonl.gz").also { Files.write(it, gz(mine)) }
        val (out, dropped) = UploadPacker.pack(file, null)
        assertEquals(0, dropped)
        assertEquals(mine, unxz(out))
    }

    @Test
    fun `mobs the sibling has as much of are left out, everything else kept`() {
        val file = Files.createTempFile("run", ".jsonl.gz").also { Files.write(it, gz(mine)) }
        val (out, dropped) = UploadPacker.pack(file, gz(sibling))
        val lines = unxz(out)
        assertEquals(1, dropped)                                         // mob 1 only
        assertFalse(lines.any { it.contains("\"id\":1,") || it.contains("\"id\":1}") })
        assertTrue(lines.any { it.contains("\"id\":2,") })              // only this recording saw it
        assertTrue(lines.any { it.contains("\"id\":3,") })              // this recording saw it more
        assertTrue(lines.contains("""{"k":"e","t":2,"d":[[2,1,1,1,0,0],[3,2,2,2,0,0]]}"""))
        assertTrue(lines.any { it.startsWith("""{"k":"p"""") })         // players always kept
        assertTrue(lines.any { it.startsWith("""{"k":"meta"""") })
    }
}
