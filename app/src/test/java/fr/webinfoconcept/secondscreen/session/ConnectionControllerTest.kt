package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.render.RenderTarget
import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.Encoding
import fr.webinfoconcept.secondscreen.rfb.protocol.EncodingMode
import fr.webinfoconcept.secondscreen.rfb.protocol.RectangleListener
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityType
import fr.webinfoconcept.secondscreen.rfb.testutil.FakeRfbServer
import fr.webinfoconcept.secondscreen.rfb.testutil.ServerSession
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopPixel
import fr.webinfoconcept.secondscreen.rfb.testutil.u16
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cycle de vie d'une connexion contre un faux serveur RFB sur loopback (SS-050 à SS-054) : états, handshake, mises à
 * jour, entrées, erreurs typées, coupure, reconnexion, secrets. Délais raccourcis (config de test) mais toujours
 * attendus par événement, jamais par pause fixe.
 */
class ConnectionControllerTest {

    private val fast = ConnectionConfig(
        connectTimeoutMs = 2_000, handshakeTimeoutMs = 2_000, readTimeoutMs = 100,
        livenessTimeoutMs = 700, keepAliveIntervalMs = 20, heartbeatIntervalMs = 100
    )

    private val servers = mutableListOf<FakeRfbServer>()
    private val controllers = mutableListOf<ConnectionController>()

    @After
    fun tearDown() {
        controllers.forEach { it.disconnect() }
        controllers.forEach { it.awaitIdle(2_000) }
        servers.forEach { it.close() }
    }

    private fun server(script: (ServerSession) -> Unit) = FakeRfbServer(script).also { servers += it }
    private fun controller() = ConnectionController(fast).also { controllers += it }
    private fun params(s: FakeRfbServer) = ConnectionParams("127.0.0.1", s.port)

    /** Enregistre les changements d'état et permet d'attendre un état. */
    private class Recorder : ConnectionController.Listener {
        val states = CopyOnWriteArrayList<ConnectionState>()
        val failures = CopyOnWriteArrayList<ConnectionFailure?>()
        private val lock = Object()
        override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) {
            states += state; failures += failure
            synchronized(lock) { lock.notifyAll() }
        }
        fun await(target: ConnectionState, timeoutMs: Long = 8_000, from: Int = 0): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            synchronized(lock) {
                while (states.drop(from).none { it == target }) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return false
                    lock.wait(left)
                }
            }
            return true
        }
        /** Chaque paire d'états consécutifs est une transition légale (ou une répétition). */
        fun assertLegalSequence() {
            val s = states.toList()
            for (i in 1 until s.size) {
                assertTrue("transition illégale ${s[i - 1]} -> ${s[i]} dans $s", s[i - 1] == s[i] || s[i - 1].canTransitionTo(s[i]))
            }
        }
    }

    private class StubTarget : RenderTarget {
        val rects = CopyOnWriteArrayList<List<Int>>()
        val updates = AtomicInteger()
        override val rectangleListener = object : RectangleListener {
            override fun onRectangle(x: Int, y: Int, w: Int, h: Int) { rects += listOf(x, y, w, h) }
        }
        override fun onFramebufferUpdated() { updates.incrementAndGet() }
    }

    /** Threads de session déjà vivants au début du test (laissés par d'autres classes de tests) : ignorés. */
    private val baseline: Set<Thread> = sessionThreads()

    private fun sessionThreads(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name in setOf("secondscreen-session", "secondscreen-keepalive", "secondscreen-input") }
        .toSet()

    /** Threads de ce contrôleur encore vivants : aucun ne doit rester après une déconnexion. */
    private fun leftBehind(): Int = (sessionThreads() - baseline).size

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    // ============================================================ chemin nominal

    @Test(timeout = 20_000)
    fun `connects to a server without authentication and reports every state in order`() {
        val setup = java.util.concurrent.atomic.AtomicReference<fr.webinfoconcept.secondscreen.rfb.testutil.ClientSetup>()
        val s = server { it.apply { setup.set(standardHandshake(64, 48, "bureau")); startCollecting(); Thread.sleep(3_000) } }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        assertTrue(c.connect(params(s)))
        assertTrue(rec.await(ConnectionState.CONNECTED))

        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.NEGOTIATING, ConnectionState.CONNECTED), rec.states.toList())
        rec.assertLegalSequence()
        val info = c.session!!
        assertEquals(RfbVersion.V3_8, info.version)
        assertEquals(SecurityType.NONE, info.securityType)
        assertEquals(64, info.server.width)
        assertEquals(48, info.server.height)
        assertEquals("bureau", info.server.desktopName)
        assertEquals(64, info.framebuffer.width)
        assertNull(c.failure)
        assertEquals(ConnectionState.CONNECTED, c.state)
        assertNotNull(setup.get())
    }

    @Test(timeout = 20_000)
    fun `sends the pixel format, the encodings and a full non incremental request first, in that order`() {
        val setup = java.util.concurrent.atomic.AtomicReference<fr.webinfoconcept.secondscreen.rfb.testutil.ClientSetup>()
        val s = server { it.apply { setup.set(standardHandshake(64, 48)); Thread.sleep(2_000) } }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))
        assertTrue(awaitTrue { setup.get() != null })

        val got = setup.get()
        assertArrayEquals(ClientMessages.setPixelFormat(), got.pixelFormat)
        assertEquals(Encoding.ADVERTISED, got.encodings)
        assertArrayEquals(ClientMessages.framebufferUpdateRequest(false, 0, 0, 64, 48), got.firstRequest)
        assertTrue(rec.await(ConnectionState.CONNECTED))
    }

    @Test(timeout = 30_000)
    fun `the server receives exactly the encodings of the chosen mode (SS-063)`() {
        for (mode in EncodingMode.values()) {
            val setup = java.util.concurrent.atomic.AtomicReference<fr.webinfoconcept.secondscreen.rfb.testutil.ClientSetup>()
            val s = server { it.apply { setup.set(standardHandshake(64, 48)); Thread.sleep(1_000) } }
            val c = controller()

            c.connect(ConnectionParams("127.0.0.1", s.port, encodingMode = mode))
            assertTrue(mode.name, awaitTrue { setup.get() != null })

            assertEquals(mode.name, mode.encodings, setup.get().encodings)
            c.disconnect()
        }
    }

    @Test
    fun `connection parameters ask for the automatic mode unless told otherwise`() {
        assertEquals(EncodingMode.AUTO, ConnectionParams("127.0.0.1", 5900).encodingMode)
    }

    @Test(timeout = 20_000)
    fun `decodes updates into the framebuffer, tells the render target once per update, and asks for the next one`() {
        val requests = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val s = server {
            it.standardHandshake(64, 48)
            it.startCollecting { m -> if (m[0].toInt() == 3) requests += m }
            it.sendDesktopUpdate(2, 3, 5, 4)
            it.sendDesktopUpdate(0, 0, 64, 1) // un deuxième
            Thread.sleep(3_000)
        }
        val c = controller()
        val target = StubTarget()
        c.setRenderTarget(target)
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertTrue(awaitTrue { c.updateCount == 2L })

        val fb = c.session!!.framebuffer
        assertEquals(desktopPixel(2, 3), fb.getPixel(2, 3))
        assertEquals(desktopPixel(6, 6), fb.getPixel(6, 6))
        assertEquals(desktopPixel(63, 0), fb.getPixel(63, 0))
        assertEquals(listOf(listOf(2, 3, 5, 4), listOf(0, 0, 64, 1)), target.rects.toList())
        assertTrue(awaitTrue { target.updates.get() == 2 })
        // après chaque mise à jour : une requête incrémentale de l'écran entier
        val full = ClientMessages.framebufferUpdateRequest(true, 0, 0, 64, 48)
        var seen = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        while (seen < 2 && System.nanoTime() < deadline) {
            val m = requests.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (m.contentEquals(full)) seen++
        }
        assertEquals(2, seen)
    }

    @Test(timeout = 20_000)
    fun `works without any render target and shows the accumulated state to a target attached later`() {
        val s = server { it.standardHandshake(64, 48); it.sendDesktopUpdate(10, 10, 4, 4); Thread.sleep(3_000) }
        val c = controller()

        c.connect(params(s))
        assertTrue(awaitTrue { c.updateCount == 1L })

        assertEquals(desktopPixel(11, 11), c.session!!.framebuffer.getPixel(11, 11))
    }

    // ============================================================ authentification

    @Test(timeout = 20_000)
    fun `connects with the right VNC password and wipes the password array`() {
        val s = server { it.banner(); assertTrue(it.securityVncAuth("secret12")); it.serverInit(64, 48); it.readSetup(); Thread.sleep(3_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val password = "secret12".toCharArray()

        c.connect(params(s), password)

        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertEquals(SecurityType.VNC_AUTH, c.session!!.securityType)
        assertTrue("mot de passe effacé", password.all { it == '\u0000' })
        assertTrue(c.reconnectNeedsPassword)
    }

    @Test(timeout = 20_000)
    fun `a wrong password gives AUTH_FAILED with the server reason and still wipes the password`() {
        val s = server { it.banner(); it.securityVncAuth("goodgood", "Authentication failed") }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val password = "badbadbad".toCharArray()

        c.connect(params(s), password)

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.AUTH_FAILED, c.failure!!.kind)
        assertEquals("Authentication failed", c.failure!!.serverReason)
        assertTrue(c.failure!!.isPasswordProblem)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(c.reconnectNeedsPassword)
        rec.assertLegalSequence()
    }

    @Test(timeout = 20_000)
    fun `a server that needs a password when none was given gives PASSWORD_REQUIRED`() {
        val s = server { it.banner(); it.securityVncAuth("x") }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s), null)

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.PASSWORD_REQUIRED, c.failure!!.kind)
        assertTrue(c.reconnectNeedsPassword)
    }

    @Test(timeout = 20_000)
    fun `an empty password is the same as none`() {
        val s = server { it.banner(); it.securityVncAuth("x") }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s), CharArray(0))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.PASSWORD_REQUIRED, c.failure!!.kind)
    }

    @Test(timeout = 20_000)
    fun `a password typed for a server that needs none is accepted and unused`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(2_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val password = "unused".toCharArray()

        c.connect(params(s), password)

        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertEquals(SecurityType.NONE, c.session!!.securityType)
        assertTrue("effacé même si le serveur n'a pas demandé de mot de passe", password.all { it == '\u0000' })
        assertFalse(c.reconnectNeedsPassword)
    }

    @Test(timeout = 20_000)
    fun `a password outside Latin-1 gives PASSWORD_INVALID and is wiped`() {
        val s = server { it.banner(); Thread.sleep(2_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val password = "mot😀".toCharArray()

        c.connect(params(s), password)

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.PASSWORD_INVALID, c.failure!!.kind)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test(timeout = 20_000)
    fun `the password is wiped when the connection fails before the authentication is even reached`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val notVnc = server { it.send("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); Thread.sleep(500) }
        val silent = server { Thread.sleep(3_000) }
        val quick = ConnectionConfig(connectTimeoutMs = 1_000, handshakeTimeoutMs = 300, readTimeoutMs = 100, livenessTimeoutMs = 700, keepAliveIntervalMs = 20, heartbeatIntervalMs = 100)

        for ((label, target, config) in listOf(
            Triple("port fermé", ConnectionParams("127.0.0.1", closedPort), fast),
            Triple("pas du VNC", params(notVnc), fast),
            Triple("serveur muet", params(silent), quick)
        )) {
            val c = ConnectionController(config).also { controllers += it }
            val rec = Recorder().also { c.addListener(it) }
            val password = "topsecret".toCharArray()

            c.connect(target, password)

            assertTrue(label, rec.await(ConnectionState.ERROR))
            assertTrue("$label : mot de passe effacé", password.all { it == '\u0000' })
        }
    }

    @Test(timeout = 20_000)
    fun `the password is wiped when the attempt is abandoned by a disconnect`() {
        val s = server { Thread.sleep(3_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val password = "topsecret".toCharArray()

        c.connect(params(s), password)
        assertTrue(rec.await(ConnectionState.NEGOTIATING))
        c.disconnect()
        assertTrue(c.awaitIdle(3_000))

        assertTrue(password.all { it == '\u0000' })
    }

    // ============================================================ erreurs (SS-053)

    @Test(timeout = 20_000)
    fun `nothing listening on the port gives CONNECTION_REFUSED while connecting`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(ConnectionParams("127.0.0.1", closedPort))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.CONNECTION_REFUSED, c.failure!!.kind)
        assertEquals(Phase.CONNECTING, c.failure!!.phase)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.ERROR), rec.states.toList())
    }

    @Test(timeout = 20_000)
    fun `a service that is not VNC gives NOT_A_VNC_SERVER`() {
        val s = server { it.send("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray()); Thread.sleep(500) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.NOT_A_VNC_SERVER, c.failure!!.kind)
        assertEquals(Phase.NEGOTIATING, c.failure!!.phase)
    }

    @Test(timeout = 20_000)
    fun `an old RFB version gives UNSUPPORTED_VERSION`() {
        val s = server { it.send("RFB 003.002\n".toByteArray()); Thread.sleep(500) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.UNSUPPORTED_VERSION, c.failure!!.kind)
    }

    @Test(timeout = 20_000)
    fun `a server that refuses the connection gives SERVER_REJECTED with its sanitized reason`() {
        val s = server { it.banner(); it.rejectConnection("Too many\u0007 connections") }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.SERVER_REJECTED, c.failure!!.kind)
        assertEquals("Too many? connections", c.failure!!.serverReason)
    }

    @Test(timeout = 20_000)
    fun `a server that stays silent during the handshake gives HANDSHAKE_TIMEOUT`() {
        val quick = ConnectionConfig(connectTimeoutMs = 1_000, handshakeTimeoutMs = 300, readTimeoutMs = 100, livenessTimeoutMs = 700, keepAliveIntervalMs = 20, heartbeatIntervalMs = 100)
        val s = server { Thread.sleep(3_000) } // n'envoie même pas la bannière
        val c = ConnectionController(quick).also { controllers += it }
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.HANDSHAKE_TIMEOUT, c.failure!!.kind)
    }

    @Test(timeout = 20_000)
    fun `an absurd screen size announced by the server gives UNSUPPORTED_SERVER_SIZE and allocates nothing`() {
        val s = server { it.banner(); it.securityNone(); it.serverInit(65535, 65535); Thread.sleep(500) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.UNSUPPORTED_SERVER_SIZE, c.failure!!.kind)
        assertNull(c.session)
    }

    @Test(timeout = 20_000)
    fun `a garbage message during the session gives PROTOCOL_ERROR and closes the connection`() {
        val s = server { it.standardHandshake(64, 48); it.send(99); it.awaitClientClose() }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.PROTOCOL_ERROR, c.failure!!.kind)
        assertEquals(Phase.RUNNING, c.failure!!.phase)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.NEGOTIATING, ConnectionState.CONNECTED, ConnectionState.ERROR), rec.states.toList())
    }

    @Test(timeout = 20_000)
    fun `the server closing the connection during the session gives CONNECTION_LOST`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(300); it.closeNow() }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.CONNECTION_LOST, c.failure!!.kind)
        assertNull("plus de session après l'erreur", c.session)
    }

    @Test(timeout = 20_000)
    fun `a server that stops answering gives NETWORK_LOST after the liveness delay, not before`() {
        val s = server { it.standardHandshake(64, 48); it.startCollecting(); Thread.sleep(10_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        val start = System.nanoTime()
        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertTrue(rec.await(ConnectionState.ERROR))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(FailureKind.NETWORK_LOST, c.failure!!.kind)
        assertEquals(Phase.RUNNING, c.failure!!.phase)
        assertTrue("déclaré trop tôt : $elapsedMs ms", elapsedMs >= 600)
    }

    @Test(timeout = 30_000)
    fun `a server that answers the liveness request stays connected well beyond the liveness delay`() {
        // Répond à chaque requête NON incrémentale d'un pixel par une mise à jour d'un pixel : c'est le signe de vie.
        val s = server { session ->
            session.standardHandshake(64, 48)
            session.startCollecting { m ->
                if (m[0].toInt() == 3 && m[1].toInt() == 0) session.sendDesktopUpdate(0, 0, 1, 1)
            }
            Thread.sleep(6_000)
        }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.CONNECTED))
        Thread.sleep(2_500) // plus de trois fois le délai de silence (700 ms)

        assertEquals(ConnectionState.CONNECTED, c.state)
        assertTrue("des réponses de signe de vie ont été reçues : ${c.updateCount}", c.updateCount >= 5)
    }

    // ============================================================ entrées

    @Test(timeout = 20_000)
    fun `input is refused before a session, delivered during it, and refused again after`() {
        val received = java.util.concurrent.atomic.AtomicReference<ServerSession>()
        val s = server { it.standardHandshake(64, 48); received.set(it); it.startCollecting(); Thread.sleep(5_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val click = ClientMessages.leftClick(10, 20)

        assertFalse("avant la connexion", c.input.send(click))
        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertTrue(c.input.send(click))
        assertTrue(c.input.sendMove(ClientMessages.pointerEvent(0, 5, 6)))

        assertTrue(awaitTrue { received.get() != null })
        val got = received.get().awaitMessage { it.size == 6 && it[0].toInt() == 5 }
        assertNotNull("un PointerEvent est arrivé", got)
        c.disconnect()
        assertFalse("après la déconnexion", c.input.send(click))
        assertFalse(c.input.sendMove(click))
    }

    // ============================================================ arrêt et reconnexion (SS-054)

    @Test(timeout = 20_000)
    fun `disconnect closes the connection, stops every thread and ends in DISCONNECTED`() {
        val closed = CountDownLatch(1)
        val s = server { it.standardHandshake(64, 48); if (it.awaitClientClose()) closed.countDown() }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.CONNECTED))

        c.disconnect()

        assertEquals(ConnectionState.DISCONNECTED, c.state)
        assertNull(c.session)
        assertNull(c.failure)
        assertTrue("le serveur voit la fermeture", closed.await(5, TimeUnit.SECONDS))
        assertTrue(c.awaitIdle(3_000))
        assertTrue("threads restants : ${sessionThreads() - baseline}", awaitTrue { leftBehind() == 0 })
        assertEquals("aucune erreur publiée après un arrêt voulu", ConnectionState.DISCONNECTED, rec.states.last())
        assertFalse(rec.states.contains(ConnectionState.ERROR))
        rec.assertLegalSequence()
    }

    @Test(timeout = 20_000)
    fun `disconnect is idempotent and harmless when nothing is connected`() {
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        c.disconnect()
        c.disconnect()

        assertEquals(ConnectionState.DISCONNECTED, c.state)
        assertTrue("aucune notification pour un état inchangé", rec.states.isEmpty())
    }

    @Test(timeout = 20_000)
    fun `disconnect during the negotiation abandons the attempt without publishing an error`() {
        val entered = CountDownLatch(1)
        val s = server { entered.countDown(); Thread.sleep(3_000) } // n'envoie rien
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(rec.await(ConnectionState.NEGOTIATING))

        c.disconnect()

        assertTrue(c.awaitIdle(3_000))
        assertEquals(ConnectionState.DISCONNECTED, c.state)
        assertFalse(rec.states.contains(ConnectionState.ERROR))
        assertFalse(rec.states.contains(ConnectionState.CONNECTED))
    }

    @Test(timeout = 20_000)
    fun `a second connect while one is active is refused and wipes its password`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(3_000) }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s))
        val extra = "leaked".toCharArray()

        assertFalse(c.connect(params(s), extra))
        assertFalse(c.reconnect(extra))

        assertTrue(extra.all { it == '\u0000' })
        assertTrue(rec.await(ConnectionState.CONNECTED))
        assertEquals("un seul serveur contacté", 1, s.connections.get())
    }

    @Test
    fun `reconnect without any previous destination is refused`() {
        val c = controller()
        val pw = "x".toCharArray()

        assertFalse(c.reconnect(pw))

        assertTrue(pw.all { it == '\u0000' })
        assertNull(c.lastConnection)
    }

    @Test(timeout = 30_000)
    fun `after a lost connection reconnect restores the session without restarting anything`() {
        val n = AtomicInteger()
        val s = server {
            if (n.incrementAndGet() == 1) { it.standardHandshake(64, 48); Thread.sleep(200); it.closeNow() }
            else { it.standardHandshake(80, 60); it.sendDesktopUpdate(1, 1, 3, 3); Thread.sleep(4_000) }
        }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.ERROR))
        assertEquals(FailureKind.CONNECTION_LOST, c.failure!!.kind)
        val before = rec.states.size

        assertTrue(c.reconnect())

        assertTrue(rec.await(ConnectionState.CONNECTED, from = before))
        assertEquals(listOf(ConnectionState.RECONNECTING, ConnectionState.NEGOTIATING, ConnectionState.CONNECTED), rec.states.drop(before))
        assertEquals("nouvel écran distant", 80, c.session!!.framebuffer.width)
        assertTrue(awaitTrue { c.updateCount == 1L })
        assertEquals("compteur remis à zéro pour la nouvelle session", 1L, c.updateCount)
        assertNull(c.failure)
        assertEquals(2, s.connections.get())
        rec.assertLegalSequence()
    }

    @Test(timeout = 30_000)
    fun `reconnect to a password server asks for the password again and never keeps the old one`() {
        val n = AtomicInteger()
        val s = server {
            it.banner(); assertTrue(it.securityVncAuth("secret12")); it.serverInit(64, 48); it.readSetup()
            if (n.incrementAndGet() == 1) { Thread.sleep(200); it.closeNow() } else Thread.sleep(3_000)
        }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s), "secret12".toCharArray())
        assertTrue(rec.await(ConnectionState.ERROR))
        assertTrue("l'interface doit redemander le mot de passe", c.reconnectNeedsPassword)
        val before = rec.states.size

        // sans mot de passe : refusé par le serveur (il en exige un), preuve que rien n'a été gardé
        assertTrue(c.reconnect(null))
        assertTrue(rec.await(ConnectionState.ERROR, from = before))
        assertEquals(FailureKind.PASSWORD_REQUIRED, c.failure!!.kind)
        val second = rec.states.size

        assertTrue(c.reconnect("secret12".toCharArray()))
        assertTrue(rec.await(ConnectionState.CONNECTED, from = second))
        assertEquals(SecurityType.VNC_AUTH, c.session!!.securityType)
    }

    @Test(timeout = 30_000)
    fun `a connect right after a disconnect is not disturbed by the old attempt still unwinding`() {
        val n = AtomicInteger()
        val s = server {
            if (n.incrementAndGet() == 1) { Thread.sleep(3_000) } // première tentative : muette
            else { it.standardHandshake(64, 48); it.sendDesktopUpdate(0, 0, 2, 2); Thread.sleep(3_000) }
        }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        c.connect(params(s))
        assertTrue(rec.await(ConnectionState.NEGOTIATING))
        c.disconnect()
        val before = rec.states.size

        assertTrue(c.connect(params(s)))

        assertTrue(rec.await(ConnectionState.CONNECTED, from = before))
        assertTrue(awaitTrue { c.updateCount == 1L })
        Thread.sleep(300) // laisse à l'ancien thread le temps de mal faire
        assertEquals(ConnectionState.CONNECTED, c.state)
        assertFalse("l'ancienne tentative n'a rien publié", rec.states.drop(before).contains(ConnectionState.ERROR))
        rec.assertLegalSequence()
    }

    @Test(timeout = 30_000)
    fun `several connect and disconnect cycles leave no thread behind`() {
        val s = server { it.standardHandshake(64, 48); it.awaitClientClose() }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }

        repeat(5) { round ->
            val before = rec.states.size
            assertTrue("tour $round", c.connect(params(s)))
            assertTrue(rec.await(ConnectionState.CONNECTED, from = before))
            c.disconnect()
            assertTrue(c.awaitIdle(3_000))
        }

        assertTrue("threads restants : ${sessionThreads() - baseline}", awaitTrue { leftBehind() == 0 })
        rec.assertLegalSequence()
    }

    // ============================================================ écouteurs

    @Test(timeout = 20_000)
    fun `a listener that throws does not break the session nor the other listeners`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(2_000) }
        val c = controller()
        c.addListener(object : ConnectionController.Listener {
            override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) = throw IllegalStateException("boom")
        })
        val rec = Recorder().also { c.addListener(it) }

        c.connect(params(s))

        assertTrue(rec.await(ConnectionState.CONNECTED))
    }

    @Test(timeout = 20_000)
    fun `a removed listener hears nothing more`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(2_000) }
        val c = controller()
        val rec = Recorder()
        c.addListener(rec)
        c.removeListener(rec)

        c.connect(params(s))
        assertTrue(awaitTrue { c.state == ConnectionState.CONNECTED })

        assertTrue(rec.states.isEmpty())
    }

    @Test(timeout = 20_000)
    fun `errors and states never expose a secret`() {
        val s = server { it.banner(); it.securityVncAuth("goodgood", "nope") }
        val c = controller()
        val rec = Recorder().also { c.addListener(it) }
        val secret = "hunter22"

        c.connect(params(s), secret.toCharArray())
        assertTrue(rec.await(ConnectionState.ERROR))

        val text = listOf(c.failure.toString(), c.state.toString(), c.lastConnection.toString(), c.failure!!.serverReason).joinToString(" ")
        assertFalse(text.contains(secret))
    }
}
