package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Tests purs (sans réseau) de l'analyse, du choix et de l'encodage de la bannière RFB. */
class ProtocolVersionTest {

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private fun parse(s: String) = ProtocolVersion.parseServerBanner(ascii(s))

    // --- parseServerBanner ---

    @Test
    fun `parses well formed banners`() {
        assertEquals(ServerVersion(3, 8), parse("RFB 003.008\n"))
        assertEquals(ServerVersion(3, 3), parse("RFB 003.003\n"))
        assertEquals(ServerVersion(3, 889), parse("RFB 003.889\n"))
        assertEquals(ServerVersion(4, 1), parse("RFB 004.001\n"))
        assertEquals(ServerVersion(0, 0), parse("RFB 000.000\n"))
        assertEquals(ServerVersion(999, 999), parse("RFB 999.999\n"))
    }

    @Test
    fun `rejects malformed banners`() {
        val invalid = listOf(
            "SSH-2.0-Open",        // autre protocole, même longueur
            "HTTP/1.1 400",
            "rfb 003.008\n",       // casse
            "RFB003.008\n\n",      // espace manquant
            "RFB 003.008\r",       // mauvais terminateur
            "RFB 003.008 ",
            "RFB 003.008\u0000",
            "RFB 003x008\n",       // séparateur
            "RFB 00a.008\n",       // non chiffre
            "RFB 003.00 \n",
            "RFB -03.008\n",       // signe
            "RFB +03.008\n",
            "RFB  03.008\n",       // espace au lieu de zéro
            "RFB 003.0\u00058\n",  // caractère de contrôle
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
        )
        for (s in invalid) {
            assertThrows("devrait être rejetée : ${s.length}", RfbProtocolException.InvalidBanner::class.java) {
                parse(s)
            }
        }
    }

    @Test
    fun `rejects bytes above 0x7F where a digit is expected`() {
        val banner = ascii("RFB 003.008\n")
        for (position in listOf(4, 5, 6, 8, 9, 10)) {
            for (value in listOf(0x80, 0xB2, 0xFF)) { // 0xB2 = '²' en Latin-1
                val copy = banner.copyOf()
                copy[position] = value.toByte()
                assertThrows(RfbProtocolException.InvalidBanner::class.java) {
                    ProtocolVersion.parseServerBanner(copy)
                }
            }
        }
    }

    @Test
    fun `wrong length is a programming error not a protocol error`() {
        assertThrows(IllegalArgumentException::class.java) { ProtocolVersion.parseServerBanner(ByteArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolVersion.parseServerBanner(ByteArray(13)) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolVersion.parseServerBanner(ByteArray(0)) }
    }

    @Test
    fun `error messages never echo server bytes`() {
        val e = assertThrows(RfbProtocolException.InvalidBanner::class.java) { parse("SSH-2.0-Open") }
        assertFalse(e.message!!.contains("SSH"))
    }

    // --- select ---

    @Test
    fun `selects 3_3 for servers from 3_3 to 3_7`() {
        for (minor in 3..7) {
            assertEquals("3.$minor", RfbVersion.V3_3, ProtocolVersion.select(ServerVersion(3, minor)))
        }
    }

    @Test
    fun `selects 3_8 for 3_8 and newer`() {
        assertEquals(RfbVersion.V3_8, ProtocolVersion.select(ServerVersion(3, 8)))
        assertEquals(RfbVersion.V3_8, ProtocolVersion.select(ServerVersion(3, 9)))
        assertEquals(RfbVersion.V3_8, ProtocolVersion.select(ServerVersion(3, 889))) // Apple Remote Desktop
        assertEquals(RfbVersion.V3_8, ProtocolVersion.select(ServerVersion(4, 0)))
        assertEquals(RfbVersion.V3_8, ProtocolVersion.select(ServerVersion(999, 999)))
    }

    @Test
    fun `rejects servers older than 3_3 and reports their version`() {
        for ((major, minor) in listOf(3 to 2, 3 to 0, 2 to 9, 2 to 0, 0 to 0, 1 to 999)) {
            val e = assertThrows(RfbProtocolException.UnsupportedVersion::class.java) {
                ProtocolVersion.select(ServerVersion(major, minor))
            }
            assertEquals(major, e.major)
            assertEquals(minor, e.minor)
        }
    }

    // --- encodage ---

    @Test
    fun `encodes client banners`() {
        assertArrayEquals(ascii("RFB 003.008\n"), RfbVersion.V3_8.bannerBytes())
        assertArrayEquals(ascii("RFB 003.003\n"), RfbVersion.V3_3.bannerBytes())
        assertEquals(ProtocolVersion.BANNER_LENGTH, RfbVersion.V3_8.bannerBytes().size)
    }

    @Test
    fun `banner bytes are a fresh copy each time`() {
        val first = RfbVersion.V3_8.bannerBytes()
        first[0] = 0
        val second = RfbVersion.V3_8.bannerBytes()

        assertNotSame(first, second)
        assertArrayEquals(ascii("RFB 003.008\n"), second)
    }

    @Test
    fun `every supported version round trips through parse and select`() {
        for (version in RfbVersion.values()) {
            val parsed = ProtocolVersion.parseServerBanner(version.bannerBytes())
            assertEquals(ServerVersion(version.major, version.minor), parsed)
            assertEquals(version, ProtocolVersion.select(parsed))
        }
    }

    @Test
    fun `formatBanner rejects out of range versions`() {
        assertThrows(IllegalArgumentException::class.java) { ProtocolVersion.formatBanner(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolVersion.formatBanner(0, 1000) }
    }
}
