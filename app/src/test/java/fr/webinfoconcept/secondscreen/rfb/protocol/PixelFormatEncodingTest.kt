package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random

/**
 * Format de pixels imposé par le client (SS-021) : sérialisation `SetPixelFormat` et conversion de
 * référence vers ARGB. Fixtures hexadécimales écrites à la main, indépendantes du code testé.
 */
class PixelFormatEncodingTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").let { h ->
        ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun argb(hexValue: Long): Int = hexValue.toInt()

    /** Le format imposé, sur le fil : 32 bpp, depth 24, LE, true-colour, max 255 x3, shifts 16/8/0. */
    private val canonicalWire = "20 18 00 01  00ff 00ff 00ff  10 08 00  000000"

    private val rgb565 = PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0)
    private val bgr233 = PixelFormat(8, 8, false, true, 7, 7, 3, 0, 3, 6)

    // ---------------------------------------------- le format imposé (documenté)

    @Test
    fun `the client format is 32 bit little endian true colour xRGB`() {
        val f = PixelFormat.XRGB_8888_LE

        assertEquals(32, f.bitsPerPixel)
        assertEquals(24, f.depth)
        assertEquals(false, f.bigEndian)
        assertEquals(true, f.trueColour)
        assertEquals(listOf(255, 255, 255), listOf(f.redMax, f.greenMax, f.blueMax))
        assertEquals(listOf(16, 8, 0), listOf(f.redShift, f.greenShift, f.blueShift))
        assertEquals(4, f.bytesPerPixel)
    }

    @Test
    fun `the client format is serialized exactly as documented`() {
        val out = ByteArray(PixelFormat.WIRE_SIZE)

        PixelFormat.XRGB_8888_LE.writeTo(out)

        assertArrayEquals(hex(canonicalWire), out)
        assertEquals(PixelFormat.XRGB_8888_LE, PixelFormat.parse(hex(canonicalWire)))
    }

    @Test
    fun `documented byte order is blue green red unused`() {
        // Sur le fil : [B, G, R, X]. Lu en U32 little-endian : 0x00RRGGBB.
        val f = PixelFormat.XRGB_8888_LE

        assertEquals(argb(0xFF112233), f.decodePixel(bytes(0x33, 0x22, 0x11, 0x00)))
        assertEquals(argb(0xFFFF0000), f.decodePixel(bytes(0x00, 0x00, 0xFF, 0x00))) // rouge seul : 3e octet
        assertEquals(argb(0xFF00FF00), f.decodePixel(bytes(0x00, 0xFF, 0x00, 0x00))) // vert seul : 2e octet
        assertEquals(argb(0xFF0000FF), f.decodePixel(bytes(0xFF, 0x00, 0x00, 0x00))) // bleu seul : 1er octet
    }

    @Test
    fun `the unused byte is ignored and alpha is always opaque`() {
        val f = PixelFormat.XRGB_8888_LE

        assertEquals(f.decodePixel(bytes(0x33, 0x22, 0x11, 0x00)), f.decodePixel(bytes(0x33, 0x22, 0x11, 0xAB)))
        assertEquals(f.decodePixel(bytes(0x33, 0x22, 0x11, 0x00)), f.decodePixel(bytes(0x33, 0x22, 0x11, 0xFF)))
        assertEquals(argb(0xFF000000), f.decodePixel(bytes(0, 0, 0, 0)))
        assertEquals(argb(0xFFFFFFFF), f.decodePixel(bytes(0xFF, 0xFF, 0xFF, 0x00)))
        assertEquals(argb(0xFFFFFFFF), f.decodePixel(bytes(0xFF, 0xFF, 0xFF, 0xFF))) // octets de poids fort : pas de signe
    }

    @Test
    fun `for the client format decoding is opaque alpha or the little endian value`() {
        // Chemin rapide attendu pour RAW (SS-024) : 0xFF000000 | U32 little-endian. La conversion de
        // référence doit lui être strictement égale, sur des pixels quelconques.
        val f = PixelFormat.XRGB_8888_LE
        val random = Random(42)
        repeat(2_000) {
            val px = ByteArray(4).also { random.nextBytes(it) }
            val littleEndian = (px[0].toInt() and 0xFF) or ((px[1].toInt() and 0xFF) shl 8) or
                ((px[2].toInt() and 0xFF) shl 16)
            assertEquals(0xFF000000.toInt() or littleEndian, f.decodePixel(px))
        }
    }

    @Test
    fun `decodePixel honours the offset`() {
        val buffer = bytes(9, 9, 0x33, 0x22, 0x11, 0x00, 9)

        assertEquals(argb(0xFF112233), PixelFormat.XRGB_8888_LE.decodePixel(buffer, 2))
    }

    // ------------------------------------------------- autres formats (référence)

    @Test
    fun `big endian variant reads the bytes the other way round`() {
        val be = PixelFormat.XRGB_8888_LE.copy(bigEndian = true)

        // [X, R, G, B]
        assertEquals(argb(0xFF112233), be.decodePixel(bytes(0x00, 0x11, 0x22, 0x33)))
        assertEquals(argb(0xFF112233), be.decodePixel(bytes(0xEE, 0x11, 0x22, 0x33)))
    }

    @Test
    fun `RGB565 little endian`() {
        assertEquals(argb(0xFFFF0000), rgb565.decodePixel(bytes(0x00, 0xF8))) // 0xF800
        assertEquals(argb(0xFF00FF00), rgb565.decodePixel(bytes(0xE0, 0x07))) // 0x07E0
        assertEquals(argb(0xFF0000FF), rgb565.decodePixel(bytes(0x1F, 0x00))) // 0x001F
        assertEquals(argb(0xFFFFFFFF), rgb565.decodePixel(bytes(0xFF, 0xFF)))
        assertEquals(argb(0xFF000000), rgb565.decodePixel(bytes(0x00, 0x00)))
    }

    @Test
    fun `RGB565 big endian`() {
        val be = rgb565.copy(bigEndian = true)

        assertEquals(argb(0xFFFF0000), be.decodePixel(bytes(0xF8, 0x00)))
        assertEquals(argb(0xFF00FF00), be.decodePixel(bytes(0x07, 0xE0)))
        assertEquals(argb(0xFF0000FF), be.decodePixel(bytes(0x00, 0x1F)))
    }

    @Test
    fun `components narrower than 8 bits are scaled with rounding`() {
        // rouge 16/31 -> (16*255 + 15) / 31 = 132 ; vert 32/63 -> (32*255 + 31) / 63 = 130
        assertEquals(argb(0xFF840000), rgb565.decodePixel(bytes(0x00, 0x80)))     // 16 << 11
        assertEquals(argb(0xFF008200), rgb565.decodePixel(bytes(0x00, 0x04)))     // 32 << 5
    }

    @Test
    fun `BGR233 one byte format`() {
        assertEquals(argb(0xFFFF0000), bgr233.decodePixel(bytes(0x07)))          // rouge max
        assertEquals(argb(0xFF00FF00), bgr233.decodePixel(bytes(0x38)))          // 7 << 3
        assertEquals(argb(0xFF0000FF), bgr233.decodePixel(bytes(0xC0)))          // 3 << 6
        assertEquals(argb(0xFF920000), bgr233.decodePixel(bytes(0x04)))          // 4/7 -> (4*255+3)/7 = 146
    }

    @Test
    fun `scaling maps zero to zero and maximum to 255 and never decreases`() {
        for (max in listOf(1, 3, 7, 15, 31, 63, 255)) {
            val f = PixelFormat(32, 24, false, true, max, max, max, 0, 8, 16)
            var previous = -1
            for (v in 0..max) {
                val red = (f.decodePixel(bytes(v, 0, 0, 0)) shr 16) and 0xFF
                assertTrue("max=$max v=$v : $red < $previous", red >= previous)
                previous = red
                if (v == 0) assertEquals("max=$max", 0, red)
                if (v == max) assertEquals("max=$max", 255, red)
            }
        }
    }

    @Test
    fun `a palette format has no direct conversion`() {
        val palette = PixelFormat(8, 8, false, false, 0, 0, 0, 0, 0, 0)

        assertThrows(IllegalStateException::class.java) { palette.decodePixel(bytes(1)) }
    }

    @Test
    fun `decodePixel rejects a truncated pixel`() {
        val f = PixelFormat.XRGB_8888_LE

        assertThrows(IllegalArgumentException::class.java) { f.decodePixel(bytes(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { f.decodePixel(bytes(1, 2, 3, 4), 1) }
        assertThrows(IllegalArgumentException::class.java) { f.decodePixel(bytes(1, 2, 3, 4), -1) }
    }

    // --------------------------------------------------------- writeTo

    @Test
    fun `serializes other formats`() {
        fun wire(f: PixelFormat) = ByteArray(16).also { f.writeTo(it) }

        assertArrayEquals(hex("10 10 00 01  001f 003f 001f  0b 05 00  000000"), wire(rgb565))
        assertArrayEquals(hex("10 10 01 01  001f 003f 001f  0b 05 00  000000"), wire(rgb565.copy(bigEndian = true)))
        assertArrayEquals(hex("08 08 00 01  0007 0007 0003  00 03 06  000000"), wire(bgr233))
    }

    @Test
    fun `round trips through parse`() {
        val formats = listOf(
            PixelFormat.XRGB_8888_LE, PixelFormat.XRGB_8888_LE.copy(bigEndian = true), rgb565,
            rgb565.copy(bigEndian = true), bgr233,
            PixelFormat(32, 24, false, true, 255, 255, 255, 24, 16, 8),
            PixelFormat(8, 8, false, false, 0, 0, 0, 0, 0, 0) // palette
        )
        for (f in formats) {
            val out = ByteArray(16)
            f.writeTo(out)
            assertEquals(f, PixelFormat.parse(out))
        }
    }

    @Test
    fun `writes only its own 16 bytes and zeroes the padding`() {
        val buffer = ByteArray(20) { 0xAA.toByte() }

        PixelFormat.XRGB_8888_LE.writeTo(buffer, 2)

        assertArrayEquals(bytes(0xAA, 0xAA), buffer.copyOfRange(0, 2))    // avant : intact
        assertArrayEquals(bytes(0xAA, 0xAA), buffer.copyOfRange(18, 20))  // après : intact
        assertArrayEquals(hex(canonicalWire), buffer.copyOfRange(2, 18))  // padding écrasé par des zéros
    }

    @Test
    fun `never serializes an invalid format`() {
        val invalid = listOf(
            PixelFormat(24, 24, false, true, 255, 255, 255, 16, 8, 0),      // 24 bpp
            PixelFormat(32, 0, false, true, 255, 255, 255, 16, 8, 0),       // depth 0
            PixelFormat(32, 24, false, true, 254, 255, 255, 16, 8, 0),      // max pas 2^n-1
            PixelFormat(32, 24, false, true, 255, 255, 255, 0, 0, 16),      // chevauchement
            PixelFormat(32, 24, false, true, 255, 255, 255, 25, 8, 0)       // dépasse le pixel
        )
        for (f in invalid) {
            val out = ByteArray(16)
            assertThrows("$f", IllegalArgumentException::class.java) { f.writeTo(out) }
            assertTrue("rien n'est écrit", out.all { it == 0.toByte() })
        }
    }

    @Test
    fun `values that do not fit their field are rejected not truncated`() {
        val palette = PixelFormat(8, 8, false, false, 70_000, 0, 0, 0, 0, 0) // U16 dépassé
        val shift = PixelFormat(8, 8, false, false, 0, 0, 0, 300, 0, 0)       // U8 dépassé

        assertThrows(IllegalArgumentException::class.java) { palette.writeTo(ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { shift.writeTo(ByteArray(16)) }
    }

    @Test
    fun `writeTo rejects a buffer that is too small`() {
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.XRGB_8888_LE.writeTo(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.XRGB_8888_LE.writeTo(ByteArray(16), 1) }
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.XRGB_8888_LE.writeTo(ByteArray(16), -1) }
    }
}
