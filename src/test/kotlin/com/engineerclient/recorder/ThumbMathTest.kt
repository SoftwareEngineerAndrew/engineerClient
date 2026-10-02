package com.engineerclient.recorder

import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Frame thumbnails' pure parts: the GPU downscale factor, sizes, paths, box scaling and JPEG output. */
class ThumbMathTest {

    @Test
    fun gpuFactorAlwaysDividesBothSides() {
        assertEquals(4, ThumbMath.gpuFactor(1920, 1080))
        assertEquals(2, ThumbMath.gpuFactor(1366, 768))
        assertEquals(3, ThumbMath.gpuFactor(1281, 723))
        assertEquals(1, ThumbMath.gpuFactor(1279, 721))
        for (w in 1..300) for (h in listOf(1, 2, 7, 480, 721)) {
            val f = ThumbMath.gpuFactor(w, h)
            assertTrue(w % f == 0 && h % f == 0, "$w x $h -> $f")
        }
    }

    @Test
    fun outSizeIsAQuarterNeverZero() {
        assertEquals(480 to 270, ThumbMath.outSize(1920, 1080))
        assertEquals(1 to 1, ThumbMath.outSize(3, 2))
    }

    @Test
    fun relPathIsPerPart() {
        assertEquals("thumbs/part0002/12345.jpg", ThumbMath.relPath(2, 12345))
    }

    @Test
    fun downscaleAveragesBoxesAndKeepsSameSize() {
        val same = IntArray(4) { it }
        assertSame(same, ThumbMath.downscale(same, 2, 2, 2, 2))
        // 4x2 -> 2x1: left box (red 0 and 200) and right box (blue 100 and 50)
        val src = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000064.toInt(), 0xFF000064.toInt(),
            0xFFC80000.toInt(), 0xFFC80000.toInt(), 0xFF000032.toInt(), 0xFF000032.toInt(),
        )
        assertContentEquals(intArrayOf(0xFF640000.toInt(), 0xFF00004B.toInt()), ThumbMath.downscale(src, 4, 2, 2, 1))
        // Non-integer ratios cover every output pixel.
        val big = IntArray(7 * 5) { 0xFF808080.toInt() }
        assertTrue(ThumbMath.downscale(big, 7, 5, 3, 2).all { it == 0xFF808080.toInt() })
    }

    @Test
    fun jpegDecodesToTheSameSizeAndColour() {
        val w = 32; val h = 16
        val px = IntArray(w * h) { 0xFF2060C0.toInt() }
        val bytes = ThumbMath.jpeg(px, w, h, ThumbCapture.QUALITY)
        assertEquals(0xFF.toByte(), bytes[0]); assertEquals(0xD8.toByte(), bytes[1])
        val img = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(w, img.width); assertEquals(h, img.height)
        val c = img.getRGB(10, 10)
        assertTrue(kotlin.math.abs(((c shr 16) and 0xFF) - 0x20) < 8 && kotlin.math.abs((c and 0xFF) - 0xC0) < 8, Integer.toHexString(c))
    }

    @Test
    fun writeIntoCreatesThePartDirectory() {
        val dir = Files.createTempDirectory("ecthumb")
        try {
            ThumbCapture.writeInto(dir, ThumbMath.relPath(1, 7), byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(dir.resolve("thumbs/part0001/7.jpg")))
        } finally {
            RecorderFiles.deleteRecursively(dir)
        }
    }
}
