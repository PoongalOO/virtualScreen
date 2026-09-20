package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.reasonString
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Négociation de sécurité sur socket loopback, RFB 3.8 et 3.3. */
class SecurityNegotiationTest {

    /** Simule VNC Authentication : lit un challenge de 16 octets, répond challenge+1 octet par octet. */
    private class FakeChallengeHandler : SecurityHandler {
        override val type = SecurityType.VNC_AUTH
        var calls = 0

        override fun authenticate(socket: RfbSocket) {
            calls++
            val challenge = ByteArray(16)
            socket.readFully(challenge, 0, 16)
            socket.write(ByteArray(16) { (challenge[it] + 1).toByte() }, 0, 16)
        }
    }

    private val challenge = ByteArray(16) { it.toByte() }
    private val expectedResponse = ByteArray(16) { (it + 1).toByte() }

    private fun negotiate38(p: LoopbackPair, vararg handlers: SecurityHandler) =
        SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, handlers.toList())

    private fun negotiate33(p: LoopbackPair, vararg handlers: SecurityHandler) =
        SecurityNegotiation.negotiate(p.client, RfbVersion.V3_3, handlers.toList())

    // ---------------------------------------------------------------- RFB 3.8

    @Test(timeout = 10_000)
    fun `3_8 None - selects type 1 and reads the security result`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(1, SecurityType.NONE.toByte()) + u32(0))

        assertSame(NoneSecurity, negotiate38(p, NoneSecurity))

        assertArrayEquals(byteArrayOf(1), p.receiveExactly(1)) // choix du client
        assertTrue(p.client.isConnected)
    }

    @Test(timeout = 10_000)
    fun `3_8 picks the type the client supports among several offered`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(3, 19, 2, 1) + u32(0)) // 19 et 2 inconnus du client

        assertSame(NoneSecurity, negotiate38(p, NoneSecurity))

        assertArrayEquals(byteArrayOf(1), p.receiveExactly(1))
    }

    @Test(timeout = 10_000)
    fun `3_8 follows the client preference order not the server order`() = LoopbackPair().use { p ->
        val vnc = FakeChallengeHandler()
        // Le serveur liste None avant VNC ; le client préfère VNC.
        p.send(byteArrayOf(2, 1, 2) + challenge + u32(0))

        assertSame(vnc, negotiate38(p, vnc, NoneSecurity))

        assertEquals(1, vnc.calls)
        assertArrayEquals(byteArrayOf(2) + expectedResponse, p.receiveExactly(17)) // type puis réponse
    }

    @Test(timeout = 10_000)
    fun `3_8 authentication runs after the choice and before the security result`() = LoopbackPair().use { p ->
        val order = mutableListOf<String>()
        val handler = object : SecurityHandler {
            override val type = SecurityType.VNC_AUTH
            override fun authenticate(socket: RfbSocket) {
                order += "authenticate"
                // Le type choisi a déjà été envoyé : le serveur ne peut le lire qu'ici.
                order += "type-sent=" + p.receiveExactly(1)[0]
                p.send(u32(0)) // le serveur n'envoie le résultat qu'après avoir vu le type
            }
        }
        p.send(byteArrayOf(1, 2))

        assertSame(handler, negotiate38(p, handler))
        assertEquals(listOf("authenticate", "type-sent=2"), order)
    }

    @Test(timeout = 10_000)
    fun `3_8 no supported type reports the offered list and sends nothing`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(2, 19, 5))

        val e = assertThrows(RfbProtocolException.NoSupportedSecurityType::class.java) {
            negotiate38(p, NoneSecurity)
        }

        assertEquals(listOf(19L, 5L), e.offered)
        assertTrue(p.client.isClosed)
        assertEquals(0, p.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `3_8 zero types means the server rejected the connection with a reason`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(0) + reasonString("Too many authentication failures"))

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("Too many authentication failures", e.reason)
        assertTrue(p.client.isClosed)
        assertEquals(0, p.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `3_8 security result failure carries the reason and closes the socket`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(1, 1) + u32(1) + reasonString("Authentication failed"))

        val e = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("Authentication failed", e.reason)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `3_8 failure without a reason string still reports authentication failed`() = LoopbackPair().use { p ->
        // Serveur non conforme qui coupe juste après le résultat négatif.
        p.send(byteArrayOf(1, 1) + u32(1))
        p.peer.shutdownOutput()

        val e = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("", e.reason)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `3_8 unknown security result value is a protocol error`() {
        for (value in listOf(2L, 0x100L, 0x80000000L, 0xFFFFFFFFL)) {
            LoopbackPair().use { p ->
                p.send(byteArrayOf(1, 1) + u32(value))

                assertThrows("valeur $value", RfbProtocolException.InvalidSecurityResult::class.java) {
                    negotiate38(p, NoneSecurity)
                }
                assertTrue(p.client.isClosed)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `3_8 leaves bytes after the security result unread`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(1, 1) + u32(0) + byteArrayOf(7, 8, 9, 10)) // 4 octets de ServerInit

        negotiate38(p, NoneSecurity)

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(7, 8, 9, 10), next)
    }

    @Test(timeout = 20_000)
    fun `3_8 handshake survives TCP fragmentation`() {
        val stream = byteArrayOf(1, 1) + u32(0)
        for (chunk in listOf(1, 2, 3, 5)) {
            LoopbackPair().use { p ->
                val writer = Thread { p.sendFragmented(stream, chunk) }
                writer.start()

                assertSame("fragments de $chunk", NoneSecurity, negotiate38(p, NoneSecurity))

                writer.join()
            }
        }
    }

    @Test(timeout = 10_000)
    fun `3_8 fragmented rejection reason is reassembled`() = LoopbackPair().use { p ->
        val writer = Thread { p.sendFragmented(byteArrayOf(0) + reasonString("Bad password"), 1) }
        writer.start()

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("Bad password", e.reason)
        writer.join()
    }

    // ------------------------------------------------------ texte de raison

    @Test(timeout = 10_000)
    fun `reason is sanitized to printable ASCII`() = LoopbackPair().use { p ->
        val evil = byteArrayOf('O'.code.toByte(), 0x1B, '['.code.toByte(), 0xC3.toByte(), 0xA9.toByte(), '\n'.code.toByte(), 'K'.code.toByte(), 0)
        p.send(byteArrayOf(0) + u32(evil.size.toLong()) + evil)

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("O?[???K?", e.reason) // ESC, é (2 octets UTF-8), LF, NUL -> '?'
    }

    @Test(timeout = 10_000)
    fun `huge announced reason length is bounded to the maximum`() = LoopbackPair().use { p ->
        val filler = ByteArray(SecurityNegotiation.MAX_REASON_LENGTH + 100) { 'A'.code.toByte() }
        p.send(byteArrayOf(0) + u32(0xFFFFFFFFL) + filler)

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("A".repeat(SecurityNegotiation.MAX_REASON_LENGTH), e.reason)
    }

    @Test(timeout = 10_000)
    fun `huge announced reason length with no data yields an empty reason`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(0) + u32(0xFFFFFFFFL))
        p.peer.shutdownOutput()

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals("", e.reason)
    }

    @Test(timeout = 10_000)
    fun `error messages never contain the server text`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(0) + reasonString("SECRET-SERVER-TEXT"))

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate38(p, NoneSecurity) }

        assertTrue(!e.message!!.contains("SECRET"))
    }

    // ---------------------------------------------------------------- RFB 3.3

    @Test(timeout = 10_000)
    fun `3_3 None - no choice sent and no security result read`() = LoopbackPair().use { p ->
        p.send(u32(1) + byteArrayOf(9, 9, 9, 9)) // type None puis début de ServerInit

        assertSame(NoneSecurity, negotiate33(p, NoneSecurity))

        val next = ByteArray(4)
        p.client.readFully(next, 0, 4)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), next) // rien consommé au-delà du type
        p.client.close()
        assertEquals(0, p.receiveUntilEof().size)        // et rien envoyé
    }

    @Test(timeout = 10_000)
    fun `3_3 VNC authentication - challenge response then security result, no choice byte`() =
        LoopbackPair().use { p ->
            val vnc = FakeChallengeHandler()
            p.send(u32(2) + challenge + u32(0))

            assertSame(vnc, negotiate33(p, vnc))

            assertArrayEquals(expectedResponse, p.receiveExactly(16)) // uniquement la réponse
        }

    @Test(timeout = 10_000)
    fun `3_3 authentication failure has no reason string`() = LoopbackPair(readTimeoutMs = 1_500).use { p ->
        val vnc = FakeChallengeHandler()
        p.send(u32(2) + challenge + u32(1)) // et rien après

        val start = System.nanoTime()
        val e = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) { negotiate33(p, vnc) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals("", e.reason)
        // Si le client avait tenté de lire une raison, il aurait attendu le timeout de 1,5 s.
        assertTrue("attente inutile d'une raison : $elapsedMs ms", elapsedMs < 1_000)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `3_3 type 0 means rejection with a reason`() = LoopbackPair().use { p ->
        p.send(u32(0) + reasonString("Server busy"))

        val e = assertThrows(RfbProtocolException.ConnectionRejected::class.java) { negotiate33(p, NoneSecurity) }

        assertEquals("Server busy", e.reason)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `3_3 type not supported by the client`() = LoopbackPair().use { p ->
        p.send(u32(2)) // VNC Auth imposé mais seul None est supporté

        val e = assertThrows(RfbProtocolException.NoSupportedSecurityType::class.java) {
            negotiate33(p, NoneSecurity)
        }

        assertEquals(listOf(2L), e.offered)
        assertTrue(p.client.isClosed)
        assertEquals(0, p.receiveUntilEof().size)
    }

    @Test(timeout = 10_000)
    fun `3_3 out of range type is reported unsigned`() {
        for (value in listOf(99L, 0x80000000L, 0xFFFFFFFFL)) {
            LoopbackPair().use { p ->
                p.send(u32(value))

                val e = assertThrows(RfbProtocolException.NoSupportedSecurityType::class.java) {
                    negotiate33(p, NoneSecurity)
                }

                assertEquals(listOf(value), e.offered)
            }
        }
    }

    // ------------------------------------------------- erreurs et robustesse

    @Test(timeout = 10_000)
    fun `server closing before sending anything is an end of stream`() = LoopbackPair().use { p ->
        p.peer.close()

        assertThrows(RfbTransportException.EndOfStream::class.java) { negotiate38(p, NoneSecurity) }
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `silent server times out and the socket is closed`() = LoopbackPair(readTimeoutMs = 150).use { p ->
        assertThrows(RfbTransportException.ReadTimeout::class.java) { negotiate38(p, NoneSecurity) }
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `truncated type list is an end of stream`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(5, 1, 2)) // annonce 5 types, n'en envoie que 2
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { negotiate38(p, NoneSecurity) }

        assertEquals(2, e.bytesRead)
        assertEquals(5, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `a failing handler closes the socket and propagates its error`() = LoopbackPair().use { p ->
        val failing = object : SecurityHandler {
            override val type = SecurityType.VNC_AUTH
            override fun authenticate(socket: RfbSocket) = throw IOException("boom")
        }
        p.send(byteArrayOf(1, 2))

        val e = assertThrows(IOException::class.java) { negotiate38(p, failing) }

        assertEquals("boom", e.message)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `invalid handler configuration is rejected`() = LoopbackPair().use { p ->
        assertThrows(IllegalArgumentException::class.java) { negotiate38(p) } // liste vide
        val zero = object : SecurityHandler {
            override val type = 0
            override fun authenticate(socket: RfbSocket) = Unit
        }
        val tooBig = object : SecurityHandler {
            override val type = 256
            override fun authenticate(socket: RfbSocket) = Unit
        }
        assertThrows(IllegalArgumentException::class.java) { negotiate38(p, zero) }
        assertThrows(IllegalArgumentException::class.java) { negotiate38(p, tooBig) }
        assertTrue(p.client.isConnected) // erreur de programmation : la connexion n'est pas touchée
    }

    @Test
    fun `security type constants match RFC 6143`() {
        assertEquals(0, SecurityType.INVALID)
        assertEquals(1, SecurityType.NONE)
        assertEquals(2, SecurityType.VNC_AUTH)
        assertEquals(1, NoneSecurity.type)
    }
}
