package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Mode touchpad (SS-045) : position du pointeur distant, sensibilité, clics, glissement, molette. */
class TouchpadActionsTest {

    private class RecordingSink : MessageSink {
        val messages = mutableListOf<ByteArray>()
        var accept = true
        var acceptMoves = true
        override fun send(message: ByteArray): Boolean { if (accept) messages += message; return accept }
        override fun sendMove(message: ByteArray): Boolean { if (acceptMoves && accept) messages += message; return acceptMoves && accept }
    }

    private val sink = RecordingSink()
    private val position = PointerPosition()
    private var sensitivity = 1f
    private val mapper = PointerMapper(100, 80)
    private val tp = TouchpadActions(mapper, sink, position) { sensitivity }

    /** (masque, x, y) de chaque PointerEvent de 6 octets. */
    private fun events(): List<Triple<Int, Int, Int>> = sink.messages.flatMap { m ->
        (0 until m.size step 6).map { i ->
            assertEquals(5, m[i].toInt())
            Triple(m[i + 1].toInt() and 255, ((m[i + 2].toInt() and 255) shl 8) or (m[i + 3].toInt() and 255), ((m[i + 4].toInt() and 255) shl 8) or (m[i + 5].toInt() and 255))
        }
    }

    // ================================================================== déplacement

    @Test
    fun `the first move starts from the center of the remote screen`() {
        tp.onPointerMove(10f, 0f)

        assertEquals(listOf(Triple(0, 60, 40)), events())
        assertTrue(position.known)
    }

    @Test
    fun `the pointer moves by the finger movement times the sensitivity`() {
        sensitivity = 2f
        tp.onPointerMove(5f, -3f) // centre (50,40) + (10,-6)

        assertEquals(Triple(0, 60, 34), events().last())
    }

    @Test
    fun `the sensitivity is read at each move, so changing it takes effect at once`() {
        tp.onPointerMove(10f, 0f)            // x1 : 50 -> 60
        sensitivity = 3f
        tp.onPointerMove(10f, 0f)            // x3 : 60 -> 90

        assertEquals(listOf(60, 90), events().map { it.second })
    }

    @Test
    fun `the sensitivity is bounded, whatever the setting says`() {
        sensitivity = 1_000f
        tp.onPointerMove(1f, 0f)
        assertEquals(50 + 4, events().last().second)     // MAX 4,0

        sink.messages.clear()
        sensitivity = -5f
        tp.onPointerMove(10f, 0f)
        assertEquals(54 + 3, events().last().second)     // MIN 0,3 : 4,x + 3
    }

    @Test
    fun `sub-pixel movement is accumulated, not lost, and a message goes out only when the pixel changes`() {
        tp.onPointerMove(0.4f, 0f)
        tp.onPointerMove(0.4f, 0f)
        assertEquals("0,8 pixel : le pixel n'a pas changé", listOf(Triple(0, 50, 40)), events().takeLast(1).ifEmpty { listOf(Triple(0, 50, 40)) })
        val before = sink.messages.size
        tp.onPointerMove(0.4f, 0f) // 1,2 pixel cumulé

        assertEquals(Triple(0, 51, 40), events().last())
        assertTrue(sink.messages.size > before)
    }

    @Test
    fun `no message when the pixel does not change`() {
        tp.onPointerMove(10f, 10f)
        val count = sink.messages.size

        tp.onPointerMove(0.2f, 0.2f)
        tp.onPointerMove(-0.2f, -0.2f)

        assertEquals(count, sink.messages.size)
    }

    @Test
    fun `the pointer stops at the edges and never leaves the framebuffer`() {
        tp.onPointerMove(10_000f, 10_000f)
        assertEquals(Triple(0, 99, 79), events().last())

        val count = sink.messages.size
        tp.onPointerMove(500f, 500f)           // déjà au coin : rien à envoyer
        assertEquals(count, sink.messages.size)

        tp.onPointerMove(-10_000f, -10_000f)
        assertEquals(Triple(0, 0, 0), events().last())
        tp.onPointerMove(-5f, -5f)
        assertEquals(count + 1, sink.messages.size)
        for ((_, x, y) in events()) assertTrue("($x,$y)", x in 0..99 && y in 0..79)
    }

    @Test
    fun `moving back from an edge starts immediately, with no dead zone beyond the edge`() {
        tp.onPointerMove(10_000f, 0f)   // colle au bord droit
        tp.onPointerMove(-1f, 0f)

        assertEquals(98, events().last().second)
    }

    @Test
    fun `non finite deltas are ignored`() {
        tp.onPointerMove(Float.NaN, 1f)
        tp.onPointerMove(1f, Float.POSITIVE_INFINITY)

        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `moves are replaceable messages - a refused move is retried by the next one`() {
        sink.acceptMoves = false
        tp.onPointerMove(10f, 0f)
        assertFalse("pixel non mémorisé : le message n'est pas parti", position.known)

        sink.acceptMoves = true
        tp.onPointerMove(1f, 0f)

        assertEquals(Triple(0, 61, 40), events().last())
    }

    // ================================================================== clics

    @Test
    fun `a click is sent at the pointer, not under the finger`() {
        tp.onPointerMove(20f, -10f)      // (70,30)
        sink.messages.clear()

        tp.onClick()

        assertArrayEquals(ClientMessages.leftClick(70, 30), sink.messages.single())
    }

    @Test
    fun `a right click is sent at the pointer`() {
        tp.onPointerMove(-20f, 10f)      // (30,50)
        sink.messages.clear()

        tp.onRightClick()

        assertArrayEquals(ClientMessages.rightClick(30, 50), sink.messages.single())
    }

    @Test
    fun `a click with no known position happens at the center and makes it known`() {
        tp.onClick()

        assertArrayEquals(ClientMessages.leftClick(50, 40), sink.messages.single())
        assertTrue(position.known)
    }

    // ================================================================== glissement

    @Test
    fun `a drag presses at the pointer, moves carry the button, the release is at the final pixel`() {
        tp.onPointerMove(10f, 0f)        // (60,40)
        sink.messages.clear()

        tp.onDragStart()
        tp.onPointerMove(15f, 5f)        // (75,45)
        tp.onPointerMove(5f, 5f)         // (80,50)
        tp.onDragEnd()

        assertArrayEquals(ClientMessages.dragStart(60, 40), sink.messages[0])
        assertEquals(listOf(Triple(1, 75, 45), Triple(1, 80, 50), Triple(0, 80, 50)), events().drop(2))
    }

    @Test
    fun `if the press could not be sent the moves do not carry the button and no release is sent`() {
        tp.onPointerMove(10f, 0f)
        sink.messages.clear()
        sink.accept = false
        tp.onDragStart()
        sink.accept = true

        tp.onPointerMove(10f, 0f)
        tp.onDragEnd()

        assertEquals("un déplacement sans bouton, et rien d'autre", listOf(Triple(0, 70, 40)), events())
    }

    @Test
    fun `a drag end without a start sends nothing and two ends send one release`() {
        tp.onDragEnd()
        assertTrue(sink.messages.isEmpty())

        tp.onDragStart(); tp.onDragEnd(); tp.onDragEnd()

        assertEquals(2, sink.messages.size) // appui (12 octets) + un seul relâchement
    }

    // ================================================================== molette

    @Test
    fun `scrolling sends wheel clicks at the pointer position`() {
        tp.onPointerMove(10f, 10f)       // (60,50)
        sink.messages.clear()

        tp.onScroll(0, 2)
        tp.onScroll(-1, 0)

        assertArrayEquals(ClientMessages.wheel(PointerButtons.WHEEL_DOWN, 2, 60, 50), sink.messages[0])
        assertArrayEquals(ClientMessages.wheel(PointerButtons.WHEEL_LEFT, 1, 60, 50), sink.messages[1])
    }

    // ================================================================== position partagée

    @Test
    fun `after the direct mode moved the pointer, the touchpad continues from there without jumping`() {
        val direct = PointerActions(mapper, sink, position)
        direct.tap(20.4f, 60.2f)         // pixel (20,60)
        sink.messages.clear()

        tp.onPointerMove(5f, -10f)

        assertEquals(Triple(0, 25, 50), events().last())
    }

    @Test
    fun `after the touchpad moved the pointer, the direct mode knows where it is`() {
        tp.onPointerMove(10f, 10f)

        assertEquals(60, position.x)
        assertEquals(50, position.y)
    }

    @Test
    fun `a new session forgets the position`() {
        tp.onPointerMove(30f, 0f)
        position.reset()
        sink.messages.clear()

        tp.onPointerMove(10f, 0f)

        assertEquals("repart du centre", Triple(0, 60, 40), events().last())
    }

    @Test
    fun `a position outside a smaller new screen is brought back inside`() {
        tp.onPointerMove(40f, 30f)       // (90,70) sur 100x80
        tp.mapper = PointerMapper(50, 40)
        sink.messages.clear()

        tp.onPointerMove(1f, 1f)

        val (_, x, y) = events().last()
        assertTrue("($x,$y)", x in 0..49 && y in 0..39)
    }

    // ================================================================== de bout en bout

    /** Serveur simulé : suit le bouton gauche et la position ; refuse un déplacement bouton enfoncé sans appui. */
    private class ShadowServer(val width: Int, val height: Int) {
        var left = false
        fun feed(stream: List<ByteArray>) {
            for (m in stream) for (i in 0 until m.size step 6) {
                val mask = m[i + 1].toInt() and 255
                val x = ((m[i + 2].toInt() and 255) shl 8) or (m[i + 3].toInt() and 255)
                val y = ((m[i + 4].toInt() and 255) shl 8) or (m[i + 5].toInt() and 255)
                assertTrue("position hors écran : ($x,$y)", x in 0 until width && y in 0 until height)
                val wanted = mask and 1 != 0
                if (wanted && !left) left = true else if (!wanted && left) left = false
            }
        }
    }

    @Test
    fun `random gestures never leave the button down, never leave the screen, and never move with the button held without a press`() {
        val rnd = java.util.Random(45)
        repeat(200) { seq ->
            val s = RecordingSink()
            val pos = PointerPosition()
            val actions = TouchpadActions(PointerMapper(100, 80), s, pos) { 0.3f + rnd.nextFloat() * 3.7f }
            var pressed = false
            val d = TouchpadDetector(
                8f, object : TouchpadListener {
                    override fun onPointerMove(dx: Float, dy: Float) = actions.onPointerMove(dx, dy)
                    override fun onClick() = actions.onClick()
                    override fun onRightClick() = actions.onRightClick()
                    override fun onDragStart() { actions.onDragStart(); pressed = true }
                    override fun onDragEnd() { actions.onDragEnd(); pressed = false }
                },
                scroll = Scroll(actions)
            )
            var t = 0L
            repeat(250) {
                t += rnd.nextInt(300)
                val x = (rnd.nextInt(600) - 100).toFloat()
                val y = (rnd.nextInt(600) - 100).toFloat()
                when (rnd.nextInt(7)) {
                    0 -> d.onDown(x, y, t)
                    1, 2 -> d.onMove(x, y)
                    3 -> d.onUp(x, y, t)
                    4 -> d.onTwoFingersDown(x, y)
                    5 -> d.onTwoFingersMove(x, y)
                    else -> d.onCancel()
                }
            }
            d.onCancel()
            val server = ShadowServer(100, 80)
            // les messages de molette (masques 8..64) sont rejoués comme des boutons : on ne garde que les PointerEvent du bouton gauche
            server.feed(s.messages.filter { it.size % 6 == 0 })
            assertFalse("séquence $seq : bouton gauche resté enfoncé", server.left)
            assertFalse(pressed)
        }
    }

    // ================================================================== liaison bloquée

    private val senders = mutableListOf<PointerSender>()

    @After
    fun tearDown() { senders.forEach { it.stop() } }

    @Test(timeout = 20_000)
    fun `on a stuck link moves are dropped but the press and the release of a drag get through, in order`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val out = LinkedBlockingQueue<ByteArray>()
        val sender = PointerSender(capacity = 16) { entered.countDown(); release.await(); out += it }
            .also { it.start(); senders += it }
        val actions = TouchpadActions(PointerMapper(1280, 800), sender, PointerPosition())

        actions.onClick() // occupe l'écriture, qui se bloque
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        actions.onDragStart()
        for (i in 1..2000) actions.onPointerMove(1f + (i % 3), 1f)   // liaison bloquée
        actions.onDragEnd()
        release.countDown()

        val all = ArrayList<ByteArray>()
        while (true) all += (out.poll(300, TimeUnit.MILLISECONDS) ?: break)
        val stream = all.drop(1) // après le clic qui a bloqué l'écriture
        val press = stream.first()
        assertEquals("appui d'abord", 12, press.size)
        assertEquals(1, press[7].toInt() and 255)
        val last = stream.last()
        assertEquals("relâchement en dernier : masque 0", 0, last[1].toInt() and 255)
        assertTrue("des déplacements ont été abandonnés", sender.droppedCount > 1_000)
    }
}
