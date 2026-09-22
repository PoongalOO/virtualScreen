package fr.webinfoconcept.secondscreen.hygiene

import fr.webinfoconcept.secondscreen.input.KeyboardInput
import fr.webinfoconcept.secondscreen.input.MessageSink
import fr.webinfoconcept.secondscreen.rfb.protocol.VncAuthentication
import fr.webinfoconcept.secondscreen.rfb.testutil.FakeRfbServer
import fr.webinfoconcept.secondscreen.rfb.testutil.ServerSession
import fr.webinfoconcept.secondscreen.session.ConnectionConfig
import fr.webinfoconcept.secondscreen.session.ConnectionController
import fr.webinfoconcept.secondscreen.session.ConnectionFailure
import fr.webinfoconcept.secondscreen.session.ConnectionParams
import fr.webinfoconcept.secondscreen.session.ConnectionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SS-071, à l'exécution : un mot de passe et un texte tapé **distinctifs** (des « canaris ») traversent les vrais chemins de
 * l'application : connexion réussie, mot de passe faux, mot de passe invalide, reconnexion automatique (le mot de passe est alors
 * gardé en mémoire), clavier distant. On cherche ensuite le canari, en entier **et par morceaux**, dans tout ce qu'on peut
 * observer : `toString()` des objets d'état, messages et traces des exceptions (causes comprises), sortie standard et d'erreur.
 * Le journal Android, lui, est couvert par LogHygieneTest (il n'existe qu'un appel, qui n'écrit que des nombres).
 */
class SecretCanaryTest {

    private companion object {
        // 8 caractères : tout le mot de passe compte pour l'authentification VNC (DES, 8 octets).
        const val PASSWORD = "Qz7#kVx!"
        // Ni l'un ni l'autre ne ressemble à un mot ni à un identifiant : une tranche de 4 caractères ne peut pas se trouver par hasard
        // dans une pile d'appels (le premier essai avec « TypedCanary » trouvait « Cana » dans le nom de cette classe).
        const val TYPED = "Wm3&pLz-Rj9"
    }

    private val fast = ConnectionConfig(
        connectTimeoutMs = 2_000, handshakeTimeoutMs = 2_000, readTimeoutMs = 100,
        livenessTimeoutMs = 700, keepAliveIntervalMs = 20, heartbeatIntervalMs = 100
    )

    /** Tout ce que les scénarios ont pu observer. */
    private val seen = CopyOnWriteArrayList<String>()
    private val servers = mutableListOf<FakeRfbServer>()
    private val controllers = mutableListOf<ConnectionController>()
    private lateinit var stdout: ByteArrayOutputStream
    private lateinit var stderr: ByteArrayOutputStream
    private lateinit var savedOut: PrintStream
    private lateinit var savedErr: PrintStream

    @Before
    fun captureStandardOutputs() {
        savedOut = System.out
        savedErr = System.err
        stdout = ByteArrayOutputStream()
        stderr = ByteArrayOutputStream()
        System.setOut(PrintStream(stdout, true))
        System.setErr(PrintStream(stderr, true))
    }

    @After
    fun restore() {
        System.setOut(savedOut)
        System.setErr(savedErr)
        controllers.forEach { it.disconnect() }
        servers.forEach { it.close() }
    }

    private fun observe(x: Any?) {
        seen += x.toString()
    }

    private fun observe(t: Throwable) {
        var cause: Throwable? = t
        while (cause != null) {
            seen += cause.javaClass.name
            seen += cause.message.orEmpty()
            seen += cause.stackTraceToString()
            cause = cause.cause
        }
    }

    /** Le canari, entier ou par tranches de 4 caractères, dans les chaînes observées ou les sorties. */
    private fun leaks(canary: String): List<String> {
        val pieces = listOf(canary) + (0..canary.length - 4).map { canary.substring(it, it + 4) }.filter { it.any(Char::isLetterOrDigit) }
        val haystacks = seen + stdout.toString(Charsets.UTF_8) + stderr.toString(Charsets.UTF_8)
        return pieces.filter { piece -> haystacks.any { it.contains(piece) } }
    }

    private fun assertNoLeak() {
        assertEquals("le mot de passe apparaît", emptyList<String>(), leaks(PASSWORD))
        assertEquals("le texte tapé apparaît", emptyList<String>(), leaks(TYPED))
    }

    private fun server(script: (ServerSession) -> Unit) = FakeRfbServer(script).also { servers += it }

    private class Listener(val onState: (ConnectionState, ConnectionFailure?) -> Unit) : ConnectionController.Listener {
        override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) = onState(state, failure)
    }

    /** Lance une connexion, observe tout ce qui en sort, attend [target] puis observe encore. */
    private fun run(s: FakeRfbServer, password: String, target: ConnectionState, autoReconnect: Boolean = false): ConnectionController {
        val c = ConnectionController(fast).also { controllers += it }
        val reached = CountDownLatch(1)
        c.addListener(Listener { state, failure ->
            observe(state); observe(failure)
            if (state == target) reached.countDown()
        })
        val chars = password.toCharArray()
        c.connect(ConnectionParams("127.0.0.1", s.port), chars, autoReconnect = autoReconnect)
        assertTrue("$target non atteint", reached.await(10, TimeUnit.SECONDS))
        observe(c.state); observe(c.failure); observe(c.lastConnection); observe(c.reconnectStatus); observe(c.session); observe(c)
        return c
    }

    // ================================================================== le banc sait détecter une fuite

    @Test
    fun `the harness does detect a leak - a canary printed or put in a message is found`() {
        System.out.println("mot de passe = $PASSWORD")
        observe(IllegalStateException("échec avec $TYPED"))

        assertEquals(listOf(PASSWORD) + (0..4).map { PASSWORD.substring(it, it + 4) }.filter { it.any(Char::isLetterOrDigit) }, leaks(PASSWORD))
        assertTrue(leaks(TYPED).isNotEmpty())
    }

    // ================================================================== mot de passe

    @Test(timeout = 30_000)
    fun `a successful connection with the password kept in memory for reconnection leaks nothing`() {
        val s = server { it.banner(); assertTrue(it.securityVncAuth(PASSWORD)); it.serverInit(64, 48); it.readSetup(); Thread.sleep(2_000) }
        run(s, PASSWORD, ConnectionState.CONNECTED, autoReconnect = true)
        assertNoLeak()
    }

    @Test(timeout = 30_000)
    fun `a wrong password leaks nothing, neither the wrong one nor the right one`() {
        val s = server { it.banner(); it.securityVncAuth("Other-Pw!", "Authentication failed") }
        run(s, PASSWORD, ConnectionState.ERROR)
        assertNoLeak()
    }

    @Test(timeout = 30_000)
    fun `the automatic reconnection that retains the password leaks nothing while it waits`() {
        val s = server { it.banner(); assertTrue(it.securityVncAuth(PASSWORD)); it.serverInit(64, 48); it.readSetup(); it.closeNow() }
        val c = run(s, PASSWORD, ConnectionState.RECONNECTING, autoReconnect = true)
        Thread.sleep(300)
        observe(c.reconnectStatus); observe(c.state); observe(c.failure)
        assertNoLeak()
    }

    @Test
    fun `an invalid password fails with an error that does not contain it`() {
        for (bad in listOf("$PASSWORD😀", "éĀ$PASSWORD", "")) {
            try {
                VncAuthentication.deriveKey(bad.toCharArray())
            } catch (e: Throwable) {
                observe(e)
            }
            try {
                VncAuthentication(bad.toCharArray()).use { observe(it) }
            } catch (e: Throwable) {
                observe(e)
            }
        }
        assertNoLeak()
    }

    // ================================================================== texte tapé

    @Test
    fun `typed text, committed, composed, deleted or unsupported, leaks nothing`() {
        val sent = mutableListOf<ByteArray>()
        val kb = KeyboardInput(object : MessageSink {
            override fun send(message: ByteArray): Boolean { sent += message; return true }
            override fun sendMove(message: ByteArray): Boolean { sent += message; return true }
        })
        val texts = listOf(TYPED, "$TYPED😀\u0000\u001B", "é$TYPED", TYPED.repeat(40))
        for (text in texts) {
            try { kb.typeText(text) } catch (e: Throwable) { observe(e) }
            try { kb.commitText(text) } catch (e: Throwable) { observe(e) }
            try { kb.setComposingText(text); kb.setComposingText(text.dropLast(3)); kb.finishComposing() } catch (e: Throwable) { observe(e) }
            try { kb.deleteSurrounding(3, 1); kb.deleteSurrounding(-1, 500) } catch (e: Throwable) { observe(e) }
            try { kb.pressKey(-5); kb.pressKey(0); kb.pressKey(Int.MIN_VALUE) } catch (e: Throwable) { observe(e) }
        }
        observe(kb)
        assertTrue("des touches ont bien été envoyées (le canal voulu)", sent.isNotEmpty())
        assertNoLeak()
    }
}
