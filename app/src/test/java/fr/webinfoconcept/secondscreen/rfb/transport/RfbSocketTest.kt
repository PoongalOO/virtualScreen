package fr.webinfoconcept.secondscreen.rfb.transport

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
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Tests de [RfbSocket] sur de vraies sockets loopback (aucun appareil requis). */
class RfbSocketTest {

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

    private fun newServer(): ServerSocket =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { toClose += it }

    private fun newClient(
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000,
        factory: () -> Socket = { Socket() }
    ): RfbSocket = RfbSocket(connectTimeoutMs, readTimeoutMs, factory).also { toClose += it }

    /** Connecte un client et accepte côté serveur (le backlog suffit, pas de thread). */
    private fun connectedPair(
        readTimeoutMs: Int = 2_000
    ): Pair<RfbSocket, Socket> {
        val server = newServer()
        val client = newClient(readTimeoutMs = readTimeoutMs)
        client.connect("127.0.0.1", server.localPort)
        val peer = server.accept().also {
            it.tcpNoDelay = true
            toClose += it
        }
        return client to peer
    }

    private fun readFully(client: RfbSocket, length: Int): ByteArray =
        ByteArray(length).also { client.readFully(it, 0, length) }

    // --- lecture ---

    @Test(timeout = 10_000)
    fun `readFully returns exactly the requested bytes`() {
        val (client, peer) = connectedPair()
        peer.getOutputStream().apply { write(byteArrayOf(1, 2, 3, 4)); flush() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readFully(client, 4))
    }

    @Test(timeout = 10_000)
    fun `readFully reassembles a message fragmented byte by byte`() {
        val (client, peer) = connectedPair()
        val message = ByteArray(16) { (it * 3).toByte() }
        val writer = Thread {
            val out = peer.getOutputStream()
            for (b in message) {
                out.write(b.toInt())
                out.flush()
                Thread.sleep(3)
            }
        }
        writer.start()

        assertArrayEquals(message, readFully(client, message.size))
        writer.join()
    }

    @Test(timeout = 10_000)
    fun `successive reads stay aligned on message boundaries`() {
        val (client, peer) = connectedPair()
        peer.getOutputStream().apply { write(byteArrayOf(10, 11, 12, 13, 14, 15)); flush() }

        assertArrayEquals(byteArrayOf(10, 11), readFully(client, 2))
        assertArrayEquals(byteArrayOf(12, 13, 14, 15), readFully(client, 4))
    }

    @Test(timeout = 10_000)
    fun `readFully honours offset and leaves other bytes untouched`() {
        val (client, peer) = connectedPair()
        peer.getOutputStream().apply { write(byteArrayOf(7, 8)); flush() }
        val buffer = byteArrayOf(-1, -1, -1, -1)

        client.readFully(buffer, 1, 2)

        assertArrayEquals(byteArrayOf(-1, 7, 8, -1), buffer)
    }

    @Test(timeout = 10_000)
    fun `readFully of zero bytes returns immediately`() {
        val (client, _) = connectedPair()
        client.readFully(ByteArray(0), 0, 0)
    }

    // --- erreurs de lecture ---

    @Test(timeout = 10_000)
    fun `end of stream reports progress and closes the socket`() {
        val (client, peer) = connectedPair()
        peer.getOutputStream().apply { write(byteArrayOf(1, 2, 3)); flush() }
        peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { readFully(client, 8) }

        assertEquals(3, e.bytesRead)
        assertEquals(8, e.bytesExpected)
        assertTrue(client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `read timeout at a message boundary is recoverable`() {
        val (client, peer) = connectedPair(readTimeoutMs = 100)

        val e = assertThrows(RfbTransportException.ReadTimeout::class.java) { readFully(client, 4) }
        assertEquals(0, e.bytesRead)
        assertFalse(client.isClosed)

        peer.getOutputStream().apply { write(byteArrayOf(9, 9, 9, 9)); flush() }
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), readFully(client, 4))
    }

    @Test(timeout = 10_000)
    fun `read timeout reports partially read bytes`() {
        val (client, peer) = connectedPair(readTimeoutMs = 100)
        peer.getOutputStream().apply { write(byteArrayOf(1, 2)); flush() }

        val e = assertThrows(RfbTransportException.ReadTimeout::class.java) { readFully(client, 4) }

        assertEquals(2, e.bytesRead)
        assertEquals(4, e.bytesExpected)
    }

    @Test(timeout = 10_000)
    fun `setReadTimeout applies to an established connection`() {
        val (client, _) = connectedPair(readTimeoutMs = 0)
        client.setReadTimeout(100)

        assertThrows(RfbTransportException.ReadTimeout::class.java) { readFully(client, 1) }
    }

    // --- erreurs de connexion ---

    @Test(timeout = 10_000)
    fun `connection refused is typed and closes the socket`() {
        val server = newServer()
        val port = server.localPort
        server.close()
        val client = newClient()

        assertThrows(RfbTransportException.ConnectionRefused::class.java) {
            client.connect("127.0.0.1", port)
        }
        assertTrue(client.isClosed)
    }

    private fun clientFailingWith(error: IOException): RfbSocket = newClient(factory = {
        object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                throw error
            }
        }
    })

    @Test(timeout = 10_000)
    fun `android style ECONNREFUSED message is reported as refused`() {
        val client = clientFailingWith(
            java.net.ConnectException(
                "failed to connect to /192.168.1.10 (port 5900): connect failed: ECONNREFUSED (Connection refused)"
            )
        )

        assertThrows(RfbTransportException.ConnectionRefused::class.java) {
            client.connect("127.0.0.1", 5900)
        }
    }

    @Test(timeout = 10_000)
    fun `android style ENETUNREACH message is not reported as refused`() {
        val client = clientFailingWith(
            java.net.ConnectException(
                "failed to connect to /192.168.1.10 (port 5900): connect failed: ENETUNREACH (Network is unreachable)"
            )
        )

        assertThrows(RfbTransportException.ConnectFailed::class.java) {
            client.connect("127.0.0.1", 5900)
        }
    }

    @Test(timeout = 10_000)
    fun `connect timeout is typed`() {
        val client = newClient(connectTimeoutMs = 1234, factory = {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    assertEquals(1234, timeout)
                    throw SocketTimeoutException("timed out")
                }
            }
        })

        val e = assertThrows(RfbTransportException.ConnectTimeout::class.java) {
            client.connect("127.0.0.1", 5900)
        }

        assertEquals(1234, e.timeoutMs)
        assertTrue(client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `unreachable network is reported as connect failed`() {
        val client = newClient(factory = {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    throw NoRouteToHostException("no route")
                }
            }
        })

        assertThrows(RfbTransportException.ConnectFailed::class.java) {
            client.connect("127.0.0.1", 5900)
        }
    }

    @Test(timeout = 30_000)
    fun `unknown host is typed`() {
        // Le TLD ".invalid" est réservé (RFC 6761) : jamais résolvable.
        val client = newClient()

        val e = assertThrows(RfbTransportException.UnknownHost::class.java) {
            client.connect("nonexistent.invalid", 5900)
        }

        assertEquals("nonexistent.invalid", e.host)
        assertTrue(client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `underlying socket is closed when connect fails`() {
        var created: Socket? = null
        val client = newClient(factory = {
            object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    throw SocketTimeoutException("timed out")
                }
            }.also { created = it }
        })

        assertThrows(RfbTransportException.ConnectTimeout::class.java) {
            client.connect("127.0.0.1", 5900)
        }

        assertTrue(created!!.isClosed)
    }

    // --- fermeture ---

    @Test(timeout = 10_000)
    fun `close is idempotent and later operations report Closed`() {
        val (client, _) = connectedPair()

        client.close()
        client.close()

        assertTrue(client.isClosed)
        assertFalse(client.isConnected)
        assertThrows(RfbTransportException.Closed::class.java) { readFully(client, 1) }
        assertThrows(RfbTransportException.Closed::class.java) { client.write(byteArrayOf(1), 0, 1) }
        assertThrows(RfbTransportException.Closed::class.java) { client.connect("127.0.0.1", 5900) }
    }

    @Test(timeout = 10_000)
    fun `close on a never connected socket is harmless`() {
        val client = newClient()
        client.close()
        client.close()
        assertTrue(client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `operations before connect report Closed`() {
        val client = newClient()

        assertThrows(RfbTransportException.Closed::class.java) { readFully(client, 1) }
        assertThrows(RfbTransportException.Closed::class.java) { client.write(byteArrayOf(1), 0, 1) }
    }

    @Test(timeout = 10_000)
    fun `close from another thread unblocks a pending read`() {
        val (client, _) = connectedPair(readTimeoutMs = 0)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            started.countDown()
            try {
                readFully(client, 1)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        reader.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // laisse le lecteur se bloquer dans read()

        client.close()
        reader.join(5_000)

        assertFalse(reader.isAlive)
        assertTrue("attendu Closed, obtenu ${failure.get()}", failure.get() is RfbTransportException.Closed)
    }

    @Test(timeout = 10_000)
    fun `connect twice is rejected`() {
        val server = newServer()
        val client = newClient()
        client.connect("127.0.0.1", server.localPort)

        assertThrows(IllegalStateException::class.java) { client.connect("127.0.0.1", server.localPort) }
    }

    // --- écriture ---

    @Test(timeout = 10_000)
    fun `write sends exactly the requested slice`() {
        val (client, peer) = connectedPair()

        client.write(byteArrayOf(0, 5, 6, 7, 0), 1, 3)

        val received = ByteArray(3)
        var read = 0
        while (read < 3) read += peer.getInputStream().read(received, read, 3 - read)
        assertArrayEquals(byteArrayOf(5, 6, 7), received)
    }

    @Test(timeout = 10_000)
    fun `write on a peer that reset the connection closes the socket`() {
        val (client, peer) = connectedPair()
        peer.setSoLinger(true, 0) // provoque un RST à la fermeture
        peer.close()

        // Le premier envoi peut réussir (tampon local) : on insiste jusqu'à l'échec.
        val payload = ByteArray(64 * 1024)
        val e = assertThrows(RfbTransportException::class.java) {
            repeat(200) { client.write(payload, 0, payload.size) }
        }

        assertTrue(e is RfbTransportException.Io || e is RfbTransportException.Closed)
        assertTrue(client.isClosed)
    }

    // --- validation des arguments ---

    @Test
    fun `connect rejects invalid host and port`() {
        val client = newClient()

        assertThrows(IllegalArgumentException::class.java) { client.connect("", 5900) }
        assertThrows(IllegalArgumentException::class.java) { client.connect("  ", 5900) }
        assertThrows(IllegalArgumentException::class.java) { client.connect("127.0.0.1", 0) }
        assertThrows(IllegalArgumentException::class.java) { client.connect("127.0.0.1", 65536) }
    }

    @Test(timeout = 10_000)
    fun `buffer bounds are validated without integer overflow`() {
        val (client, _) = connectedPair()
        val buffer = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) { client.readFully(buffer, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { client.readFully(buffer, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { client.readFully(buffer, 2, 3) }
        assertThrows(IllegalArgumentException::class.java) { client.readFully(buffer, Int.MAX_VALUE, 2) }
        assertThrows(IllegalArgumentException::class.java) { client.write(buffer, 3, Int.MAX_VALUE) }
        assertTrue(client.isConnected) // une erreur d'argument ne ferme pas la connexion
    }

    @Test
    fun `constructor rejects invalid timeouts`() {
        assertThrows(IllegalArgumentException::class.java) { RfbSocket(connectTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { RfbSocket(readTimeoutMs = -1) }
    }
}
