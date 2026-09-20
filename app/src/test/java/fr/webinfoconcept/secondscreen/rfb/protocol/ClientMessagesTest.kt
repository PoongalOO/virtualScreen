package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages client SetPixelFormat (SS-021) et SetEncodings (SS-022), vecteurs hexadécimaux exacts. */
class ClientMessagesTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").let { h ->
        ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    // ------------------------------------------------------- SetPixelFormat

    @Test
    fun `SetPixelFormat of the client format matches the protocol byte for byte`() {
        val expected = hex(
            "00  000000 " +                                   // type 0, 3 octets de padding
                "20 18 00 01  00ff 00ff 00ff  10 08 00  000000" // PIXEL_FORMAT 32 bpp LE true-colour
        )

        assertArrayEquals(expected, ClientMessages.setPixelFormat())
        assertEquals(20, ClientMessages.setPixelFormat().size)
    }

    @Test
    fun `SetPixelFormat default is the documented client format`() {
        assertArrayEquals(
            ClientMessages.setPixelFormat(PixelFormat.XRGB_8888_LE),
            ClientMessages.setPixelFormat()
        )
    }

    @Test
    fun `SetPixelFormat carries an arbitrary valid format`() {
        val rgb565 = PixelFormat(16, 16, true, true, 31, 63, 31, 11, 5, 0)

        val message = ClientMessages.setPixelFormat(rgb565)

        assertArrayEquals(hex("00 000000  10 10 01 01  001f 003f 001f  0b 05 00  000000"), message)
    }

    @Test
    fun `SetPixelFormat embeds a PIXEL_FORMAT that parses back to the same format`() {
        for (format in listOf(PixelFormat.XRGB_8888_LE, PixelFormat(16, 16, true, true, 31, 63, 31, 11, 5, 0))) {
            val message = ClientMessages.setPixelFormat(format)
            assertEquals(format, PixelFormat.parse(message, 4))
        }
    }

    @Test
    fun `SetPixelFormat has type 0 and zero padding`() {
        val message = ClientMessages.setPixelFormat()

        assertEquals(ClientMessages.TYPE_SET_PIXEL_FORMAT, 0)
        assertEquals(0, message[0].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0), message.copyOfRange(1, 4))
        assertEquals(ClientMessages.SET_PIXEL_FORMAT_LENGTH, message.size)
    }

    @Test
    fun `SetPixelFormat refuses to send an invalid format`() {
        val invalid = PixelFormat(24, 24, false, true, 255, 255, 255, 16, 8, 0)

        assertThrows(IllegalArgumentException::class.java) { ClientMessages.setPixelFormat(invalid) }
    }

    // ---------------------------------------------------------- SetEncodings

    @Test
    fun `SetEncodings advertises exactly the encodings that have a decoder`() {
        // Piège volontaire : n'annoncer un encodage que lorsque son décodeur existe. Ce test doit être mis à
        // jour en même temps que Encoding.ADVERTISED et ServerMessageReader.defaultDecoders.
        // Compacts d'abord, RAW en dernier recours : Hextile (5), CopyRect (1), RAW (0).
        assertEquals(listOf(Encoding.HEXTILE, Encoding.COPY_RECT, Encoding.RAW), Encoding.ADVERTISED)
        assertArrayEquals(hex("02 00 0003  00000005  00000001  00000000"), ClientMessages.setEncodings())
    }

    @Test
    fun `SetEncodings keeps the preference order`() {
        val message = ClientMessages.setEncodings(listOf(Encoding.HEXTILE, Encoding.COPY_RECT, Encoding.RAW))

        assertArrayEquals(hex("02 00 0003  00000005  00000001  00000000"), message)
    }

    @Test
    fun `SetEncodings count and length follow the list`() {
        val message = ClientMessages.setEncodings(listOf(0, 1, 5))

        assertEquals(4 + 4 * 3, message.size)
        assertEquals(3, message.u16At(2))
        assertEquals(ClientMessages.TYPE_SET_ENCODINGS, message[0].toInt())
        assertEquals(0, message[1].toInt()) // padding
    }

    @Test
    fun `SetEncodings writes pseudo encodings as signed 32 bit`() {
        // Cursor = -239 (0xFFFFFF11), DesktopSize = -223 (0xFFFFFF21).
        assertArrayEquals(
            hex("02 00 0003  00000000  ffffff11  ffffff21"),
            ClientMessages.setEncodings(listOf(Encoding.RAW, -239, -223))
        )
        assertArrayEquals(hex("02 00 0002  80000000  7fffffff"), ClientMessages.setEncodings(listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
    }

    @Test
    fun `SetEncodings accepts the maximum number of encodings`() {
        val list = (0 until ClientMessages.MAX_ENCODINGS).toList()

        val message = ClientMessages.setEncodings(list)

        assertEquals(4 + 4 * ClientMessages.MAX_ENCODINGS, message.size)
        assertEquals(ClientMessages.MAX_ENCODINGS, message.u16At(2))
        assertEquals(63, message.u32At(4 + 4 * 63).toInt()) // dernier encodage intact
    }

    @Test
    fun `SetEncodings rejects an empty list, too many entries and duplicates`() {
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.setEncodings(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            ClientMessages.setEncodings((0..ClientMessages.MAX_ENCODINGS).toList()) // 65 entrées
        }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.setEncodings(listOf(0, 1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.setEncodings(listOf(5, 5)) }
    }

    @Test
    fun `encoding numbers match RFC 6143`() {
        assertEquals(0, Encoding.RAW)
        assertEquals(1, Encoding.COPY_RECT)
        assertEquals(5, Encoding.HEXTILE)
        assertTrue(Encoding.ADVERTISED.contains(Encoding.RAW)) // RAW doit toujours être annoncé
        assertEquals(Encoding.ADVERTISED.size, Encoding.ADVERTISED.toSet().size)
    }

    // ------------------------------------------------------------- général

    @Test
    fun `each call returns a fresh array`() {
        val first = ClientMessages.setEncodings()
        val expected = first.copyOf()
        first.fill(0x55)

        val second = ClientMessages.setEncodings()

        assertNotSame(first, second)
        assertArrayEquals(expected, second)
        assertNotSame(ClientMessages.setPixelFormat(), ClientMessages.setPixelFormat())
    }

    @Test(timeout = 10_000)
    fun `both messages reach the server intact and in order`() = LoopbackPair().use { p ->
        val pixelFormat = ClientMessages.setPixelFormat()
        val encodings = ClientMessages.setEncodings(listOf(Encoding.COPY_RECT, Encoding.RAW))

        p.client.write(pixelFormat, 0, pixelFormat.size)
        p.client.write(encodings, 0, encodings.size)

        assertArrayEquals(pixelFormat + encodings, p.receiveExactly(pixelFormat.size + encodings.size))
    }
}
