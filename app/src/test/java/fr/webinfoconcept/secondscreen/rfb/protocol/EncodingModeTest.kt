package fr.webinfoconcept.secondscreen.rfb.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choix de l'encodage annoncé au serveur (SS-063) : chaque liste doit être valide, décodable et se finir par RAW. */
class EncodingModeTest {

    @Test
    fun `auto is exactly the advertised list, the default behaviour`() {
        assertEquals(Encoding.ADVERTISED, EncodingMode.AUTO.encodings)
    }

    @Test
    fun `hextile mode is hextile then raw without copyrect, raw mode is raw alone`() {
        assertEquals(listOf(Encoding.HEXTILE, Encoding.RAW), EncodingMode.HEXTILE.encodings)
        assertEquals(listOf(Encoding.RAW), EncodingMode.RAW.encodings)
    }

    @Test
    fun `every mode gives a valid SetEncodings message, in the order of the list`() {
        for (mode in EncodingMode.values()) {
            val message = ClientMessages.setEncodings(mode.encodings)
            assertEquals(mode.name, 4 + 4 * mode.encodings.size, message.size)
            assertEquals(mode.name, mode.encodings.size, ((message[2].toInt() and 255) shl 8) or (message[3].toInt() and 255))
            val first = ((message[4].toInt() and 255) shl 24) or ((message[5].toInt() and 255) shl 16) or
                ((message[6].toInt() and 255) shl 8) or (message[7].toInt() and 255)
            assertEquals(mode.name, mode.encodings.first(), first)
        }
    }

    @Test
    fun `every mode ends with raw, which any server can send, and has no duplicate`() {
        for (mode in EncodingMode.values()) {
            assertEquals(mode.name, Encoding.RAW, mode.encodings.last())
            assertEquals(mode.name, mode.encodings.size, mode.encodings.toSet().size)
        }
    }

    @Test
    fun `the rule of the advertised list holds for every mode - only encodings that have a decoder`() {
        val decodable = ServerMessageReader.defaultDecoders(PixelFormat.XRGB_8888_LE, 64).map { it.encoding }.toSet()
        for (mode in EncodingMode.values()) {
            for (encoding in mode.encodings) assertTrue("${mode.name} annonce $encoding sans décodeur", encoding in decodable)
        }
    }

    @Test
    fun `keys are unique and round trip, anything unknown is auto`() {
        assertEquals(EncodingMode.values().size, EncodingMode.values().map { it.key }.toSet().size)
        for (mode in EncodingMode.values()) assertEquals(mode, EncodingMode.fromKey(mode.key))
        for (garbage in listOf(null, "", "AUTO", "Raw", "tight", "0", "hextile ")) {
            assertEquals("« $garbage »", EncodingMode.AUTO, EncodingMode.fromKey(garbage))
        }
    }

    @Test
    fun `the message of the default mode is the message of the default argument`() {
        assertArrayEquals(ClientMessages.setEncodings(), ClientMessages.setEncodings(EncodingMode.AUTO.encodings))
    }
}
