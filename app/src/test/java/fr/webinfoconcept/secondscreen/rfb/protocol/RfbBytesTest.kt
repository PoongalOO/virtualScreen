package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertEquals
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
