package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Message client KeyEvent (SS-046) : vecteurs hexadécimaux de la RFC 6143 §7.5.4 (type 4, flag, 2 octets de padding, keysym). */
class KeyEventTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").let { h ->
        ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    @Test
    fun `key down is type 4, flag 1, two padding bytes, then the keysym as a big-endian U32`() {
        assertArrayEquals(hex("04 01 0000 00000061"), ClientMessages.keyEvent(true, 'a'.code))
        assertEquals(8, ClientMessages.keyEvent(true, 'a'.code).size)
    }

    @Test
    fun `key up has flag 0`() {
        assertArrayEquals(hex("04 00 0000 00000061"), ClientMessages.keyEvent(false, 'a'.code))
    }

    @Test
    fun `padding bytes are always zero`() {
        val m = ClientMessages.keyEvent(true, 0x0110FFFF)
        assertEquals(0, m[2].toInt())
        assertEquals(0, m[3].toInt())
    }

    @Test
    fun `keysyms use all four bytes, unsigned, big-endian`() {
        assertArrayEquals(hex("04 01 0000 0000ff0d"), ClientMessages.keyEvent(true, 0xFF0D))     // Return
        assertArrayEquals(hex("04 01 0000 0000ffff"), ClientMessages.keyEvent(true, 0xFFFF))     // Delete
        assertArrayEquals(hex("04 01 0000 010020ac"), ClientMessages.keyEvent(true, 0x010020AC)) // euro, keysym Unicode
        assertArrayEquals(hex("04 01 0000 0110ffff"), ClientMessages.keyEvent(true, 0x0110FFFF)) // dernier point de code
        assertArrayEquals(hex("04 01 0000 7fffffff"), ClientMessages.keyEvent(true, Int.MAX_VALUE))
    }

    @Test
    fun `a zero or negative keysym is refused, never sent`() {
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyEvent(true, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyEvent(false, -1) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyPress(0) }
    }

    @Test
    fun `a key press is down then up of the same keysym in one 16-byte array`() {
        val press = ClientMessages.keyPress('a'.code)

        assertEquals(ClientMessages.KEY_PRESS_LENGTH, press.size)
        assertArrayEquals(hex("04 01 0000 00000061   04 00 0000 00000061"), press)
    }

    @Test
    fun `several key presses are pairs in order, one array, 16 bytes each`() {
        val message = ClientMessages.keyPresses(intArrayOf('h'.code, 'i'.code, 0xFF0D))

        assertEquals(48, message.size)
        assertArrayEquals(
            hex("04 01 0000 00000068  04 00 0000 00000068   04 01 0000 00000069  04 00 0000 00000069   04 01 0000 0000ff0d  04 00 0000 0000ff0d"),
            message
        )
    }

    @Test
    fun `every key press ends with the key released - no key is ever left down by a message`() {
        val message = ClientMessages.keyPresses(IntArray(ClientMessages.MAX_KEY_PRESSES) { 0x20 + it })
        for (i in message.indices step 16) {
            assertEquals(1, message[i + 1].toInt())
            assertEquals(0, message[i + 9].toInt())
            for (b in 4..7) assertEquals("même keysym", message[i + b], message[i + 8 + b])
        }
    }

    @Test
    fun `the number of key presses per message is bounded`() {
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyPresses(IntArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyPresses(IntArray(ClientMessages.MAX_KEY_PRESSES + 1) { 'a'.code }) }
        assertThrows(IllegalArgumentException::class.java) { ClientMessages.keyPresses(intArrayOf('a'.code, 0)) }
    }

    @Test(timeout = 10_000)
    fun `key messages reach the server intact and in order between other client messages`() = LoopbackPair().use { p ->
        val pointer = ClientMessages.leftClick(10, 20)
        val keys = ClientMessages.keyPresses(intArrayOf('o'.code, 'k'.code))

        p.client.write(pointer, 0, pointer.size)
        p.client.write(keys, 0, keys.size)
        p.client.write(pointer, 0, pointer.size)

        assertArrayEquals(pointer + keys + pointer, p.receiveExactly(pointer.size * 2 + keys.size))
    }
}
