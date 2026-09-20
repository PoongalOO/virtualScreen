package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Message client PointerEvent (SS-040) : vecteurs hexadécimaux exacts tirés de la spécification RFB (type 5). */
class PointerEventTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").let { h ->
        ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    @Test
    fun `PointerEvent is type 5, button mask, then x and y as big-endian U16`() {
        assertArrayEquals(hex("05 01 0280 0190"), ClientMessages.pointerEvent(PointerButtons.LEFT, 640, 400))
        assertEquals(6, ClientMessages.pointerEvent(0, 0, 0).size)
    }

    @Test
    fun `coordinates use both bytes and are not mixed up`() {
        // x = 0x0123, y = 0x0456 : un échange x/y ou un ordre d'octets inversé se verrait ici.
        assertArrayEquals(hex("05 00 0123 0456"), ClientMessages.pointerEvent(0, 0x0123, 0x0456))
    }

    @Test
    fun `extreme valid coordinates and masks are encoded unsigned`() {
        assertArrayEquals(hex("05 ff ffff ffff"), ClientMessages.pointerEvent(0xFF, 65535, 65535))
        assertArrayEquals(hex("05 00 0000 0000"), ClientMessages.pointerEvent(0, 0, 0))
        // 1279 et 799 : les derniers pixels d'un écran 1280x800
        assertArrayEquals(hex("05 00 04ff 031f"), ClientMessages.pointerEvent(0, 1279, 799))
    }

    @Test
    fun `button bits follow the RFB and X11 numbering`() {
        assertEquals(1, PointerButtons.LEFT)
        assertEquals(2, PointerButtons.MIDDLE)
        assertEquals(4, PointerButtons.RIGHT)
        assertEquals(8, PointerButtons.WHEEL_UP)
        assertEquals(16, PointerButtons.WHEEL_DOWN)
        assertArrayEquals(
            hex("05 05 0001 0002"),
            ClientMessages.pointerEvent(PointerButtons.LEFT or PointerButtons.RIGHT, 1, 2)
        )
    }

    @Test
    fun `out-of-range values are refused instead of truncated`() {
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(0, 65536, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(0, 0, 65536) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.pointerEvent(256, 0, 0) }
    }

    @Test
    fun `left click is hover then press then release, in one 18-byte array`() {
        val click = ClientMessages.leftClick(640, 400)

        assertEquals(ClientMessages.LEFT_CLICK_LENGTH, click.size)
        assertEquals(18, click.size)
        assertArrayEquals(
            hex("05 00 0280 0190   05 01 0280 0190   05 00 0280 0190"),
            click
        )
    }

    @Test
    fun `left click always ends with the button released`() {
        for ((x, y) in listOf(0 to 0, 1279 to 799, 65535 to 65535, 3 to 700)) {
            val click = ClientMessages.leftClick(x, y)
            assertEquals("premier : survol sans bouton", 0, click[1].toInt())
            assertEquals("deuxième : bouton gauche enfoncé", 1, click[7].toInt())
            assertEquals("dernier : tout relâché", 0, click[13].toInt())
            for (event in 0 until 3) assertEquals(5, click[event * 6].toInt())
        }
    }

    @Test
    fun `left click refuses out-of-range coordinates`() {
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.leftClick(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.leftClick(0, 65536) }
    }

    @Test(timeout = 10_000)
    fun `a click reaches the server as one intact message after the other client messages`() = LoopbackPair().use { p ->
        val request = ClientMessages.keepAliveRequest()
        val click = ClientMessages.leftClick(1279, 799)

        p.client.write(request, 0, request.size)
        p.client.write(click, 0, click.size)

        assertArrayEquals(request + click, p.receiveExactly(request.size + click.size))
    }
}
