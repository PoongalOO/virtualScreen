package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Chaîne complète d'un tap (SS-040, SS-041), sans Android : événements tactiles -> [TapDetector] -> [PointerActions]
 * -> [PointerSender] -> vraie socket. Ce que le « serveur » reçoit est comparé octet par octet.
 */
class PointerActionsTest {

    private val senders = mutableListOf<PointerSender>()

    @After
    fun tearDown() {
        senders.forEach { it.stop() }
    }

    private fun collectingSender(into: LinkedBlockingQueue<ByteArray>) =
        PointerSender { into += it }.also { it.start(); senders += it }

    @Test(timeout = 10_000)
    fun `a tap becomes one left click at the framebuffer pixel`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        assertTrue(actions.tap(639.9f, 400.2f))

        assertArrayEquals(ClientMessages.leftClick(639, 400), out.poll(5, TimeUnit.SECONDS))
        assertTrue(out.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a tap outside the framebuffer sends nothing`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        assertFalse(actions.tap(1280f, 10f))
        assertFalse(actions.tap(10f, -3f))
        assertFalse(actions.tap(Float.NaN, 10f))
        Thread.sleep(50)

        assertTrue(out.isEmpty())
    }

    @Test
    fun `a tap is refused when the sender is stopped`() {
        val sender = PointerSender { }.also { senders += it } // jamais démarré
        val actions = PointerActions(PointerMapper(1280, 800), sender)

        assertFalse(actions.tap(10f, 10f))
    }

    @Test(timeout = 10_000)
    fun `the mapper can be replaced when the framebuffer size changes`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))
        assertFalse(actions.tap(1500f, 10f))

        actions.mapper = PointerMapper(1920, 1080)

        assertTrue(actions.tap(1500f, 10f))
        assertArrayEquals(ClientMessages.leftClick(1500, 10), out.poll(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun `touch gestures reach the server as exactly the expected clicks`() = LoopbackPair().use { p ->
        val sender = PointerSender.forSocket(p.client).also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)
        val detector = TapDetector(8f) { x, y -> actions.tap(x, y) }

        // 1. tap franc en (100, 200) -> un clic
        detector.onDown(100f, 200f, 0); detector.onUp(100f, 200f, 70)
        // 2. glissement -> aucun clic
        detector.onDown(300f, 300f, 200); detector.onMove(340f, 300f); detector.onUp(380f, 300f, 300)
        // 3. deux doigts -> aucun clic
        detector.onDown(500f, 500f, 400); detector.onSecondFingerDown(); detector.onUp(500f, 500f, 450)
        // 4. appui long -> aucun clic
        detector.onDown(600f, 600f, 600); detector.onUp(600f, 600f, 1_400)
        // 5. relâchement dupliqué -> un seul clic
        detector.onDown(1279f, 799f, 2_000); detector.onUp(1279f, 799f, 2_060); detector.onUp(1279f, 799f, 2_061)
        // 6. geste annulé -> aucun clic
        detector.onDown(10f, 10f, 3_000); detector.onCancel(); detector.onUp(10f, 10f, 3_050)

        val expected = ClientMessages.leftClick(100, 200) + ClientMessages.leftClick(1279, 799)
        assertArrayEquals(expected, p.receiveExactly(expected.size))
        sender.stop()
        p.client.close()
        assertEquals("aucun octet de plus", 0, p.receiveUntilEof().size)
    }
}
