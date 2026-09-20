package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Lecture non signée big-endian et assainissement du texte serveur (fixtures déterministes). */
class RfbBytesTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `u8At is unsigned`() {
        val b = bytes(0x00, 0x7F, 0x80, 0xFF)
        assertEquals(listOf(0, 127, 128, 255), (0..3).map { b.u8At(it) })
    }

    @Test
    fun `u16At is big endian and unsigned`() {
        val b = bytes(0x00, 0x01, 0x12, 0x34, 0x80, 0x00, 0xFF, 0xFF)
        assertEquals(1, b.u16At(0))
        assertEquals(0x1234, b.u16At(2))   // l'ordre des octets compte
        assertEquals(32768, b.u16At(4))    // négatif si lu en Short signé
        assertEquals(65535, b.u16At(6))
    }

    @Test
    fun `u32At is big endian and unsigned`() {
        val b = bytes(0x00, 0x00, 0x00, 0x01, 0x12, 0x34, 0x56, 0x78, 0x80, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
        assertEquals(1L, b.u32At(0))
        assertEquals(0x12345678L, b.u32At(4))
        assertEquals(2147483648L, b.u32At(8))
        assertEquals(4294967295L, b.u32At(12))
    }

    @Test
    fun `readers honour the offset`() {
        val b = bytes(0xAA, 0x00, 0x05, 0xBB)
        assertEquals(5, b.u16At(1))
    }

    // --- écriture (messages client) ---

    @Test
    fun `putU8At writes the byte and rejects out of range values`() {
        val b = ByteArray(3)
        b.putU8At(1, 0xFF)

        assertEquals(listOf(0, 255, 0), b.map { it.toInt() and 0xFF })
        assertThrows(IllegalArgumentException::class.java) { b.putU8At(0, 256) }
        assertThrows(IllegalArgumentException::class.java) { b.putU8At(0, -1) }
    }

    @Test
    fun `putU16At is big endian and touches only two bytes`() {
        val b = bytes(0xAA, 0xAA, 0xAA, 0xAA)

        b.putU16At(1, 0x1234)

        assertEquals(listOf(0xAA, 0x12, 0x34, 0xAA), b.map { it.toInt() and 0xFF })
        b.putU16At(0, 0xFFFF)
        assertEquals(65535, b.u16At(0))
        b.putU16At(0, 0)
        assertEquals(0, b.u16At(0))
    }

    @Test
    fun `putU16At rejects values that do not fit`() {
        val b = ByteArray(2)
        assertThrows(IllegalArgumentException::class.java) { b.putU16At(0, 65536) }
        assertThrows(IllegalArgumentException::class.java) { b.putU16At(0, -1) }
    }

    @Test
    fun `putS32At is big endian two's complement`() {
        fun encode(v: Int) = ByteArray(4).also { it.putS32At(0, v) }.map { it.toInt() and 0xFF }

        assertEquals(listOf(0, 0, 0, 1), encode(1))
        assertEquals(listOf(0x12, 0x34, 0x56, 0x78), encode(0x12345678))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), encode(-1))
        assertEquals(listOf(0x80, 0, 0, 0), encode(Int.MIN_VALUE))
        assertEquals(listOf(0x7F, 0xFF, 0xFF, 0xFF), encode(Int.MAX_VALUE))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0x11), encode(-239)) // pseudo-encodage Cursor
    }

    @Test
    fun `writers and readers are inverse`() {
        for (v in listOf(0, 1, -1, 255, 256, -239, 0x12345678, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val b = ByteArray(4)
            b.putS32At(0, v)
            assertEquals(v.toLong() and 0xFFFFFFFFL, b.u32At(0))
        }
    }

    @Test
    fun `sanitizeServerText keeps printable ASCII`() {
        assertEquals("Desktop 1 (user@host:0)", sanitizeServerText("Desktop 1 (user@host:0)".toByteArray(Charsets.US_ASCII)))
        assertEquals(" ~", sanitizeServerText(bytes(0x20, 0x7E))) // bornes incluses
    }

    @Test
    fun `sanitizeServerText replaces everything else`() {
        assertEquals("?", sanitizeServerText(bytes(0x1F)))        // juste sous l'espace
        assertEquals("?", sanitizeServerText(bytes(0x7F)))        // DEL
        assertEquals("?", sanitizeServerText(bytes(0x00)))
        assertEquals("A?B", sanitizeServerText(bytes('A'.code, 0x1B, 'B'.code))) // ESC
        assertEquals("??", sanitizeServerText("é".toByteArray(Charsets.UTF_8))) // 2 octets UTF-8 -> 2 '?'
        assertEquals("???", sanitizeServerText(bytes(0x80, 0xFF, 0xC3)))
        assertEquals("?", sanitizeServerText(bytes('\n'.code)))
    }

    @Test
    fun `sanitizeServerText of nothing is empty`() {
        assertEquals("", sanitizeServerText(ByteArray(0)))
    }
}
