package fr.webinfoconcept.secondscreen.rfb.encoding

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopArgb
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopPixel
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopRect
import fr.webinfoconcept.secondscreen.rfb.testutil.rawXrgb
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Décodeur RAW (SS-024) : exactitude pixel par pixel, rectangles partiels, erreurs, chemins rapide/générique. */
class RawDecoderTest {

    private val black = Framebuffer.OPAQUE_BLACK

    private fun decoderFor(fb: Framebuffer, format: PixelFormat = PixelFormat.XRGB_8888_LE) =
        RawDecoder(format, fb.width)

    /** Vérifie chaque pixel de [fb] : ceux de [inside] valent [expected], tous les autres [outside]. */
    private fun assertPixels(
        fb: Framebuffer, x0: Int, y0: Int, w: Int, h: Int, expected: (Int, Int) -> Int, outside: Int = black
    ) {
        for (y in 0 until fb.height) {
            for (x in 0 until fb.width) {
                val inside = x in x0 until x0 + w && y in y0 until y0 + h
                assertEquals("($x,$y)", if (inside) expected(x, y) else outside, fb.getPixel(x, y))
            }
        }
    }

    // ------------------------------------------------------ exactitude

    @Test(timeout = 10_000)
    fun `decodes a rectangle exactly and touches nothing else`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(rawXrgb(desktopRect(5, 6, 4, 3)))

        decoderFor(fb).decode(p.client, 5, 6, 4, 3, fb)

        assertPixels(fb, 5, 6, 4, 3, expected = ::desktopPixel)
    }

    @Test(timeout = 10_000)
    fun `decodes at the four corners of the screen`() {
        for ((x, y) in listOf(0 to 0, 16 to 0, 0 to 6, 16 to 6)) {
            LoopbackPair().use { p ->
                val fb = Framebuffer(20, 10)
                p.send(rawXrgb(desktopRect(x, y, 4, 4)))

                decoderFor(fb).decode(p.client, x, y, 4, 4, fb)

                assertPixels(fb, x, y, 4, 4, expected = ::desktopPixel)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `decodes a single pixel and a single row and a single column`() {
        for ((w, h) in listOf(1 to 1, 7 to 1, 1 to 7)) {
            LoopbackPair().use { p ->
                val fb = Framebuffer(20, 10)
                p.send(rawXrgb(desktopRect(3, 2, w, h)))

                decoderFor(fb).decode(p.client, 3, 2, w, h, fb)

                assertPixels(fb, 3, 2, w, h, expected = ::desktopPixel)
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a full 1280x800 desktop is reproduced exactly`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val fb = Framebuffer() // 1280x800
        val expected = desktopArgb(1280, 800)
        val writer = Thread { p.send(rawXrgb(expected)) } // 4 Mio : plus que les tampons TCP
        writer.start()

        decoderFor(fb).decode(p.client, 0, 0, 1280, 800, fb)
        writer.join()

        assertArrayEquals(expected, fb.pixels)
    }

    @Test(timeout = 30_000)
    fun `partial rectangles compose the desktop exactly in any order`() = LoopbackPair(readTimeoutMs = 10_000).use { p ->
        val fb = Framebuffer()
        // 16 tuiles de 320x200, envoyées dans un ordre qui n'est ni ligne à ligne ni colonne à colonne.
        val tiles = (0 until 16).map { (it * 7) % 16 }.map { (it % 4) * 320 to (it / 4) * 200 }
        assertEquals(16, tiles.toSet().size)
        val writer = Thread { for ((x, y) in tiles) p.send(rawXrgb(desktopRect(x, y, 320, 200))) }
        writer.start()

        val decoder = decoderFor(fb)
        for ((x, y) in tiles) decoder.decode(p.client, x, y, 320, 200, fb)
        writer.join()

        assertArrayEquals(desktopArgb(1280, 800), fb.pixels)
    }

    @Test(timeout = 10_000)
    fun `a partial update leaves the rest of an existing image intact`() = LoopbackPair().use { p ->
        val fb = Framebuffer(64, 48)
        fb.writeRect(0, 0, 64, 48, desktopArgb(64, 48))
        val before = fb.pixels.copyOf()
        val patch = 0xFF123456.toInt()
        p.send(rawXrgb(IntArray(10 * 5) { patch }))

        decoderFor(fb).decode(p.client, 20, 15, 10, 5, fb)

        for (y in 0 until 48) {
            for (x in 0 until 64) {
                val inside = x in 20 until 30 && y in 15 until 20
                assertEquals("($x,$y)", if (inside) patch else before[y * 64 + x], fb.getPixel(x, y))
            }
        }
    }

    @Test(timeout = 10_000)
    fun `the unused byte is ignored and alpha is always opaque`() = LoopbackPair().use { p ->
        val fb = Framebuffer(8, 4)
        val pixels = desktopRect(0, 0, 8, 4)
        p.send(rawXrgb(pixels, unused = 0x00))
        p.send(rawXrgb(pixels, unused = 0xFF)) // le serveur peut y mettre n'importe quoi

        val decoder = decoderFor(fb)
        decoder.decode(p.client, 0, 0, 8, 4, fb)
        val first = fb.pixels.copyOf()
        decoder.decode(p.client, 0, 0, 8, 4, fb)

        assertArrayEquals(first, fb.pixels)
        assertTrue(fb.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test(timeout = 10_000)
    fun `empty rectangles read nothing`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(1, 2, 3, 4) // ne doit pas être consommé

        val decoder = decoderFor(fb)
        decoder.decode(p.client, 5, 5, 0, 3, fb)
        decoder.decode(p.client, 5, 5, 3, 0, fb)
        decoder.decode(p.client, 20, 10, 0, 0, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), next)
        assertTrue(fb.pixels.all { it == black })
    }

    @Test(timeout = 10_000)
    fun `reads exactly width x height x 4 bytes`() = LoopbackPair().use { p ->
        val fb = Framebuffer(20, 10)
        p.send(rawXrgb(desktopRect(0, 0, 3, 2)) + byteArrayOf(9, 8, 7, 6))

        decoderFor(fb).decode(p.client, 0, 0, 3, 2, fb)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), next) // rien consommé au-delà du rectangle
    }

    @Test(timeout = 20_000)
    fun `survives TCP fragmentation`() {
        for (chunk in listOf(1, 3, 5, 4, 4096)) {
            LoopbackPair().use { p ->
                val fb = Framebuffer(40, 20)
                val wire = rawXrgb(desktopRect(10, 4, 16, 8))
                val writer = Thread { p.sendFragmented(wire, chunk) }
                writer.start()

                decoderFor(fb).decode(p.client, 10, 4, 16, 8, fb)
                writer.join()

                assertPixels(fb, 10, 4, 16, 8, expected = ::desktopPixel)
            }
        }
    }

    // ------------------------------------------------ validation avant lecture

    @Test(timeout = 10_000)
    fun `an out of bounds rectangle is refused before reading any pixel`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        val fb = Framebuffer(20, 10)
        val decoder = decoderFor(fb)
        // Aucune donnée envoyée : si le décodeur tentait de lire, il attendrait le timeout de 1,5 s.
        val start = System.nanoTime()

        val rects = listOf(
            intArrayOf(15, 0, 6, 1), intArrayOf(0, 9, 1, 2), intArrayOf(19, 9, 2, 2),
            intArrayOf(65535, 65535, 65535, 65535), intArrayOf(Int.MAX_VALUE, 0, 10, 10)
        )
        for (r in rects) {
            assertThrows(RfbProtocolException.RectangleOutOfBounds::class.java) {
                decoder.decode(p.client, r[0], r[1], r[2], r[3], fb)
            }
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("lecture tentée : $elapsedMs ms", elapsedMs < 1_000)
        assertTrue(fb.pixels.all { it == black })
        assertTrue(p.client.isConnected) // une erreur de rectangle ne touche pas la socket
    }

    // ---------------------------------------------------------------- erreurs

    @Test(timeout = 10_000)
    fun `EOF in the middle of a row is an end of stream and completed rows stay written`() =
        LoopbackPair().use { p ->
            val fb = Framebuffer(20, 10)
            val wire = rawXrgb(desktopRect(2, 3, 4, 3)) // 3 lignes de 16 octets
            p.send(wire.copyOfRange(0, 16 + 7))          // ligne 1 complète, ligne 2 à moitié
            p.peer.close()

            val e = assertThrows(RfbTransportException.EndOfStream::class.java) {
                decoderFor(fb).decode(p.client, 2, 3, 4, 3, fb)
            }

            assertEquals(7, e.bytesRead)
            assertEquals(16, e.bytesExpected)
            assertTrue(p.client.isClosed)
            // Comportement documenté : les lignes terminées sont écrites, le reste n'est pas touché.
            for (x in 2 until 6) assertEquals(desktopPixel(x, 3), fb.getPixel(x, 3))
            for (x in 2 until 6) assertEquals(black, fb.getPixel(x, 4))
            for (x in 2 until 6) assertEquals(black, fb.getPixel(x, 5))
        }

    @Test(timeout = 10_000)
    fun `a silent server in the middle of a rectangle times out`() = LoopbackPair(readTimeoutMs = 150).use { p ->
        val fb = Framebuffer(20, 10)
        p.send(rawXrgb(desktopRect(0, 0, 4, 1))) // une ligne sur trois

        assertThrows(RfbTransportException.ReadTimeout::class.java) {
            decoderFor(fb).decode(p.client, 0, 0, 4, 3, fb)
        }
        for (x in 0 until 4) assertEquals(desktopPixel(x, 0), fb.getPixel(x, 0))
    }

    // ------------------------------------------- chemins rapide et générique

    /** Format identique au format imposé mais d'un `depth` différent : force le chemin générique. */
    private val genericTwin = PixelFormat(32, 32, false, true, 255, 255, 255, 16, 8, 0)

    @Test(timeout = 20_000)
    fun `fast and generic paths produce the same pixels`() = LoopbackPair().use { p ->
        val random = Random(1234)
        val wire = ByteArray(64 * 32 * 4).also { random.nextBytes(it) } // octets quelconques, dont l'octet inutilisé
        val fast = Framebuffer(64, 32)
        val generic = Framebuffer(64, 32)
        p.send(wire)
        p.send(wire)

        decoderFor(fast).decode(p.client, 0, 0, 64, 32, fast)
        decoderFor(generic, genericTwin).decode(p.client, 0, 0, 64, 32, generic)

        assertArrayEquals(generic.pixels, fast.pixels)
    }

    @Test(timeout = 20_000)
    fun `the fast path agrees with the reference conversion pixel by pixel`() = LoopbackPair().use { p ->
        val random = Random(99)
        val wire = ByteArray(32 * 16 * 4).also { random.nextBytes(it) }
        val fb = Framebuffer(32, 16)
        p.send(wire)

        decoderFor(fb).decode(p.client, 0, 0, 32, 16, fb)

        for (i in 0 until 32 * 16) {
            assertEquals("pixel $i", PixelFormat.XRGB_8888_LE.decodePixel(wire, 4 * i), fb.pixels[i])
        }
    }

    @Test(timeout = 20_000)
    fun `the generic path decodes RGB565 in both endiannesses`() {
        val random = Random(5)
        for (bigEndian in listOf(false, true)) {
            LoopbackPair().use { p ->
                val format = PixelFormat(16, 16, bigEndian, true, 31, 63, 31, 11, 5, 0)
                val wire = ByteArray(16 * 8 * 2).also { random.nextBytes(it) }
                val fb = Framebuffer(16, 8)
                p.send(wire)

                decoderFor(fb, format).decode(p.client, 0, 0, 16, 8, fb)

                for (i in 0 until 16 * 8) assertEquals("be=$bigEndian pixel $i", format.decodePixel(wire, 2 * i), fb.pixels[i])
            }
        }
    }

    @Test(timeout = 10_000)
    fun `the generic path decodes one byte per pixel formats`() = LoopbackPair().use { p ->
        val format = PixelFormat(8, 8, false, true, 7, 7, 3, 0, 3, 6)
        val wire = ByteArray(8 * 4) { (it * 37).toByte() }
        val fb = Framebuffer(8, 4)
        p.send(wire)

        decoderFor(fb, format).decode(p.client, 0, 0, 8, 4, fb)

        for (i in 0 until 32) assertEquals("pixel $i", format.decodePixel(wire, i), fb.pixels[i])
    }

    // ------------------------------------------------------------ construction

    @Test
    fun `refuses a palette format and invalid widths`() {
        val palette = PixelFormat(8, 8, false, false, 0, 0, 0, 0, 0, 0)

        assertThrows(IllegalArgumentException::class.java) { RawDecoder(palette, 100) }
        assertThrows(IllegalArgumentException::class.java) { RawDecoder(PixelFormat.XRGB_8888_LE, 0) }
        assertThrows(IllegalArgumentException::class.java) { RawDecoder(PixelFormat.XRGB_8888_LE, Framebuffer.MAX_DIMENSION + 1) }
    }

    @Test(timeout = 10_000)
    fun `a rectangle wider than the decoder is a programming error`() {
        LoopbackPair().use { p ->
            val fb = Framebuffer(20, 10)

            assertThrows(IllegalArgumentException::class.java) {
                RawDecoder(PixelFormat.XRGB_8888_LE, 10).decode(p.client, 0, 0, 15, 1, fb)
            }
        }
    }

    @Test
    fun `identifies itself as the RAW encoding`() {
        assertEquals(Encoding.RAW, RawDecoder().encoding)
        assertEquals(0, RawDecoder().encoding)
    }
}
