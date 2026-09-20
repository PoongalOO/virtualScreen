package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessage
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopArgb
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopPixel
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopRect
import fr.webinfoconcept.secondscreen.rfb.testutil.framebufferUpdate
import fr.webinfoconcept.secondscreen.rfb.testutil.rawRect
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.u16
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Décodeur CopyRect (SS-025) : lecture des coordonnées source, chevauchement, ordre des rectangles, sécurité. */
class CopyRectDecoderTest {

    private fun filled(width: Int, height: Int) =
        Framebuffer(width, height).also { it.writeRect(0, 0, width, height, desktopArgb(width, height)) }

    private fun source(x: Int, y: Int) = u16(x) + u16(y)

    // ----------------------------------------------------------- décodage

    @Test(timeout = 10_000)
    fun `copies the announced source rectangle`() = LoopbackPair().use { p ->
        val fb = filled(40, 20)
        val before = fb.pixels.copyOf()
        p.send(source(2, 3))

        CopyRectDecoder().decode(p.client, 20, 10, 6, 4, fb)

        for (dy in 0 until 4) for (dx in 0 until 6) {
            assertEquals("($dx,$dy)", before[(3 + dy) * 40 + 2 + dx], fb.getPixel(20 + dx, 10 + dy))
        }
    }

    @Test(timeout = 10_000)
    fun `source coordinates are big endian and unsigned`() = LoopbackPair().use { p ->
        val fb = filled(600, 300)
        val before = fb.pixels.copyOf()
        p.send(source(0x0102, 0x0103)) // x = 258, y = 259 : l'ordre des octets compte

        CopyRectDecoder().decode(p.client, 0, 0, 10, 10, fb)

        assertEquals(before[259 * 600 + 258], fb.getPixel(0, 0))
        assertEquals(before[268 * 600 + 267], fb.getPixel(9, 9))
    }

    @Test(timeout = 10_000)
    fun `reads exactly four bytes`() = LoopbackPair().use { p ->
        val fb = filled(40, 20)
        p.send(source(0, 0) + byteArrayOf(9, 8, 7, 6))

        CopyRectDecoder().decode(p.client, 10, 10, 4, 4, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), next)
    }

    @Test(timeout = 10_000)
    fun `an empty rectangle still consumes its four bytes`() = LoopbackPair().use { p ->
        // Le serveur envoie toujours les 4 octets : les ignorer désalignerait le flux.
        val fb = filled(40, 20)
        val before = fb.pixels.copyOf()
        p.send(source(0, 0) + byteArrayOf(9, 9, 9, 9))

        CopyRectDecoder().decode(p.client, 5, 5, 0, 3, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), next)
        assertArrayEquals(before, fb.pixels)
    }

    @Test(timeout = 10_000)
    fun `overlapping copies do not corrupt`() {
        // Défilement vers le haut de 8 lignes (source et destination se chevauchent), puis vers le bas.
        for ((srcY, dstY) in listOf(8 to 0, 0 to 8)) {
            LoopbackPair().use { p ->
                val fb = filled(40, 24)
                val before = fb.pixels.copyOf()
                p.send(source(0, srcY))

                CopyRectDecoder().decode(p.client, 0, dstY, 40, 16, fb)

                for (row in 0 until 16) for (x in 0 until 40) {
                    assertEquals("ligne $row col $x", before[(srcY + row) * 40 + x], fb.getPixel(x, dstY + row))
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun `survives TCP fragmentation`() {
        for (chunk in listOf(1, 2, 3)) {
            LoopbackPair().use { p ->
                val fb = filled(40, 20)
                val before = fb.pixels.copyOf()
                val writer = Thread { p.sendFragmented(source(4, 5), chunk) }
                writer.start()

                CopyRectDecoder().decode(p.client, 20, 10, 3, 3, fb)
                writer.join()

                assertEquals(before[5 * 40 + 4], fb.getPixel(20, 10))
            }
        }
    }

    // ------------------------------------------------------------- sécurité

    @Test(timeout = 10_000)
    fun `an out of bounds source is refused after its coordinates are read and writes nothing`() {
        val bad = listOf(
            intArrayOf(35, 0, 6, 4),          // source déborde à droite
            intArrayOf(0, 18, 4, 4),          // source déborde en bas
            intArrayOf(0xFFFF, 0xFFFF, 4, 4), // 65535 : non signé, jamais négatif
            intArrayOf(0x8000, 0, 4, 4)       // 32768
        )
        for (r in bad) {
            LoopbackPair().use { p ->
                val fb = filled(40, 20)
                val before = fb.pixels.copyOf()
                p.send(source(r[0], r[1]) + byteArrayOf(1, 2, 3, 4)) // les 4 octets sont lus ; la suite est intacte

                val e = assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
                    CopyRectDecoder().decode(p.client, 0, 0, r[2], r[3], fb)
                }

                assertEquals("source signalée", listOf(r[0], r[1], r[2], r[3]), listOf(e.x, e.y, e.width, e.height))
                assertArrayEquals("aucune écriture", before, fb.pixels)
                val next = ByteArray(4)
                p.client.readFully(next, 0, 4)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), next)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `an out of bounds destination is refused before reading anything`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        val fb = filled(40, 20)
        val before = fb.pixels.copyOf()
        val start = System.nanoTime() // aucune donnée envoyée : lire attendrait le timeout de 1,5 s

        assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
            CopyRectDecoder().decode(p.client, 35, 0, 6, 4, fb)
        }

        assertTrue("lecture tentée", (System.nanoTime() - start) / 1_000_000 < 1_000)
        assertArrayEquals(before, fb.pixels)
        assertTrue(p.client.isConnected)
    }

    @Test(timeout = 10_000)
    fun `EOF in the middle of the source coordinates is an end of stream`() = LoopbackPair().use { p ->
        val fb = filled(40, 20)
        p.send(0, 1)
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
            CopyRectDecoder().decode(p.client, 0, 0, 4, 4, fb)
        }

        assertEquals(2, e.bytesRead)
        assertEquals(4, e.bytesExpected)
    }

    @Test
    fun `identifies itself as the CopyRect encoding`() {
        assertEquals(Encoding.COPY_RECT, CopyRectDecoder().encoding)
        assertEquals(1, CopyRectDecoder().encoding)
    }

    // ---------------------------------- dans un FramebufferUpdate (via le lecteur)

    private fun readerFor(p: LoopbackPair, fb: Framebuffer) = ServerMessageReader(p.client, fb)

    private fun copyRectRect(x: Int, y: Int, w: Int, h: Int, srcX: Int, srcY: Int) =
        rectHeader(x, y, w, h, Encoding.COPY_RECT) + source(srcX, srcY)

    @Test(timeout = 10_000)
    fun `a CopyRect rectangle in an update copies what earlier rectangles drew`() = LoopbackPair().use { p ->
        val fb = Framebuffer(40, 20)
        // 1) RAW dessine un motif en (0,0) ; 2) CopyRect le recopie en (20,10) : la source est l'état APRÈS le rectangle 1.
        p.send(
            framebufferUpdate(
                rawRect(0, 0, 4, 4, desktopRect(0, 0, 4, 4)),
                copyRectRect(20, 10, 4, 4, 0, 0)
            )
        )

        assertEquals(2, (readerFor(p, fb).readMessage() as ServerMessage.FramebufferUpdated).rectangles)

        for (dy in 0 until 4) for (dx in 0 until 4) assertEquals(desktopPixel(dx, dy), fb.getPixel(20 + dx, 10 + dy))
    }

    @Test(timeout = 10_000)
    fun `the source of a CopyRect is the framebuffer at that moment not before the update`() = LoopbackPair().use { p ->
        val fb = Framebuffer(40, 20)
        val patch = 0xFF123456.toInt()
        // CopyRect d'abord (recopie du noir), puis RAW écrase la source : la copie doit rester noire.
        p.send(
            framebufferUpdate(
                copyRectRect(20, 10, 4, 4, 0, 0),
                rawRect(0, 0, 4, 4, IntArray(16) { patch })
            )
        )

        readerFor(p, fb).readMessage()

        assertEquals(Framebuffer.OPAQUE_BLACK, fb.getPixel(20, 10)) // copié avant l'écrasement
        assertEquals(patch, fb.getPixel(0, 0))
    }

    @Test(timeout = 10_000)
    fun `a terminal scroll update reproduces the expected screen`() = LoopbackPair().use { p ->
        val fb = Framebuffer(64, 48)
        val expected = IntArray(64 * 48)

        // État initial : l'écran complet en RAW.
        p.send(framebufferUpdate(rawRect(0, 0, 64, 48, desktopRect(0, 0, 64, 48))))
        readerFor(p, fb).let { it.readMessage() }
        desktopArgb(64, 48).copyInto(expected)

        // Défilement de 16 lignes vers le haut (CopyRect chevauchant) puis nouvelle ligne du bas en RAW.
        val newBottom = IntArray(64 * 16) { 0xFF445566.toInt() + it % 7 }
        p.send(
            framebufferUpdate(
                copyRectRect(0, 0, 64, 32, 0, 16),
                rawRect(0, 32, 64, 16, newBottom)
            )
        )
        val reader = ServerMessageReader(p.client, fb)
        reader.readMessage()

        // Attendu, calculé à part : lignes 16..47 remontent en 0..31, puis le nouveau bas.
        val scrolled = expected.copyOf()
        for (row in 0 until 32) System.arraycopy(expected, (16 + row) * 64, scrolled, row * 64, 64)
        System.arraycopy(newBottom, 0, scrolled, 32 * 64, 64 * 16)
        assertArrayEquals(scrolled, fb.pixels)
    }

    @Test(timeout = 10_000)
    fun `an out of bounds source inside an update is a typed error and closes the socket`() = LoopbackPair().use { p ->
        p.send(framebufferUpdate(copyRectRect(0, 0, 8, 8, 60, 44))) // 60 + 8 > 64

        assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
            readerFor(p, Framebuffer(64, 48)).readMessage()
        }

        assertTrue(p.client.isClosed)
    }

    @Test
    fun `the reader supports CopyRect by default`() = LoopbackPair().use { p ->
        val reader = readerFor(p, Framebuffer(20, 10))

        assertTrue(reader.supports(Encoding.COPY_RECT))
        assertTrue(reader.supports(Encoding.RAW))
    }
}
