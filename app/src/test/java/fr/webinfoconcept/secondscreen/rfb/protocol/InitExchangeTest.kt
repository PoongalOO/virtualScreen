package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.ascii
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** ClientInit / ServerInit : parsing pur des bornes, puis échange complet sur socket loopback. */
class InitExchangeTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    /** En-tête ServerInit de 24 octets. */
    private fun header(
        width: Int = 1280,
        height: Int = 800,
        pixelFormat: ByteArray = PixelFormatTest.BGRX_32,
        nameLength: Long = 0
    ): ByteArray = u16(width) + u16(height) + pixelFormat + u32(nameLength)

    private fun serverInit(name: String = "Desktop", width: Int = 1280, height: Int = 800) =
        header(width, height, nameLength = name.length.toLong()) + ascii(name)

    private fun assertBadSize(width: Int, height: Int) {
        val e = assertThrows(RfbProtocolException.InvalidFramebufferSize::class.java) {
            InitExchange.parseHeader(header(width, height))
        }
        assertEquals(width, e.width)
        assertEquals(height, e.height)
    }

    // ------------------------------------------------------------ parseHeader

    @Test
    fun `parses a nominal 1280x800 header`() {
        val h = InitExchange.parseHeader(header(1280, 800, nameLength = 7))

        assertEquals(1280, h.width)
        assertEquals(800, h.height)
        assertEquals(PixelFormat.parse(PixelFormatTest.BGRX_32), h.pixelFormat)
        assertEquals(7, h.nameLength)
    }

    @Test
    fun `accepts sizes up to the limits`() {
        for ((w, h) in listOf(1 to 1, 1280 to 800, 1920 to 1200, 1200 to 1920, 4096 to 562, 562 to 4096)) {
            val parsed = InitExchange.parseHeader(header(w, h))
            assertEquals(w, parsed.width)
            assertEquals(h, parsed.height)
        }
    }

    @Test
    fun `rejects zero dimensions`() {
        assertBadSize(0, 800)
        assertBadSize(1280, 0)
        assertBadSize(0, 0)
    }

    @Test
    fun `rejects sizes above the per axis limit`() {
        assertBadSize(4097, 100)
        assertBadSize(100, 4097)
        assertBadSize(65535, 1)
    }

    @Test
    fun `rejects sizes above the pixel area limit`() {
        assertBadSize(1921, 1200)   // 2 305 200 > 2 304 000
        assertBadSize(4096, 563)    // 2 306 048
        assertBadSize(2560, 1440)
        assertBadSize(3840, 2160)
    }

    @Test
    fun `huge sizes that would overflow an Int area are rejected`() {
        // 46341^2 dépasse Int.MAX_VALUE : calculée en Int la surface deviendrait négative. Le plafond
        // par axe l'interdit déjà ; la surface est en plus calculée en Long par sécurité.
        assertBadSize(65535, 65535)
        assertBadSize(46341, 46341)
        assertBadSize(65535, 32769)
    }

    @Test
    fun `size is validated before the pixel format`() {
        val e = assertThrows(RfbProtocolException::class.java) {
            InitExchange.parseHeader(header(0, 800, pixelFormat = ByteArray(16))) // format tout à zéro : invalide aussi
        }
        assertTrue(e is RfbProtocolException.InvalidFramebufferSize)
    }

    @Test
    fun `rejects an invalid pixel format`() {
        val bad = PixelFormatTest.wire(24, 24, 0, 1, 255, 255, 255, 16, 8, 0) // 24 bpp non supporté
        assertThrows(RfbProtocolException.InvalidPixelFormat::class.java) {
            InitExchange.parseHeader(header(pixelFormat = bad))
        }
    }

    @Test
    fun `name length limit is inclusive and unsigned`() {
        assertEquals(1024, InitExchange.parseHeader(header(nameLength = 1024)).nameLength)

        for (announced in listOf(1025L, 65536L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL)) {
            val e = assertThrows(RfbProtocolException.InvalidDesktopName::class.java) {
                InitExchange.parseHeader(header(nameLength = announced))
            }
            assertEquals(announced, e.announcedLength) // 0xFFFFFFFF reste positif
        }
    }

    @Test
    fun `header of the wrong size is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) { InitExchange.parseHeader(ByteArray(23)) }
        assertThrows(IllegalArgumentException::class.java) { InitExchange.parseHeader(ByteArray(25)) }
    }

    // ---------------------------------------------------------------- perform

    @Test(timeout = 10_000)
    fun `sends ClientInit first then reads ServerInit`() = LoopbackPair().use { p ->
        val sharedFlag = java.util.concurrent.atomic.AtomicInteger(-1)
        // Le serveur n'envoie ServerInit qu'après avoir reçu ClientInit : sans lui, blocage.
        val server = Thread {
            sharedFlag.set(p.peer.getInputStream().read())
            p.send(serverInit("Bureau test"))
        }
        server.start()

        val init = InitExchange.perform(p.client)
        server.join()

        assertEquals(1, sharedFlag.get())
        assertEquals(1280, init.width)
        assertEquals(800, init.height)
        assertEquals(PixelFormat.parse(PixelFormatTest.BGRX_32), init.pixelFormat)
        assertEquals("Bureau test", init.desktopName)
        assertTrue(p.client.isConnected)
    }

    @Test(timeout = 10_000)
    fun `shared flag is configurable`() {
        for ((shared, expected) in listOf(true to 1, false to 0)) {
            LoopbackPair().use { p ->
                p.send(serverInit())
                InitExchange.perform(p.client, shared)
                assertArrayEquals(byteArrayOf(expected.toByte()), p.receiveExactly(1))
            }
        }
    }

    @Test(timeout = 10_000)
    fun `empty desktop name is valid`() = LoopbackPair().use { p ->
        p.send(serverInit(""))

        assertEquals("", InitExchange.perform(p.client).desktopName)
    }

    @Test(timeout = 10_000)
    fun `longest allowed desktop name is read entirely`() = LoopbackPair().use { p ->
        val name = "N".repeat(InitExchange.MAX_NAME_LENGTH)
        p.send(serverInit(name))

        assertEquals(name, InitExchange.perform(p.client).desktopName)
    }

    @Test(timeout = 10_000)
    fun `desktop name is sanitized`() = LoopbackPair().use { p ->
        // 'A', ESC, 'é' en UTF-8 (2 octets), LF, 'Z'
        val name = byteArrayOf('A'.code.toByte(), 0x1B, 0xC3.toByte(), 0xA9.toByte(), '\n'.code.toByte(), 'Z'.code.toByte())
        p.send(header(nameLength = name.size.toLong()) + name)

        assertEquals("A????Z", InitExchange.perform(p.client).desktopName) // ESC, 2 octets UTF-8 et LF -> 4 '?'
    }

    @Test(timeout = 10_000)
    fun `leaves bytes after the name unread`() = LoopbackPair().use { p ->
        // Le serveur enchaîne avec un premier message (ici 4 octets quelconques).
        p.send(serverInit("srv") + byteArrayOf(0, 0, 0, 1))

        InitExchange.perform(p.client)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), next)
    }

    @Test(timeout = 20_000)
    fun `survives TCP fragmentation`() {
        val stream = serverInit("Fragmented desktop")
        for (chunk in listOf(1, 2, 3, 7, 24)) {
            LoopbackPair().use { p ->
                val writer = Thread { p.sendFragmented(stream, chunk) }
                writer.start()

                val init = InitExchange.perform(p.client)

                writer.join()
                assertEquals("fragments de $chunk", "Fragmented desktop", init.desktopName)
                assertEquals(1280, init.width)
            }
        }
    }

    // ---------------------------------------------------- erreurs et sécurité

    @Test(timeout = 10_000)
    fun `invalid size closes the socket and does not wait for the name`() =
        LoopbackPair(readTimeoutMs = 1_500).use { p ->
            // Seulement l'en-tête : si le client cherchait à lire un nom il attendrait le timeout.
            p.send(header(width = 0, nameLength = 5))

            val start = System.nanoTime()
            assertThrows(RfbProtocolException.InvalidFramebufferSize::class.java) { InitExchange.perform(p.client) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue("attente inutile : $elapsedMs ms", elapsedMs < 1_000)
            assertTrue(p.client.isClosed)
        }

    @Test(timeout = 10_000)
    fun `oversized desktop name is rejected without reading it`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        p.send(header(nameLength = 0xFFFFFFFFL)) // ~4 Gio annoncés, aucun octet envoyé

        val start = System.nanoTime()
        val e = assertThrows(RfbProtocolException.InvalidDesktopName::class.java) { InitExchange.perform(p.client) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(0xFFFFFFFFL, e.announcedLength)
        assertTrue("lecture tentée : $elapsedMs ms", elapsedMs < 1_000)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `invalid pixel format closes the socket`() = LoopbackPair().use { p ->
        p.send(header(pixelFormat = PixelFormatTest.wire(24, 24, 0, 1, 255, 255, 255, 16, 8, 0)))

        assertThrows(RfbProtocolException.InvalidPixelFormat::class.java) { InitExchange.perform(p.client) }
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `error messages never contain the desktop name`() = LoopbackPair().use { p ->
        p.send(header(width = 0, nameLength = 6) + ascii("SECRET"))

        val e = assertThrows(RfbProtocolException.InvalidFramebufferSize::class.java) { InitExchange.perform(p.client) }

        assertFalse(e.message!!.contains("SECRET"))
    }

    @Test(timeout = 10_000)
    fun `server closing in the middle of the header is an end of stream`() = LoopbackPair().use { p ->
        p.send(serverInit().copyOfRange(0, 10))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { InitExchange.perform(p.client) }

        assertEquals(10, e.bytesRead)
        assertEquals(24, e.bytesExpected)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `server closing in the middle of the name is an end of stream`() = LoopbackPair().use { p ->
        p.send(header(nameLength = 10) + ascii("abc"))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { InitExchange.perform(p.client) }

        assertEquals(3, e.bytesRead)
        assertEquals(10, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `silent server times out and the socket is closed`() = LoopbackPair(readTimeoutMs = 150).use { p ->
        assertThrows(RfbTransportException.ReadTimeout::class.java) { InitExchange.perform(p.client) }
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `limits are consistent with the nominal 1280x800 desktop`() {
        assertTrue(1280L * 800L <= InitExchange.MAX_PIXELS)
        assertTrue(1280 <= InitExchange.MAX_DIMENSION && 800 <= InitExchange.MAX_DIMENSION)
    }
}
