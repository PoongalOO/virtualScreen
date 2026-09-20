package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.encoding.EncodingDecoder
import fr.webinfoconcept.secondscreen.rfb.encoding.RawDecoder
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopArgb
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopPixel
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopRect
import fr.webinfoconcept.secondscreen.rfb.testutil.framebufferUpdate
import fr.webinfoconcept.secondscreen.rfb.testutil.rawRect
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.testutil.updateHeader
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lecture des messages serveur : FramebufferUpdate multi-rectangles, Bell, ServerCutText, erreurs, EOF. */
class ServerMessageReaderTest {

    private val black = Framebuffer.OPAQUE_BLACK
    private val bell = byteArrayOf(2)

    private fun cutText(length: Long, text: ByteArray = ByteArray(0)) = byteArrayOf(3, 0, 0, 0) + u32(length) + text

    private fun readerFor(p: LoopbackPair, fb: Framebuffer, listener: RectangleListener? = null) =
        ServerMessageReader(p.client, fb, listener = listener)

    private fun updated(message: ServerMessage): Int = (message as ServerMessage.FramebufferUpdated).rectangles

    // -------------------------------------------------- FramebufferUpdate

    @Test(timeout = 10_000)
    fun `an update with no rectangle is valid`() = LoopbackPair().use { p ->
        p.send(updateHeader(0))

        assertEquals(0, updated(readerFor(p, Framebuffer(20, 10)).readMessage()))
    }

    @Test(timeout = 10_000)
    fun `one rectangle is decoded into the framebuffer`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(framebufferUpdate(rawRect(5, 6, 4, 3, desktopRect(5, 6, 4, 3))))

        assertEquals(1, updated(readerFor(p, fb).readMessage()))

        for (y in 6 until 9) for (x in 5 until 9) assertEquals(desktopPixel(x, y), fb.getPixel(x, y))
        assertEquals(12, fb.pixels.count { it != black })
    }

    @Test(timeout = 10_000)
    fun `several rectangles of one update are all applied in order`() = LoopbackPair().use { p ->
        val fb = Framebuffer(40, 20)
        p.send(
            framebufferUpdate(
                rawRect(0, 0, 8, 4, desktopRect(0, 0, 8, 4)),
                rawRect(30, 15, 10, 5, desktopRect(30, 15, 10, 5)),
                rawRect(10, 8, 3, 3, desktopRect(10, 8, 3, 3)),
                rawRect(12, 9, 3, 3, IntArray(9) { 0xFF00FF00.toInt() }) // recouvre le précédent : le dernier gagne
            )
        )

        assertEquals(4, updated(readerFor(p, fb).readMessage()))

        assertEquals(desktopPixel(3, 2), fb.getPixel(3, 2))
        assertEquals(desktopPixel(35, 17), fb.getPixel(35, 17))
        assertEquals(desktopPixel(10, 8), fb.getPixel(10, 8))
        assertEquals(0xFF00FF00.toInt(), fb.getPixel(12, 9)) // recouvrement : dernier rectangle
        assertEquals(desktopPixel(11, 8), fb.getPixel(11, 8)) // hors recouvrement
    }

    @Test(timeout = 30_000)
    fun `a full desktop delivered as several updates is reproduced exactly`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val fb = Framebuffer()
        // 4 mises à jour de 4 rectangles chacune : 16 tuiles de 320x200.
        val updates = (0 until 4).map { u ->
            framebufferUpdate(*Array(4) { r ->
                val tile = u * 4 + r
                val x = (tile % 4) * 320
                val y = (tile / 4) * 200
                rawRect(x, y, 320, 200, desktopRect(x, y, 320, 200))
            })
        }
        val writer = Thread { updates.forEach { p.send(it) } }
        writer.start()

        val reader = readerFor(p, fb)
        repeat(4) { assertEquals(4, updated(reader.readMessage())) }
        writer.join()

        assertArrayEquals(desktopArgb(1280, 800), fb.pixels)
    }

    @Test(timeout = 10_000)
    fun `the listener is told about each rectangle in order`() = LoopbackPair().use { p ->
        val seen = mutableListOf<List<Int>>()
        p.send(
            framebufferUpdate(
                rawRect(1, 2, 3, 4, desktopRect(1, 2, 3, 4)),
                rawRect(10, 5, 2, 1, desktopRect(10, 5, 2, 1))
            )
        )
        val listener = object : RectangleListener {
            override fun onRectangle(x: Int, y: Int, w: Int, h: Int) {
                seen += listOf(x, y, w, h)
            }
        }

        readerFor(p, Framebuffer(20, 10), listener).readMessage()

        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(10, 5, 2, 1)), seen)
    }

    @Test(timeout = 10_000)
    fun `header layout matches the protocol byte for byte`() = LoopbackPair().use { p ->
        // type 0, padding, 1 rectangle ; x=2 y=1 w=2 h=1 encodage RAW ; deux pixels [B,G,R,X].
        val wire = byteArrayOf(
            0, 0, 0, 1,
            0, 2, 0, 1, 0, 2, 0, 1, 0, 0, 0, 0,
            0x33, 0x22, 0x11, 0x00,  0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x00
        )
        val fb = Framebuffer(8, 4)
        p.send(wire)

        readerFor(p, fb).readMessage()

        assertEquals(0xFF112233.toInt(), fb.getPixel(2, 1))
        assertEquals(0xFFCCBBAA.toInt(), fb.getPixel(3, 1))
    }

    @Test(timeout = 10_000)
    fun `65535 empty rectangles are read without trouble`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val empty = rectHeader(0, 0, 0, 0)
        val message = updateHeader(65535) + ByteArray(65535 * 12).also { big ->
            for (i in 0 until 65535) empty.copyInto(big, i * 12)
        }
        val writer = Thread { p.send(message) }
        writer.start()

        assertEquals(65535, updated(readerFor(p, Framebuffer(20, 10)).readMessage()))
        writer.join()
    }

    @Test(timeout = 20_000)
    fun `a whole update survives TCP fragmentation`() {
        val wire = framebufferUpdate(
            rawRect(2, 1, 5, 3, desktopRect(2, 1, 5, 3)),
            rawRect(20, 8, 4, 2, desktopRect(20, 8, 4, 2))
        ) + bell
        for (chunk in listOf(1, 2, 3, 7, 64)) {
            LoopbackPair().use { p ->
                val fb = Framebuffer(40, 20)
                val writer = Thread { p.sendFragmented(wire, chunk) }
                writer.start()

                val reader = readerFor(p, fb)
                assertEquals("fragments de $chunk", 2, updated(reader.readMessage()))
                assertSame(ServerMessage.Bell, reader.readMessage())
                writer.join()

                assertEquals(desktopPixel(3, 2), fb.getPixel(3, 2))
                assertEquals(desktopPixel(23, 9), fb.getPixel(23, 9))
            }
        }
    }

    // ------------------------------------------------- Bell et ServerCutText

    @Test(timeout = 10_000)
    fun `Bell is recognised and consumes one byte`() = LoopbackPair().use { p ->
        p.send(bell + bell)
        val reader = readerFor(p, Framebuffer(20, 10))

        assertSame(ServerMessage.Bell, reader.readMessage())
        assertSame(ServerMessage.Bell, reader.readMessage())
    }

    @Test(timeout = 10_000)
    fun `ServerCutText is consumed and the stream stays aligned`() = LoopbackPair().use { p ->
        val text = "clipboard content".toByteArray(Charsets.US_ASCII)
        p.send(cutText(text.size.toLong(), text) + bell)
        val reader = readerFor(p, Framebuffer(20, 10))

        val message = reader.readMessage() as ServerMessage.CutTextIgnored

        assertEquals(text.size, message.length)
        assertSame(ServerMessage.Bell, reader.readMessage()) // le message suivant est bien aligné
    }

    @Test(timeout = 10_000)
    fun `ServerCutText may be empty or exactly a skip block long`() = LoopbackPair().use { p ->
        p.send(cutText(0) + cutText(4096, ByteArray(4096)) + cutText(4097, ByteArray(4097)) + bell)
        val reader = readerFor(p, Framebuffer(20, 10))

        assertEquals(0, (reader.readMessage() as ServerMessage.CutTextIgnored).length)
        assertEquals(4096, (reader.readMessage() as ServerMessage.CutTextIgnored).length)
        assertEquals(4097, (reader.readMessage() as ServerMessage.CutTextIgnored).length)
        assertSame(ServerMessage.Bell, reader.readMessage())
    }

    @Test(timeout = 20_000)
    fun `the longest accepted ServerCutText is skipped without keeping it`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val max = ServerMessageReader.MAX_CUT_TEXT_LENGTH.toInt()
        val writer = Thread { p.send(cutText(max.toLong(), ByteArray(max)) + bell) }
        writer.start()
        val reader = readerFor(p, Framebuffer(20, 10))

        assertEquals(max, (reader.readMessage() as ServerMessage.CutTextIgnored).length)
        assertSame(ServerMessage.Bell, reader.readMessage())
        writer.join()
    }

    @Test(timeout = 10_000)
    fun `an oversized ServerCutText is refused without being read`() {
        for (announced in listOf(ServerMessageReader.MAX_CUT_TEXT_LENGTH + 1, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL)) {
            LoopbackPair(readTimeoutMs = 1_500).use { p ->
                p.send(cutText(announced)) // aucun octet de texte envoyé
                val start = System.nanoTime()

                val e = assertThrows(RfbProtocolException.CutTextTooLong::class.java) {
                    readerFor(p, Framebuffer(20, 10)).readMessage()
                }

                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertEquals(announced, e.announcedLength) // non signé : 0xFFFFFFFF reste positif
                assertTrue("lecture tentée : $elapsedMs ms", elapsedMs < 1_000)
                assertTrue(p.client.isClosed)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `mixed messages are read in sequence`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(
            bell + framebufferUpdate(rawRect(0, 0, 2, 2, desktopRect(0, 0, 2, 2))) + cutText(3, byteArrayOf(1, 2, 3)) +
                bell + framebufferUpdate(rawRect(4, 4, 1, 1, desktopRect(4, 4, 1, 1)))
        )
        val reader = readerFor(p, fb)

        assertSame(ServerMessage.Bell, reader.readMessage())
        assertEquals(1, updated(reader.readMessage()))
        assertEquals(3, (reader.readMessage() as ServerMessage.CutTextIgnored).length)
        assertSame(ServerMessage.Bell, reader.readMessage())
        assertEquals(1, updated(reader.readMessage()))
        assertEquals(desktopPixel(4, 4), fb.getPixel(4, 4))
    }

    // ------------------------------------------------------ types inconnus

    @Test(timeout = 10_000)
    fun `unknown message types are a typed error and close the socket`() {
        // 1 = SetColourMapEntries (jamais envoyé à un client true-colour), 4..255 inconnus.
        for (type in listOf(1, 4, 5, 0x7F, 0x80, 0xFF)) {
            LoopbackPair().use { p ->
                p.send(type, 1, 2, 3)

                val e = assertThrows(RfbProtocolException.UnsupportedServerMessage::class.java) {
                    readerFor(p, Framebuffer(20, 10)).readMessage()
                }

                assertEquals("type $type", type, e.type) // 0xFF reste 255, jamais -1
                assertTrue(p.client.isClosed)
            }
        }
    }

    // ------------------------------------------------- encodages inconnus

    @Test(timeout = 10_000)
    fun `an encoding without decoder is a typed error and closes the socket`() {
        // CopyRect, RRE, Hextile, Tight, ZRLE ; pseudo-encodages ; limites du S32.
        for (encoding in listOf(1, 2, 5, 7, 16, -223, -239, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            LoopbackPair().use { p ->
                p.send(updateHeader(1) + rectHeader(0, 0, 2, 2, encoding) + ByteArray(16))

                val e = assertThrows(RfbProtocolException.UnsupportedEncoding::class.java) {
                    readerFor(p, Framebuffer(20, 10)).readMessage()
                }

                assertEquals(encoding, e.encoding) // signé : -1 reste -1
                assertTrue(p.client.isClosed)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `an unsupported encoding reads no data`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        p.send(updateHeader(1) + rectHeader(0, 0, 2, 2, 5)) // pas de données derrière
        val start = System.nanoTime()

        assertThrows(RfbProtocolException.UnsupportedEncoding::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
    }

    @Test(timeout = 10_000)
    fun `a decoder can be plugged in for another encoding`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        val consumed = mutableListOf<Int>()
        val fake = object : EncodingDecoder {
            override val encoding = 99
            override fun decode(socket: RfbSocket, x: Int, y: Int, w: Int, h: Int, framebuffer: Framebuffer) {
                val data = ByteArray(3)
                socket.readFully(data, 0, 3)
                consumed += data.map { it.toInt() }
                framebuffer.fillRect(x, y, w, h, 0xFFABCDEF.toInt())
            }
        }
        val reader = ServerMessageReader(p.client, fb, decoders = listOf(RawDecoder(PixelFormat.XRGB_8888_LE, 20), fake))
        p.send(
            framebufferUpdate(
                rectHeader(1, 1, 2, 2, encoding = 99) + byteArrayOf(7, 8, 9),
                rawRect(10, 5, 1, 1, desktopRect(10, 5, 1, 1))
            )
        )

        assertEquals(2, updated(reader.readMessage()))

        assertEquals(listOf(7, 8, 9), consumed)
        assertEquals(0xFFABCDEF.toInt(), fb.getPixel(2, 2))
        assertEquals(desktopPixel(10, 5), fb.getPixel(10, 5))
        assertTrue(reader.supports(99))
    }

    @Test(timeout = 10_000)
    fun `two decoders for the same encoding are refused`() {
        LoopbackPair().use { p ->
            val fb = Framebuffer(20, 10)

            assertThrows(IllegalArgumentException::class.java) {
                ServerMessageReader(p.client, fb, decoders = listOf(RawDecoder(maxWidth = 20), RawDecoder(maxWidth = 20)))
            }
        }
    }

    @Test
    fun `every advertised encoding has a decoder`() = LoopbackPair().use { p ->
        // Garde-fou de SS-022 : on n'annonce que ce qu'on sait décoder.
        val reader = readerFor(p, Framebuffer(20, 10))

        for (encoding in Encoding.ADVERTISED) assertTrue("encodage $encoding annoncé sans décodeur", reader.supports(encoding))
        assertFalse(reader.supports(Encoding.HEXTILE))
        assertFalse(reader.supports(Encoding.COPY_RECT))
    }

    // ------------------------------------------------ rectangles hors écran

    @Test(timeout = 10_000)
    fun `an out of bounds rectangle is refused before its data and closes the socket`() {
        val bad = listOf(
            intArrayOf(15, 0, 6, 1), intArrayOf(0, 9, 1, 2), intArrayOf(19, 9, 2, 2),
            intArrayOf(20, 0, 1, 1), intArrayOf(65535, 65535, 65535, 65535), intArrayOf(0, 0, 21, 10)
        )
        for (r in bad) {
            LoopbackPair(readTimeoutMs = 1_500).use { p ->
                val fb = Framebuffer(20, 10)
                p.send(updateHeader(1) + rectHeader(r[0], r[1], r[2], r[3])) // aucune donnée de pixels
                val start = System.nanoTime()

                val e = assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
                    readerFor(p, fb).readMessage()
                }

                assertEquals(listOf(r[0], r[1], r[2], r[3]), listOf(e.x, e.y, e.width, e.height))
                assertTrue("lecture tentée", (System.nanoTime() - start) / 1_000_000 < 1_000)
                assertTrue(p.client.isClosed)
                assertTrue(fb.pixels.all { it == black })
            }
        }
    }

    @Test(timeout = 10_000)
    fun `rectangles decoded before a bad one stay applied`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(
            framebufferUpdate(
                rawRect(0, 0, 2, 2, desktopRect(0, 0, 2, 2)),
                rectHeader(19, 9, 5, 5) // hors écran
            )
        )

        assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) { readerFor(p, fb).readMessage() }

        assertEquals(desktopPixel(1, 1), fb.getPixel(1, 1)) // comportement documenté
    }

    // -------------------------------------------------- EOF et timeouts

    @Test(timeout = 10_000)
    fun `EOF at a message boundary is the normal close by the server`() = LoopbackPair().use { p ->
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(0, e.bytesRead) // aucun octet d'un message : fermeture propre
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `a timeout at a message boundary is recoverable`() = LoopbackPair(readTimeoutMs = 150).use { p ->
        val reader = readerFor(p, Framebuffer(20, 10))

        val e = assertThrows(RfbTransportException.ReadTimeout::class.java) { reader.readMessage() }
        assertEquals(0, e.bytesRead)
        assertFalse(p.client.isClosed) // la socket reste ouverte

        p.send(bell)
        assertSame(ServerMessage.Bell, reader.readMessage()) // et le flux est toujours aligné
    }

    @Test(timeout = 10_000)
    fun `a timeout in the middle of a message closes the socket`() = LoopbackPair(readTimeoutMs = 150).use { p ->
        p.send(0) // type FramebufferUpdate, puis silence

        assertThrows(RfbTransportException.ReadTimeout::class.java) { readerFor(p, Framebuffer(20, 10)).readMessage() }

        assertTrue(p.client.isClosed) // flux désaligné : on ne réessaie pas
    }

    @Test(timeout = 10_000)
    fun `EOF after the type byte is a truncated message`() = LoopbackPair().use { p ->
        p.send(0, 0) // type + 1 octet de padding sur 3
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(1, e.bytesRead)
        assertEquals(3, e.bytesExpected)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `EOF in the middle of a rectangle header is a truncated message`() = LoopbackPair().use { p ->
        p.send(updateHeader(1) + rectHeader(0, 0, 2, 2).copyOfRange(0, 5))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(5, e.bytesRead)
        assertEquals(12, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `EOF in the middle of pixel data is a truncated message`() = LoopbackPair().use { p ->
        val wire = framebufferUpdate(rawRect(0, 0, 4, 2, desktopRect(0, 0, 4, 2)))
        p.send(wire.copyOfRange(0, wire.size - 6)) // il manque 6 octets de la dernière ligne
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(10, e.bytesRead)
        assertEquals(16, e.bytesExpected)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `fewer rectangles than announced is a truncated message`() = LoopbackPair().use { p ->
        p.send(updateHeader(2) + rawRect(0, 0, 1, 1, desktopRect(0, 0, 1, 1))) // annonce 2, en envoie 1
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(0, e.bytesRead)
        assertEquals(12, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `EOF in the middle of ServerCutText is a truncated message`() = LoopbackPair().use { p ->
        p.send(cutText(100, ByteArray(30)))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals(30, e.bytesRead)
        assertEquals(100, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `error messages contain only numbers`() = LoopbackPair().use { p ->
        p.send(updateHeader(1) + rectHeader(0, 0, 2, 2, 12345) + ByteArray(16))

        val e = assertThrows(RfbProtocolException.UnsupportedEncoding::class.java) {
            readerFor(p, Framebuffer(20, 10)).readMessage()
        }

        assertEquals("Encodage non supporté (12345)", e.message)
    }

    @Test
    fun `message type constants match RFC 6143`() {
        assertEquals(0, ServerMessageReader.TYPE_FRAMEBUFFER_UPDATE)
        assertEquals(2, ServerMessageReader.TYPE_BELL)
        assertEquals(3, ServerMessageReader.TYPE_SERVER_CUT_TEXT)
        assertEquals(1024 * 1024L, ServerMessageReader.MAX_CUT_TEXT_LENGTH)
    }
}
