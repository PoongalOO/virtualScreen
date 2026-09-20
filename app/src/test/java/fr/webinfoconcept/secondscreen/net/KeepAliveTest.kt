package fr.webinfoconcept.secondscreen.net

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfThread
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Battement Wi-Fi (KeepAlive) : cadence, arrêt, erreurs, sans jamais dépendre d'un chronométrage serré. Les
 * bornes de cadence sont larges (une machine chargée ralentit, elle n'accélère pas).
 */
class KeepAliveTest {

    private val toStop = mutableListOf<KeepAlive>()

    @After
    fun tearDown() {
        toStop.forEach { it.stop() }
    }

    private fun keepAlive(intervalMs: Long = 20, onError: (Throwable) -> Unit = {}, action: () -> Unit): KeepAlive =
        KeepAlive(intervalMs, onError, action).also { toStop += it }

    private fun keepAliveThreads() = Thread.getAllStackTraces().keys.filter { it.name == "secondscreen-keepalive" && it.isAlive }

    // ------------------------------------------------------------ cadence

    @Test
    fun `does nothing until started`() {
        val count = AtomicInteger()
        val k = keepAlive { count.incrementAndGet() }

        Thread.sleep(120)

        assertEquals(0, count.get())
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `runs the action periodically and no faster than the interval`() {
        val count = AtomicInteger()
        val reached = CountDownLatch(5)
        val k = keepAlive(20) { count.incrementAndGet(); reached.countDown() }

        val start = System.nanoTime()
        k.start()
        assertTrue("5 battements attendus", reached.await(5, TimeUnit.SECONDS))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        k.stop()

        assertTrue(k.isRunning.not())
        // 5 battements à 20 ms d'intervalle ne peuvent pas arriver en moins de ~100 ms : pas de boucle folle.
        assertTrue("trop rapide : $elapsedMs ms pour 5 battements", elapsedMs >= 80)
    }

    @Test(timeout = 10_000)
    fun `absolute deadlines - a slow action does not stretch the interval`() {
        val stamps = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val done = CountDownLatch(15)
        // Action de 10 ms, intervalle de 30 ms : avec des attentes relatives on aurait 40 ms entre deux battements.
        val k = keepAlive(30) { stamps += System.nanoTime(); Thread.sleep(10); done.countDown() }

        k.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        k.stop()

        val spacingMs = (stamps[14] - stamps[0]) / 14 / 1_000_000.0
        assertTrue("espacement moyen $spacingMs ms : l'intervalle se serait allongé jusqu'à ~40 ms", spacingMs < 36.0)
        assertTrue("espacement moyen $spacingMs ms : plus court que l'intervalle", spacingMs >= 28.0)
    }

    @Test(timeout = 10_000)
    fun `the default interval is 100 ms`() {
        val count = AtomicInteger()
        val k = KeepAlive { count.incrementAndGet() }.also { toStop += it }

        k.start()
        Thread.sleep(550)
        k.stop()

        assertTrue("${count.get()} battements en 550 ms", count.get() in 1..6) // ~5 attendus, jamais 10x plus
    }

    // ------------------------------------------------------- démarrage / arrêt

    @Test(timeout = 10_000)
    fun `starting twice keeps a single thread`() {
        val k = keepAlive { }

        k.start()
        k.start()
        k.start()

        assertEquals(1, keepAliveThreads().size)
        assertTrue(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `stop halts further calls`() {
        val count = AtomicInteger()
        val reached = CountDownLatch(3)
        val k = keepAlive(20) { count.incrementAndGet(); reached.countDown() }
        k.start()
        assertTrue(reached.await(5, TimeUnit.SECONDS))

        k.stop()
        val atStop = count.get()
        Thread.sleep(150)

        assertEquals("aucun battement après stop()", atStop, count.get())
        assertFalse(k.isRunning)
        assertTrue("le thread est terminé", keepAliveThreads().isEmpty())
    }

    @Test
    fun `stop is idempotent and harmless before start`() {
        val k = keepAlive { }

        k.stop()
        k.stop()
        k.start()
        k.stop()
        k.stop()

        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `it can be restarted after a stop`() {
        val count = AtomicInteger()
        val first = CountDownLatch(2)
        val second = CountDownLatch(4)
        val k = keepAlive(20) { count.incrementAndGet(); first.countDown(); second.countDown() }

        k.start(); assertTrue(first.await(5, TimeUnit.SECONDS)); k.stop()
        val afterFirst = count.get()
        k.start(); assertTrue(second.await(5, TimeUnit.SECONDS)); k.stop()

        assertTrue("le second démarrage a bien repris", count.get() > afterFirst)
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `the thread is a daemon so it can never keep the process alive`() {
        val k = keepAlive { }
        k.start()

        val thread = keepAliveThreads().single()

        assertTrue(thread.isDaemon)
        assertNotNull(thread)
    }

    // ---------------------------------------------------------------- erreurs

    @Test(timeout = 10_000)
    fun `a failing action stops the heartbeat and is reported exactly once`() {
        val calls = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val reported = CountDownLatch(1)
        val boom = IOException("socket fermée")
        val k = keepAlive(20, onError = { synchronized(errors) { errors += it }; reported.countDown() }) {
            if (calls.incrementAndGet() == 3) throw boom
        }

        k.start()
        assertTrue(reported.await(5, TimeUnit.SECONDS))
        Thread.sleep(150)

        assertEquals("l'action n'est plus appelée après l'échec", 3, calls.get())
        synchronized(errors) { assertEquals(listOf<Throwable>(boom), errors) }
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `a failing error handler does not crash anything`() {
        val ran = CountDownLatch(1)
        val k = keepAlive(20, onError = { ran.countDown(); throw IllegalStateException("onError défaillant") }) {
            throw IOException("échec")
        }

        k.start()

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `stop from inside the action does not deadlock`() {
        val calls = AtomicInteger()
        val holder = AtomicReference<KeepAlive>()
        val k = keepAlive(20) {
            if (calls.incrementAndGet() == 2) holder.get().stop()
        }
        holder.set(k)

        k.start()
        Thread.sleep(300)

        assertEquals("plus aucun appel après l'arrêt demandé par l'action", 2, calls.get())
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `stop from the error handler does not deadlock`() {
        val holder = AtomicReference<KeepAlive>()
        val handled = CountDownLatch(1)
        val k = keepAlive(20, onError = { holder.get().stop(); handled.countDown() }) { throw IOException("x") }
        holder.set(k)

        k.start()

        assertTrue(handled.await(5, TimeUnit.SECONDS))
        assertFalse(k.isRunning)
    }

    @Test
    fun `the interval is validated`() {
        assertThrows(IllegalArgumentException::class.java) { KeepAlive(9) { } }
        assertThrows(IllegalArgumentException::class.java) { KeepAlive(401) { } }
        assertThrows(IllegalArgumentException::class.java) { KeepAlive(0) { } }
        assertThrows(IllegalArgumentException::class.java) { KeepAlive(-100) { } }
        KeepAlive(10) { }
        KeepAlive(400) { }
        assertEquals(400L, KeepAlive.MAX_INTERVAL_MS)
        assertTrue("la valeur par défaut est dans la plage", ClientMessages.KEEP_ALIVE_INTERVAL_MS in KeepAlive.MIN_INTERVAL_MS..KeepAlive.MAX_INTERVAL_MS)
    }

    // ------------------------------------------------------- sur la connexion RFB

    @Test(timeout = 10_000)
    fun `over a socket it sends the keep alive request repeatedly`() = LoopbackPair().use { p ->
        val k = KeepAlive.forSocket(p.client, intervalMs = 20).also { toStop += it }

        k.start()
        Thread.sleep(300)
        k.stop()
        p.client.close()
        val received = p.receiveUntilEof()

        val message = ClientMessages.keepAliveRequest()
        assertEquals("des messages entiers seulement", 0, received.size % message.size)
        assertTrue("${received.size / message.size} battements reçus", received.size / message.size >= 3)
        for (i in 0 until received.size / message.size) {
            assertArrayEquals("message $i", message, received.copyOfRange(i * message.size, (i + 1) * message.size))
        }
    }

    @Test(timeout = 10_000)
    fun `over a closed socket it stops by itself and reports a typed error`() = LoopbackPair().use { p ->
        val error = AtomicReference<Throwable>()
        val reported = CountDownLatch(1)
        val k = KeepAlive.forSocket(p.client, 20) { error.set(it); reported.countDown() }.also { toStop += it }
        p.client.close()

        k.start()

        assertTrue(reported.await(5, TimeUnit.SECONDS))
        assertTrue("erreur typée : ${error.get()}", error.get() is RfbTransportException.Closed)
        assertFalse(k.isRunning)
    }

    @Test(timeout = 10_000)
    fun `it does not interleave with other messages written to the same socket`() = LoopbackPair().use { p ->
        val k = KeepAlive.forSocket(p.client, intervalMs = 10).also { toStop += it }
        val other = ClientMessages.framebufferUpdateRequest(false, 0, 0, 1280, 800)
        k.start()

        repeat(40) { p.client.write(other, 0, other.size); Thread.sleep(3) }
        k.stop()
        p.client.close()
        val received = p.receiveUntilEof()

        // Chaque message de 10 octets doit être l'un des deux messages connus, jamais un mélange.
        val keep = ClientMessages.keepAliveRequest()
        assertEquals(0, received.size % 10)
        for (i in 0 until received.size / 10) {
            val chunk = received.copyOfRange(i * 10, (i + 1) * 10)
            assertTrue("message $i corrompu", chunk.contentEquals(keep) || chunk.contentEquals(other))
        }
        assertEquals(40, (0 until received.size / 10).count { received.copyOfRange(it * 10, it * 10 + 10).contentEquals(other) })
    }

    // -------------------------------------------------------------- allocation

    @Test(timeout = 20_000)
    fun `a heartbeat over a socket allocates nothing`() = LoopbackPair().use { p ->
        assumeTrue("mesure d'allocation indisponible sur cette JVM", allocatedBytesOfThread(Thread.currentThread().id) != null)
        val k = KeepAlive.forSocket(p.client, intervalMs = 10).also { toStop += it }
        val drain = Thread { try { while (p.peer.getInputStream().read(ByteArray(4096)) >= 0) { } } catch (e: IOException) { } }
        drain.isDaemon = true
        drain.start()

        k.start()
        Thread.sleep(300) // échauffement
        val id = keepAliveThreads().single().id
        val before = allocatedBytesOfThread(id)!!
        Thread.sleep(1_000)
        val allocated = allocatedBytesOfThread(id)!! - before
        k.stop()

        // ~100 battements en 1 s : un tableau de 10 octets par battement ferait > 2 Kio (16 octets d'en-tête + 10).
        assertTrue("$allocated octets alloués par le thread du battement en 1 s", allocated < 2_048)
    }
}
