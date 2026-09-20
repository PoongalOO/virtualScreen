package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Négociation de version de bout en bout sur des sockets loopback (aucun appareil requis). */
class ProtocolVersionNegotiationTest {

    private val toClose = mutableListOf<Closeable>()

    @After
    fun tearDown() {
        toClose.forEach {
            try {
                it.close()
            } catch (ignored: IOException) {
            }
        }
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

    private class Pair(val client: RfbSocket, val peer: Socket)

    private fun connectedPair(readTimeoutMs: Int = 2_000): Pair {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { toClose += it }
        val client = RfbSocket(2_000, readTimeoutMs).also { toClose += it }
        client.connect("127.0.0.1", server.localPort)
        val peer = server.accept().also {
            it.tcpNoDelay = true
            toClose += it
        }
        return Pair(client, peer)
    }

    private fun Socket.send(bytes: ByteArray) {
        getOutputStream().apply { write(bytes); flush() }
    }

    /** Lit ce que le client a envoyé jusqu'à la fermeture de sa socket (EOF). */
    private fun Socket.receiveUntilEof(): ByteArray {
        soTimeout = 2_000
        return getInputStream().readBytes()
    }

    private fun Socket.receiveExactly(n: Int): ByteArray {
        soTimeout = 2_000
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = getInputStream().read(out, read, n - read)
            check(r >= 0) { "EOF prématuré" }
            read += r
        }
        return out
    }

    // --- cas nominaux ---

    @Test(timeout = 10_000)
    fun `negotiates 3_8 with a 3_8 server and replies with the 3_8 banner`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 003.008\n"))

        assertEquals(RfbVersion.V3_8, ProtocolVersion.negotiate(p.client))

        assertArrayEquals(ascii("RFB 003.008\n"), p.peer.receiveExactly(12))
        assertTrue(p.client.isConnected)
    }

    @Test(timeout = 10_000)
    fun `negotiates 3_3 with a 3_3 server`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 003.003\n"))

        assertEquals(RfbVersion.V3_3, ProtocolVersion.negotiate(p.client))

        assertArrayEquals(ascii("RFB 003.003\n"), p.peer.receiveExactly(12))
    }

    @Test(timeout = 10_000)
    fun `falls back to 3_3 for a 3_7 server`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 003.007\n"))

        assertEquals(RfbVersion.V3_3, ProtocolVersion.negotiate(p.client))

        assertArrayEquals(ascii("RFB 003.003\n"), p.peer.receiveExactly(12))
    }

    @Test(timeout = 10_000)
    fun `answers 3_8 to an Apple 3_889 server`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 003.889\n"))

        assertEquals(RfbVersion.V3_8, ProtocolVersion.negotiate(p.client))

        assertArrayEquals(ascii("RFB 003.008\n"), p.peer.receiveExactly(12))
    }

    // --- fragmentation TCP (TESTS.md : fragments de 1, 2, 3, N octets) ---

    @Test(timeout = 20_000)
    fun `reassembles a banner fragmented in chunks of every size`() {
        for (chunk in listOf(1, 2, 3, 5, 12)) {
            val p = connectedPair()
            val banner = ascii("RFB 003.008\n")
            val writer = Thread {
                for (from in banner.indices step chunk) {
                    p.peer.send(banner.copyOfRange(from, minOf(from + chunk, banner.size)))
                    Thread.sleep(5)
                }
            }
            writer.start()

            assertEquals("fragments de $chunk", RfbVersion.V3_8, ProtocolVersion.negotiate(p.client))

            writer.join()
            assertArrayEquals(ascii("RFB 003.008\n"), p.peer.receiveExactly(12))
        }
    }

    @Test(timeout = 10_000)
    fun `does not consume bytes that follow the banner`() {
        val p = connectedPair()
        // Le serveur enchaîne immédiatement avec l'étape suivante (ex. types de sécurité).
        p.peer.send(ascii("RFB 003.008\n") + byteArrayOf(1, 2, 0x99.toByte(), 4))

        assertEquals(RfbVersion.V3_8, ProtocolVersion.negotiate(p.client))

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(1, 2, 0x99.toByte(), 4), next)
    }

    // --- erreurs : la socket est fermée et rien n'est envoyé au serveur ---

    @Test(timeout = 10_000)
    fun `invalid banner closes the socket and sends nothing`() {
        val p = connectedPair()
        p.peer.send(ascii("SSH-2.0-Open"))

        assertThrows(RfbProtocolException.InvalidBanner::class.java) { ProtocolVersion.negotiate(p.client) }

        assertTrue(p.client.isClosed)
        assertEquals(0, p.peer.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `unsupported version closes the socket and sends nothing`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 003.002\n"))

        val e = assertThrows(RfbProtocolException.UnsupportedVersion::class.java) {
            ProtocolVersion.negotiate(p.client)
        }

        assertEquals(3, e.major)
        assertEquals(2, e.minor)
        assertTrue(p.client.isClosed)
        assertEquals(0, p.peer.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `server closing in the middle of the banner is an end of stream`() {
        val p = connectedPair()
        p.peer.send(ascii("RFB 0"))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { ProtocolVersion.negotiate(p.client) }

        assertEquals(5, e.bytesRead)
        assertEquals(12, e.bytesExpected)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `silent server times out and the socket is closed`() {
        val p = connectedPair(readTimeoutMs = 150)

        val e = assertThrows(RfbTransportException.ReadTimeout::class.java) { ProtocolVersion.negotiate(p.client) }

        assertEquals(0, e.bytesRead)
        assertTrue(p.client.isClosed)
        assertEquals(0, p.peer.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `negotiating on a closed socket reports Closed`() {
        val p = connectedPair()
        p.client.close()

        assertThrows(RfbTransportException.Closed::class.java) { ProtocolVersion.negotiate(p.client) }
        assertFalse(p.client.isConnected)
    }
}
