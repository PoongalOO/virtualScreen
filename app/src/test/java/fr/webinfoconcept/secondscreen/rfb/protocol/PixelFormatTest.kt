package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Décodage et validation de PIXEL_FORMAT (fixtures déterministes, aucun réseau). */
class PixelFormatTest {

    companion object {
        /** Construit un PIXEL_FORMAT de 16 octets tel qu'il circule sur le fil. */
        fun wire(
            bpp: Int, depth: Int, bigEndian: Int, trueColour: Int,
            redMax: Int, greenMax: Int, blueMax: Int,
            redShift: Int, greenShift: Int, blueShift: Int
        ): ByteArray = byteArrayOf(
            bpp.toByte(), depth.toByte(), bigEndian.toByte(), trueColour.toByte(),
            (redMax shr 8).toByte(), redMax.toByte(),
            (greenMax shr 8).toByte(), greenMax.toByte(),
            (blueMax shr 8).toByte(), blueMax.toByte(),
            redShift.toByte(), greenShift.toByte(), blueShift.toByte(),
            0, 0, 0
        )

        /** 32 bpp, depth 24, little-endian, R=16 G=8 B=0 : ce qu'envoient TigerVNC, x11vnc, TightVNC. */
        val BGRX_32 = wire(32, 24, 0, 1, 255, 255, 255, 16, 8, 0)
    }

    private fun assertInvalid(detail: String, bytes: ByteArray) {
        val e = assertThrows(RfbProtocolException.InvalidPixelFormat::class.java) { PixelFormat.parse(bytes) }
        assertEquals(detail, e.detail)
    }

    // --- formats valides ---

    @Test
    fun `parses the common 32 bit true colour format`() {
        val f = PixelFormat.parse(BGRX_32)

        assertEquals(PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0), f)
        assertEquals(4, f.bytesPerPixel)
    }

    @Test
    fun `parses RGB565`() {
        val f = PixelFormat.parse(wire(16, 16, 0, 1, 31, 63, 31, 11, 5, 0))

        assertEquals(31, f.redMax)
        assertEquals(63, f.greenMax)
        assertEquals(11, f.redShift)
        assertEquals(2, f.bytesPerPixel)
    }

    @Test
    fun `parses BGR233`() {
        val f = PixelFormat.parse(wire(8, 8, 0, 1, 7, 7, 3, 0, 3, 6))

        assertEquals(3, f.blueMax)
        assertEquals(6, f.blueShift)
        assertEquals(1, f.bytesPerPixel)
    }

    @Test
    fun `big endian and true colour flags are any non zero value`() {
        val f = PixelFormat.parse(wire(32, 24, 2, 0x80, 255, 255, 255, 16, 8, 0))

        assertTrue(f.bigEndian)
        assertTrue(f.trueColour)
        assertFalse(PixelFormat.parse(BGRX_32).bigEndian)
    }

    @Test
    fun `colour map format does not validate maxima and shifts`() {
        // Palette : maxima/décalages sans signification, même absurdes.
        val f = PixelFormat.parse(wire(8, 8, 0, 0, 12345, 0, 254, 200, 255, 99))

        assertFalse(f.trueColour)
        assertEquals(1, f.bytesPerPixel)
    }

    @Test
    fun `colour map format still needs a valid bits per pixel and depth`() {
        assertInvalid("bits-per-pixel", wire(12, 8, 0, 0, 0, 0, 0, 0, 0, 0))
        assertInvalid("depth", wire(8, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `honours the offset and ignores the padding`() {
        val padded = byteArrayOf(9, 9, 9) + wire(32, 24, 0, 1, 255, 255, 255, 16, 8, 0).also {
            it[13] = 0x55; it[14] = 0x66; it[15] = 0x77 // padding quelconque
        }

        assertEquals(PixelFormat.parse(BGRX_32), PixelFormat.parse(padded, 3))
    }

    // --- formats invalides ---

    @Test
    fun `rejects unsupported bits per pixel`() {
        for (bpp in listOf(0, 1, 7, 12, 15, 24, 31, 33, 64, 128, 255)) {
            assertInvalid("bits-per-pixel", wire(bpp, 8, 0, 1, 255, 255, 255, 16, 8, 0))
        }
    }

    @Test
    fun `rejects depth outside 1 to bits per pixel`() {
        assertInvalid("depth", wire(32, 0, 0, 1, 255, 255, 255, 16, 8, 0))
        assertInvalid("depth", wire(32, 33, 0, 1, 255, 255, 255, 16, 8, 0))
        assertInvalid("depth", wire(16, 24, 0, 1, 31, 63, 31, 11, 5, 0))
        assertInvalid("depth", wire(32, 255, 0, 1, 255, 255, 255, 16, 8, 0))
    }

    @Test
    fun `rejects a maximum that is not 2 to the n minus 1`() {
        for (bad in listOf(0, 2, 6, 254, 256, 300, 0x8000, 0xFFFE)) {
            assertInvalid("composante rouge", wire(32, 24, 0, 1, bad, 255, 255, 16, 8, 0))
            assertInvalid("composante verte", wire(32, 24, 0, 1, 255, bad, 255, 16, 8, 0))
            assertInvalid("composante bleue", wire(32, 24, 0, 1, 255, 255, bad, 16, 8, 0))
        }
    }

    @Test
    fun `maximum is read unsigned`() {
        // 0xFFFF = 65535 = 2^16 - 1 : forme valide, mais 16 bits ne tiennent pas dans un pixel de 8 bits.
        assertInvalid("composante rouge", wire(8, 8, 0, 1, 0xFFFF, 7, 3, 0, 3, 6))
        // En 32 bpp : rouge sur 16 bits (bits 0..15), bleu 16..23, vert 24..31.
        val f = PixelFormat.parse(wire(32, 32, 0, 1, 0xFFFF, 255, 255, 0, 24, 16))
        assertEquals(65535, f.redMax)
    }

    @Test
    fun `rejects a component that does not fit in the pixel`() {
        assertInvalid("composante rouge", wire(32, 24, 0, 1, 255, 255, 255, 25, 8, 0)) // 25 + 8 > 32
        assertInvalid("composante bleue", wire(16, 16, 0, 1, 31, 63, 31, 11, 5, 12))   // 12 + 5 > 16
        assertInvalid("composante rouge", wire(32, 24, 0, 1, 255, 255, 255, 255, 8, 0)) // shift 255 non signé
        assertInvalid("composante verte", wire(8, 8, 0, 1, 7, 7, 3, 0, 6, 6))          // 6 + 3 > 8
    }

    @Test
    fun `rejects overlapping components`() {
        assertInvalid("composantes qui se chevauchent", wire(32, 24, 0, 1, 255, 255, 255, 0, 0, 16))
        assertInvalid("composantes qui se chevauchent", wire(32, 24, 0, 1, 255, 255, 255, 16, 8, 4))
        assertInvalid("composantes qui se chevauchent", wire(16, 16, 0, 1, 31, 63, 31, 11, 5, 4))
    }

    @Test
    fun `adjacent components are not overlapping`() {
        PixelFormat.parse(wire(32, 24, 0, 1, 255, 255, 255, 24, 16, 8)) // R en haut, pas de chevauchement
        PixelFormat.parse(wire(8, 8, 0, 1, 7, 7, 3, 5, 2, 0))           // RGB332
    }

    @Test
    fun `wrong buffer size is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.parse(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.parse(BGRX_32, 1) }
        assertThrows(IllegalArgumentException::class.java) { PixelFormat.parse(BGRX_32, -1) }
    }

    @Test
    fun `invalid format message never contains server data`() {
        val e = assertThrows(RfbProtocolException.InvalidPixelFormat::class.java) {
            PixelFormat.parse(wire(99, 8, 0, 1, 255, 255, 255, 16, 8, 0))
        }
        assertFalse(e.message!!.contains("99"))
    }
}
