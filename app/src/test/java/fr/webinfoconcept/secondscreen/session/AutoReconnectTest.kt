package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityType
import fr.webinfoconcept.secondscreen.rfb.testutil.FakeRfbServer
import fr.webinfoconcept.secondscreen.rfb.testutil.ServerSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Reconnexion automatique (SS-055) : bornée, désactivable, sans jamais garder le mot de passe plus longtemps que nécessaire. */
class AutoReconnectTest {

    private fun config(delays: List<Long>, stableMs: Long = 30_000) = ConnectionConfig(
        connectTimeoutMs = 2_000, handshakeTimeoutMs = 2_000, readTimeoutMs = 100,
        livenessTimeoutMs = 700, keepAliveIntervalMs = 20, heartbeatIntervalMs = 100,
        reconnectDelaysMs = delays, reconnectStableMs = stableMs
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
    private fun controller(c: ConnectionConfig) = ConnectionController(c).also { controllers += it }
    private fun params(s: FakeRfbServer) = ConnectionParams("127.0.0.1", s.port)

    private class Snapshot(val state: ConnectionState, val failure: ConnectionFailure?, val status: ReconnectStatus?)

    private class Recorder(private val controller: ConnectionController) : ConnectionController.Listener {
        val snapshots = CopyOnWriteArrayList<Snapshot>()
        private val lock = Object()
        override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) {
            snapshots += Snapshot(state, failure, controller.reconnectStatus)
            synchronized(lock) { lock.notifyAll() }
        }
        val states get() = snapshots.map { it.state }
        fun awaitCount(state: ConnectionState, count: Int, timeoutMs: Long = 10_000): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            synchronized(lock) {
                while (snapshots.count { it.state == state } < count) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return false
                    lock.wait(left)
                }
            }
            return true
        }
        fun assertLegal() {
            val s = states
            for (i in 1 until s.size) assertTrue("transition illégale ${s[i - 1]} -> ${s[i]} dans $s", s[i - 1] == s[i] || s[i - 1].canTransitionTo(s[i]))
        }
    }

    private fun recorder(c: ConnectionController) = Recorder(c).also { c.addListener(it) }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    // ============================================================ reconnexion réussie

    @Test(timeout = 30_000)
    fun `a lost session is reconnected by itself, with the cause shown while it waits`() {
        val n = AtomicInteger()
        val s = server {
            if (n.incrementAndGet() == 1) { it.standardHandshake(64, 48); Thread.sleep(200); it.closeNow() }
            else { it.standardHandshake(80, 60); it.sendDesktopUpdate(1, 1, 3, 3); Thread.sleep(4_000) }
        }
        val c = controller(config(listOf(50, 100)))
        val rec = recorder(c)

        c.connect(params(s), null, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.CONNECTED, 2))

        val states = rec.states
        assertEquals(ConnectionState.CONNECTING, states[0])
        assertTrue("passage par RECONNECTING : $states", ConnectionState.RECONNECTING in states)
        val waiting = rec.snapshots.first { it.state == ConnectionState.RECONNECTING }
        assertEquals("cause de la coupure affichée pendant l'attente", FailureKind.CONNECTION_LOST, waiting.failure!!.kind)
        assertEquals(1, waiting.status!!.attempt)
        assertEquals(2, waiting.status!!.maxAttempts)
        assertEquals(50L, waiting.status!!.delayMs)
        assertTrue(waiting.status!!.waiting)
        assertEquals("nouvel écran distant", 80, c.session!!.framebuffer.width)
        assertNull(c.failure)
        assertNull("plus de statut une fois reconnecté", c.reconnectStatus)
        assertTrue(awaitTrue { c.updateCount == 1L })
        assertEquals(2, s.connections.get())
        rec.assertLegal()
    }

    @Test(timeout = 30_000)
    fun `a password server is reconnected without asking again, with the retained password, then it is wiped`() {
        val n = AtomicInteger()
        val s = server {
            it.banner(); assertTrue(it.securityVncAuth("secret12")); it.serverInit(64, 48); it.readSetup()
            if (n.incrementAndGet() == 1) { Thread.sleep(200); it.closeNow() } else Thread.sleep(4_000)
        }
        val c = controller(config(listOf(50)))
        val rec = recorder(c)
        val typed = "secret12".toCharArray()

        c.connect(params(s), typed, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.CONNECTED, 2))

        assertEquals(SecurityType.VNC_AUTH, c.session!!.securityType)
        assertTrue("le tableau saisi est effacé comme avant", typed.all { it == '\u0000' })
        assertArrayEquals("une copie est gardée en mémoire pour la reconnexion", "secret12".toCharArray(), c.retainedPasswordForTest())
        c.disconnect()
        assertNull("effacé dès la déconnexion", c.retainedPasswordForTest())
    }

    @Test(timeout = 30_000)
    fun `without auto reconnect a lost session ends in ERROR as before, and no password is kept`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(200); it.closeNow() }
        val c = controller(config(listOf(50)))
        val rec = recorder(c)

        c.connect(params(s), "secret".toCharArray(), autoReconnect = false)

        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))
        assertEquals(FailureKind.CONNECTION_LOST, c.failure!!.kind)
        assertFalse(ConnectionState.RECONNECTING in rec.states)
        assertNull(c.retainedPasswordForTest())
        assertEquals(1, s.connections.get())
    }

    @Test(timeout = 30_000)
    fun `an empty delay list disables it even when asked`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(200); it.closeNow() }
        val c = controller(config(emptyList()))
        val rec = recorder(c)

        c.connect(params(s), null, autoReconnect = true)

        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))
        assertFalse(ConnectionState.RECONNECTING in rec.states)
    }

    // ============================================================ ce qui n'est pas retenté

    @Test(timeout = 20_000)
    fun `a first connection that fails is not retried even with auto reconnect - the user is in front of the screen`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val c = controller(config(listOf(50, 50)))
        val rec = recorder(c)

        c.connect(ConnectionParams("127.0.0.1", closedPort), "pw".toCharArray(), autoReconnect = true)

        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))
        assertEquals(FailureKind.CONNECTION_REFUSED, c.failure!!.kind)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.ERROR), rec.states)
        assertNull("le mot de passe n'est pas resté en mémoire", c.retainedPasswordForTest())
    }

    @Test(timeout = 30_000)
    fun `a non transient failure during the outage stops at once - a changed password is not retried`() {
        val n = AtomicInteger()
        val s = server {
            it.banner()
            if (n.incrementAndGet() == 1) { assertTrue(it.securityVncAuth("secret12")); it.serverInit(64, 48); it.readSetup(); Thread.sleep(200); it.closeNow() }
            else it.securityVncAuth("autre-mdp", "Authentication failed") // le mot de passe du serveur a changé
        }
        val c = controller(config(listOf(50, 50, 50, 50)))
        val rec = recorder(c)

        c.connect(params(s), "secret12".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))

        assertEquals(FailureKind.AUTH_FAILED, c.failure!!.kind)
        assertEquals("une seule tentative de reconnexion, pas quatre", 2, s.connections.get())
        assertEquals(0, c.gaveUpAfter)
        assertNull(c.retainedPasswordForTest())
        assertTrue(c.reconnectNeedsPassword)
    }

    @Test
    fun `only passing causes are transient`() {
        val transient = FailureKind.values().filter { it.isTransient }.toSet()

        assertEquals(
            setOf(
                FailureKind.UNKNOWN_HOST, FailureKind.CONNECT_TIMEOUT, FailureKind.CONNECTION_REFUSED,
                FailureKind.NETWORK_UNREACHABLE, FailureKind.SERVER_REJECTED, FailureKind.HANDSHAKE_TIMEOUT,
                FailureKind.CONNECTION_LOST, FailureKind.NETWORK_LOST
            ),
            transient
        )
        for (never in listOf(FailureKind.AUTH_FAILED, FailureKind.PASSWORD_REQUIRED, FailureKind.PASSWORD_INVALID,
            FailureKind.NOT_A_VNC_SERVER, FailureKind.UNSUPPORTED_VERSION, FailureKind.NO_COMPATIBLE_SECURITY,
            FailureKind.UNSUPPORTED_SERVER_SIZE, FailureKind.PROTOCOL_ERROR, FailureKind.LOCAL_ERROR)) {
            assertFalse("$never", never.isTransient)
        }
    }

    // ============================================================ bornée

    @Test(timeout = 30_000)
    fun `it gives up after the last delay, with exactly that many attempts, and the delays grow as configured`() {
        lateinit var srv: FakeRfbServer
        srv = server { it.standardHandshake(64, 48); Thread.sleep(150); srv.close() } // coupe puis refuse toute nouvelle connexion
        val delays = listOf(40L, 80L, 160L)
        val c = controller(config(delays))
        val rec = recorder(c)

        c.connect(params(srv), "pw".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))

        val waits = rec.snapshots.filter { it.state == ConnectionState.RECONNECTING && it.status!!.waiting }
        assertEquals("un statut d'attente par tentative", 3, waits.size)
        assertEquals(delays, waits.map { it.status!!.delayMs })
        assertEquals(listOf(1, 2, 3), waits.map { it.status!!.attempt })
        assertTrue(waits.all { it.status!!.maxAttempts == 3 })
        assertEquals(3, c.gaveUpAfter)
        assertEquals(FailureKind.CONNECTION_REFUSED, c.failure!!.kind)
        assertNull(c.reconnectStatus)
        assertNull("mot de passe effacé à l'abandon", c.retainedPasswordForTest())
        assertEquals(ConnectionState.ERROR, rec.states.last())
        rec.assertLegal()
    }

    @Test(timeout = 30_000)
    fun `a manual reconnect still works after an automatic one`() {
        val n = AtomicInteger()
        val srv = server {
            if (n.incrementAndGet() == 1) { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
            else { it.standardHandshake(64, 48); Thread.sleep(3_000) }
        }
        val c = controller(config(listOf(30)))
        val rec = recorder(c)
        c.connect(params(srv), null, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.CONNECTED, 2)) // la reconnexion automatique réussit

        c.disconnect()
        assertTrue(c.reconnect(null))
        assertTrue(rec.awaitCount(ConnectionState.CONNECTED, 3))
    }

    @Test(timeout = 40_000)
    fun `a session that held long enough resets the counter, so it can reconnect any number of times`() {
        val n = AtomicInteger()
        val s = server { it.standardHandshake(64, 48); n.incrementAndGet(); Thread.sleep(250); it.closeNow() }
        val c = controller(config(listOf(20, 20), stableMs = 150)) // tient 250 ms >= 150 ms : stable
        val rec = recorder(c)

        c.connect(params(s), null, autoReconnect = true)

        assertTrue("plus de 2 reconnexions successives sans abandonner", rec.awaitCount(ConnectionState.CONNECTED, 5, 30_000))
        assertFalse("jamais abandonné", rec.states.contains(ConnectionState.ERROR))
        assertTrue(n.get() >= 5)
    }

    @Test(timeout = 40_000)
    fun `a flapping link that drops at once does not reset the counter and ends up abandoned`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(10); it.closeNow() }
        val c = controller(config(listOf(20, 20, 20), stableMs = 10_000)) // ne tient jamais 10 s
        val rec = recorder(c)

        c.connect(params(s), null, autoReconnect = true)

        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1, 30_000))
        assertEquals(3, c.gaveUpAfter)
        assertTrue("bornée : 1 + 3 connexions au plus, pas une boucle sans fin : ${s.connections.get()}", s.connections.get() in 2..4)
    }

    // ============================================================ contrôle par l'utilisateur

    @Test(timeout = 30_000)
    fun `disconnect during the wait stops everything at once and wipes the password`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
        val c = controller(config(listOf(60_000)))  // attente très longue
        val rec = recorder(c)
        c.connect(params(s), "secret".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.RECONNECTING, 1))
        assertNotNull(c.retainedPasswordForTest())

        val start = System.nanoTime()
        c.disconnect()

        assertEquals(ConnectionState.DISCONNECTED, c.state)
        assertNull("effacé de façon synchrone par disconnect()", c.retainedPasswordForTest())
        assertNull(c.reconnectStatus)
        assertTrue("le fil sort tout de suite, sans attendre les 60 s", c.awaitIdle(3_000))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
        assertEquals(1, s.connections.get())
        assertFalse(rec.states.contains(ConnectionState.ERROR))
    }

    @Test(timeout = 30_000)
    fun `retry now skips the wait and reconnects at once`() {
        val n = AtomicInteger()
        val s = server {
            if (n.incrementAndGet() == 1) { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
            else { it.standardHandshake(64, 48); Thread.sleep(3_000) }
        }
        val c = controller(config(listOf(60_000)))
        val rec = recorder(c)
        c.connect(params(s), null, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.RECONNECTING, 1))

        c.retryNow()

        assertTrue("reconnecté sans attendre les 60 s", rec.awaitCount(ConnectionState.CONNECTED, 2, 8_000))
        assertEquals(2, s.connections.get())
    }

    @Test(timeout = 30_000)
    fun `retry now outside a wait does nothing`() {
        val c = controller(config(listOf(50)))

        c.retryNow()
        c.stopAutoReconnect()

        assertEquals(ConnectionState.DISCONNECTED, c.state)
    }

    @Test(timeout = 30_000)
    fun `stopping the automatic reconnection leaves an ERROR with the cause, so the screen offers a manual reconnect`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
        val c = controller(config(listOf(60_000)))
        val rec = recorder(c)
        c.connect(params(s), "secret".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.RECONNECTING, 1))

        c.stopAutoReconnect()

        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))
        assertEquals(FailureKind.CONNECTION_LOST, c.failure!!.kind)
        assertEquals(0, c.gaveUpAfter)
        assertNull(c.retainedPasswordForTest())
        assertNull(c.reconnectStatus)
        assertTrue(c.awaitIdle(3_000))
        assertEquals(1, s.connections.get())
        rec.assertLegal()
    }

    @Test(timeout = 30_000)
    fun `a manual connect is refused while the automatic reconnection is running, and wipes its password`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
        val c = controller(config(listOf(60_000)))
        val rec = recorder(c)
        c.connect(params(s), null, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.RECONNECTING, 1))
        val extra = "leaked".toCharArray()

        assertFalse(c.connect(params(s), extra))
        assertFalse(c.reconnect(extra))

        assertTrue(extra.all { it == '\u0000' })
    }

    // ============================================================ mot de passe et sécurité

    @Test(timeout = 30_000)
    fun `the retained password lives only while it is needed - never after an error, never without the option`() {
        val s = server { it.banner(); it.securityVncAuth("goodgood", "nope") }
        val c = controller(config(listOf(50)))
        val rec = recorder(c)

        c.connect(params(s), "badbadbad".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.ERROR, 1))

        assertNull(c.retainedPasswordForTest())
        assertTrue(awaitTrue { c.awaitIdle(100) })
    }

    @Test(timeout = 30_000)
    fun `each attempt gets its own copy and the caller array is wiped, the retained copy is not the caller array`() {
        val s = server { it.banner(); assertTrue(it.securityVncAuth("secret12")); it.serverInit(64, 48); it.readSetup(); Thread.sleep(3_000) }
        val c = controller(config(listOf(50)))
        val rec = recorder(c)
        val typed = "secret12".toCharArray()

        c.connect(params(s), typed, autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.CONNECTED, 1))

        val snapshot1 = c.retainedPasswordForTest()!!
        val snapshot2 = c.retainedPasswordForTest()!!
        assertArrayEquals(snapshot1, snapshot2)
        assertTrue(snapshot1 !== snapshot2)          // une copie à chaque lecture : rien ne fuit par référence
        assertTrue(typed.all { it == '\u0000' })
        snapshot1.fill('x')
        assertArrayEquals("modifier une copie ne touche pas la copie gardée", "secret12".toCharArray(), c.retainedPasswordForTest())
    }

    @Test(timeout = 30_000)
    fun `errors and statuses never expose the password`() {
        val s = server { it.standardHandshake(64, 48); Thread.sleep(150); it.closeNow() }
        val c = controller(config(listOf(40)))
        val rec = recorder(c)
        c.connect(params(s), "hunter22".toCharArray(), autoReconnect = true)
        assertTrue(rec.awaitCount(ConnectionState.RECONNECTING, 1))

        val text = (rec.snapshots.map { "${it.state} ${it.failure} ${it.status}" } + c.lastConnection.toString()).joinToString(" ")

        assertFalse(text.contains("hunter22"))
    }

    // ============================================================ paramètres

    @Test
    fun `the default policy is bounded and grows to a cap`() {
        val d = ConnectionConfig.DEFAULT_RECONNECT_DELAYS_MS

        assertEquals(8, d.size)
        assertEquals(d, d.sorted())
        assertEquals(30_000L, d.last())
        assertTrue("environ 2 minutes 30 au total : ${d.sum()}", d.sum() in 90_000..180_000)
    }

    @Test
    fun `invalid reconnect settings are refused`() {
        assertThrows(IllegalArgumentException::class.java) { config(listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { config(listOf(-5)) }
        assertThrows(IllegalArgumentException::class.java) { config(listOf(ConnectionConfig.MAX_RECONNECT_DELAY_MS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { config(List(ConnectionConfig.MAX_RECONNECT_ATTEMPTS + 1) { 10L }) }
        assertThrows(IllegalArgumentException::class.java) { config(listOf(10), stableMs = -1) }
    }

    @Test
    fun `the remaining wait counts down and is zero while attempting`() {
        val waiting = ReconnectStatus(2, 8, 4_000, startedAtNs = 0, waiting = true)

        assertEquals(4_000L, waiting.remainingMs(nowNs = 0))
        assertEquals(1_000L, waiting.remainingMs(nowNs = 3_000_000_000L))
        assertEquals(0L, waiting.remainingMs(nowNs = 9_000_000_000L))
        assertEquals(0L, ReconnectStatus(2, 8, 4_000, 0, waiting = false).remainingMs(nowNs = 0))
    }
}
