package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Envoi des messages d'entrée hors du thread appelant, file bornée, erreurs (SS-040). */
class PointerSenderTest {

    private val toStop = mutableListOf<PointerSender>()

    @After
    fun tearDown() {
        toStop.forEach { it.stop() }
    }

    private fun sender(
        capacity: Int = 8,
        onError: (Throwable) -> Unit = {},
        write: (ByteArray) -> Unit
    ): PointerSender = PointerSender(onError, capacity, write).also { toStop += it }

    private fun msg(n: Int) = ClientMessages.pointerEvent(0, n, n)

    @Test
    fun `refuses messages until started`() {
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender { written += it }

        assertFalse(s.isRunning)
        assertFalse(s.send(msg(1)))
        Thread.sleep(50)

        assertTrue(written.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `delivers every message in order`() {
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender(capacity = 64) { written += it }
        s.start()

        for (i in 0 until 50) assertTrue(s.send(msg(i)))

        for (i in 0 until 50) assertArrayEquals(msg(i), written.poll(5, TimeUnit.SECONDS))
        assertEquals(0, s.droppedCount)
    }

    @Test(timeout = 10_000)
    fun `writes on a dedicated thread, never on the caller thread`() {
        val writer = AtomicReference<Thread>()
        val done = CountDownLatch(1)
        val s = sender { writer.set(Thread.currentThread()); done.countDown() }
        s.start()

        s.send(msg(1))
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertNotEquals(Thread.currentThread(), writer.get())
        assertEquals("secondscreen-input", writer.get().name)
        assertTrue(writer.get().isDaemon)
    }

    @Test(timeout = 10_000)
    fun `send never blocks even when the link is stuck, and drops whole messages`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender(capacity = 4) { entered.countDown(); release.await(); written += it }
        s.start()

        s.send(msg(0))
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // l'écriture est maintenant bloquée
        val accepted = (1..4).count { s.send(msg(it)) } // remplit la file
        val start = System.nanoTime()
        val refused = (5..1004).count { !s.send(msg(it)) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(4, accepted)
        assertEquals(1000, refused)
        assertEquals(1000, s.droppedCount.toInt())
        assertTrue("send a bloqué : $elapsedMs ms pour 1000 appels", elapsedMs < 1_000)

        release.countDown()
        // ce qui a été accepté arrive entier et dans l'ordre : 0 puis 1..4
        for (i in 0..4) assertArrayEquals(msg(i), written.poll(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun `moves are dropped first - a quarter of the queue stays reserved for state messages`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender(capacity = 8) { entered.countDown(); release.await(); written += it }
        s.start()
        s.send(msg(0))
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // l'écriture est bloquée, la file (8) est vide

        val acceptedMoves = (1..100).count { s.sendMove(msg(it)) }
        val acceptedState = (1..2).count { s.send(msg(1000 + it)) } // la réserve : 8 / 4 = 2 places

        assertEquals("6 déplacements (8 - réserve de 2)", 6, acceptedMoves)
        assertEquals("les 2 messages d'état passent malgré la file de déplacements pleine", 2, acceptedState)
        assertEquals(94, s.droppedCount.toInt())
        release.countDown()
        // ordre conservé : 0, les 6 déplacements, puis les 2 messages d'état
        val expected = listOf(0, 1, 2, 3, 4, 5, 6, 1001, 1002)
        for (n in expected) assertArrayEquals(msg(n), written.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `sendMove is refused when stopped and never blocks`() {
        val s = sender { }
        assertFalse(s.sendMove(msg(1)))
        s.start()
        s.stop()
        assertFalse(s.sendMove(msg(1)))
    }

    @Test(timeout = 10_000)
    fun `a write failure stops the sender and reports the error once`() {
        val errors = AtomicInteger()
        val error = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        val s = sender(onError = { errors.incrementAndGet(); error.set(it); failed.countDown() }) {
            throw IOException("socket fermée")
        }
        s.start()

        s.send(msg(1))
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)

        assertEquals(1, errors.get())
        assertEquals("socket fermée", error.get().message)
        assertFalse(s.isRunning)
        assertFalse("après l'erreur, plus rien n'est accepté", s.send(msg(2)))
        assertEquals(1, errors.get())
    }

    @Test(timeout = 10_000)
    fun `a failing onError does not crash the process`() {
        val called = CountDownLatch(1)
        val s = sender(onError = { called.countDown(); throw IllegalStateException("boom") }) {
            throw IOException("x")
        }
        s.start()

        s.send(msg(1))

        assertTrue(called.await(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun `stop discards pending messages and later sends are refused`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender(capacity = 8) { entered.countDown(); release.await(); written += it }
        s.start()
        s.send(msg(0))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        for (i in 1..5) s.send(msg(i)) // en attente derrière l'écriture bloquée

        s.stop()
        release.countDown()
        Thread.sleep(100)

        assertFalse(s.isRunning)
        assertFalse(s.send(msg(9)))
        // seul le message déjà en cours d'écriture peut sortir ; les 5 en attente sont abandonnés
        assertTrue("messages périmés envoyés : ${written.size}", written.size <= 1)
    }

    @Test(timeout = 10_000)
    fun `stop and start are idempotent and the sender can be restarted without stale messages`() {
        val written = LinkedBlockingQueue<ByteArray>()
        val s = sender { written += it }

        s.stop() // jamais démarré
        s.start()
        s.start()
        s.send(msg(1))
        assertArrayEquals(msg(1), written.poll(5, TimeUnit.SECONDS))
        s.stop()
        s.stop()
        assertFalse(s.isRunning)

        s.start()
        assertTrue(s.isRunning)
        s.send(msg(2))
        assertArrayEquals(msg(2), written.poll(5, TimeUnit.SECONDS))
        assertTrue(written.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `stop leaves no sender thread alive`() {
        val s = sender { }
        s.start()
        s.stop()

        val alive = Thread.getAllStackTraces().keys.count { it.name == "secondscreen-input" && it.isAlive }
        assertEquals(0, alive)
    }

    @Test(timeout = 10_000)
    fun `stop can be called from the write callback itself`() {
        val stopped = CountDownLatch(1)
        lateinit var s: PointerSender
        s = sender { s.stop(); stopped.countDown() }
        s.start()

        s.send(msg(1))

        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertFalse(s.isRunning)
    }

    @Test
    fun `invalid capacity is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PointerSender(capacity = 0) { } }
        assertThrows(IllegalArgumentException::class.java) { PointerSender(capacity = 100_000) { } }
    }

    // ---------------------------------------------------------- sur une vraie socket

    @Test(timeout = 10_000)
    fun `forSocket delivers messages to the server byte for byte and in order`() = LoopbackPair().use { p ->
        val s = PointerSender.forSocket(p.client).also { toStop += it }
        s.start()
        val messages = listOf(ClientMessages.leftClick(1, 2), ClientMessages.leftClick(1279, 799), ClientMessages.pointerEvent(0, 5, 6))

        messages.forEach { assertTrue(s.send(it)) }

        val expected = messages.fold(ByteArray(0)) { acc, m -> acc + m }
        assertArrayEquals(expected, p.receiveExactly(expected.size))
    }

    @Test(timeout = 10_000)
    fun `forSocket reports a closed socket through onError`() = LoopbackPair().use { p ->
        val error = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        val s = PointerSender.forSocket(p.client) { error.set(it); failed.countDown() }.also { toStop += it }
        s.start()
        p.client.close()

        s.send(ClientMessages.leftClick(1, 1))

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertTrue(error.get() is RfbTransportException)
        assertFalse(s.isRunning)
    }

    @Test(timeout = 20_000)
    fun `clicks stay intact when the keep-alive writes at the same time`() = LoopbackPair().use { p ->
        val s = PointerSender.forSocket(p.client).also { toStop += it }
        s.start()
        val keepAlive = fr.webinfoconcept.secondscreen.net.KeepAlive.forSocket(p.client, intervalMs = 10)
        keepAlive.start()
        val clicks = 40
        try {
            for (i in 0 until clicks) {
                assertTrue(s.send(ClientMessages.leftClick(i, i)))
                Thread.sleep(5)
            }
            Thread.sleep(50)
        } finally {
            keepAlive.stop()
            s.stop()
        }
        p.client.close()

        // Le flux est une suite de messages entiers : 10 octets (FramebufferUpdateRequest) ou 18 (clic).
        val stream = p.receiveUntilEof()
        var pos = 0
        var clicksSeen = 0
        while (pos < stream.size) {
            when (stream[pos].toInt()) {
                3 -> pos += 10
                5 -> {
                    assertArrayEquals(ClientMessages.leftClick(clicksSeen, clicksSeen), stream.copyOfRange(pos, pos + 18))
                    clicksSeen++
                    pos += 18
                }
                else -> throw AssertionError("flux désaligné à l'octet $pos : ${stream[pos]}")
            }
        }
        assertEquals(clicks, clicksSeen)
    }
}
