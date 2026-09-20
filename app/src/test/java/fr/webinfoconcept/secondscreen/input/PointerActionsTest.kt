package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.PointerButtons
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
 * Chaîne complète d'un tap (SS-040, SS-041), sans Android : événements tactiles -> [TouchGestureDetector] -> [PointerActions]
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
        val detector = TouchGestureDetector(8f) { x, y -> actions.tap(x, y) }

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

    // =========================================================== glissement (SS-042)

    /**
     * Serveur simulé : rejoue un flux de `PointerEvent` et vérifie à chaque message la cohérence de l'état du
     * bouton gauche. Un déplacement bouton enfoncé sans appui préalable serait, pour un vrai serveur, un appui
     * fantôme : c'est le cas que ce simulateur refuse.
     */
    private class ShadowServer {
        var buttonDown = false
        var x = -1
        var y = -1
        val presses = mutableListOf<Pair<Int, Int>>()
        val releases = mutableListOf<Pair<Int, Int>>()
        val positions = mutableListOf<Pair<Int, Int>>()

        fun feed(stream: ByteArray) {
            assertEquals("flux non multiple de 6 octets", 0, stream.size % 6)
            for (i in stream.indices step 6) {
                assertEquals("type de message", 5, stream[i].toInt())
                val mask = stream[i + 1].toInt() and 0xFF
                x = ((stream[i + 2].toInt() and 0xFF) shl 8) or (stream[i + 3].toInt() and 0xFF)
                y = ((stream[i + 4].toInt() and 0xFF) shl 8) or (stream[i + 5].toInt() and 0xFF)
                assertTrue("masque inattendu : $mask", mask == 0 || mask == 1)
                val down = mask == 1
                if (down && !buttonDown) presses += x to y
                if (!down && buttonDown) releases += x to y
                buttonDown = down
                positions += x to y
            }
        }
    }

    private fun collected(out: LinkedBlockingQueue<ByteArray>): ByteArray {
        val all = java.io.ByteArrayOutputStream()
        while (true) all.write(out.poll(200, TimeUnit.MILLISECONDS) ?: break)
        return all.toByteArray()
    }

    @Test(timeout = 10_000)
    fun `a drag is press at the landing pixel, moves with the button held, release at the end pixel`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onDragStart(100.4f, 200.7f)
        actions.onDragMove(130.2f, 200.7f)
        actions.onDragMove(180.9f, 240.1f)
        actions.onDragEnd(181.5f, 241.5f)

        // le relâchement porte lui-même la position finale : pas de déplacement supplémentaire avant lui
        val expected = ClientMessages.dragStart(100, 200) +
            ClientMessages.pointerEvent(1, 130, 200) +
            ClientMessages.pointerEvent(1, 180, 240) +
            ClientMessages.pointerEvent(0, 181, 241)
        val stream = collected(out)
        assertArrayEquals(expected, stream)
        val server = ShadowServer().also { it.feed(stream) }
        assertEquals(listOf(100 to 200), server.presses)
        assertEquals(listOf(181 to 241), server.releases)
        assertFalse(server.buttonDown)
    }

    @Test(timeout = 10_000)
    fun `moves that stay on the same pixel send nothing`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onDragStart(100f, 100f)
        actions.onDragMove(100.2f, 100.9f) // même pixel que le départ
        actions.onDragMove(150f, 100f)
        actions.onDragMove(150.5f, 100.5f) // même pixel que le précédent
        actions.onDragEnd(150.5f, 100.5f)

        val stream = collected(out)
        assertArrayEquals(
            ClientMessages.dragStart(100, 100) + ClientMessages.pointerEvent(1, 150, 100) + ClientMessages.pointerEvent(0, 150, 100),
            stream
        )
    }

    @Test(timeout = 10_000)
    fun `a finger leaving the framebuffer keeps dragging along the edge and releases on it`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onDragStart(1200f, 700f)
        actions.onDragMove(1400f, 900f)   // hors cadre : bornée à (1279, 799)
        actions.onDragEnd(-20f, 900f)     // relâché hors cadre : (0, 799)

        val stream = collected(out)
        assertArrayEquals(
            ClientMessages.dragStart(1200, 700) + ClientMessages.pointerEvent(1, 1279, 799) + ClientMessages.pointerEvent(0, 0, 799),
            stream
        )
    }

    @Test(timeout = 10_000)
    fun `a drag that starts outside the framebuffer sends nothing at all, moves and end included`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onDragStart(1500f, 10f)
        actions.onDragMove(600f, 10f)
        actions.onDragMove(700f, 20f)
        actions.onDragEnd(700f, 20f)

        assertEquals("aucun octet : un déplacement bouton enfoncé serait un appui fantôme", 0, collected(out).size)
    }

    @Test
    fun `a drag whose press could not be queued sends nothing`() {
        val sender = PointerSender { }.also { senders += it } // arrêté : refuse tout
        val actions = PointerActions(PointerMapper(1280, 800), sender)

        actions.onDragStart(10f, 10f)
        actions.onDragMove(50f, 50f)
        actions.onDragEnd(50f, 50f)

        assertEquals(0L, sender.droppedCount)
        assertFalse(sender.isRunning)
    }

    @Test(timeout = 10_000)
    fun `an end without a start sends nothing and two ends send one release`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onDragEnd(10f, 10f)
        actions.onDragStart(10f, 10f)
        actions.onDragEnd(20f, 20f)
        actions.onDragEnd(20f, 20f)

        val server = ShadowServer().also { it.feed(collected(out)) }
        assertEquals(1, server.presses.size)
        assertEquals(1, server.releases.size)
    }

    @Test(timeout = 20_000)
    fun `on a stuck link moves are dropped but the press and the release always get through, in order`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val out = LinkedBlockingQueue<ByteArray>()
        val sender = PointerSender(capacity = 16) { entered.countDown(); release.await(); out += it }
            .also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)

        actions.tap(5f, 5f) // occupe l'écriture, qui se bloque
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        actions.onDragStart(100f, 100f)
        for (i in 1..2000) actions.onDragMove(100f + (i % 1000) * 0.5f + i / 1000f, 100f + i % 600) // liaison bloquée
        actions.onDragEnd(700f, 500f)
        release.countDown()

        val stream = collected(out)
        val server = ShadowServer()
        server.feed(stream.copyOfRange(ClientMessages.LEFT_CLICK_LENGTH, stream.size)) // après le clic bloqué
        assertEquals("appui au départ", listOf(100 to 100), server.presses)
        assertEquals("relâchement à la position finale", listOf(700 to 500), server.releases)
        assertFalse("jamais de bouton resté enfoncé", server.buttonDown)
        assertTrue("des déplacements ont été abandonnés", sender.droppedCount > 1_000)
    }

    @Test(timeout = 10_000)
    fun `the whole drag path over a real socket ends with the button released`() = LoopbackPair().use { p ->
        val sender = PointerSender.forSocket(p.client).also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)
        val detector = TouchGestureDetector(8f, dragListener = actions) { x, y -> actions.tap(x, y) }

        // tap, glissement, tap, glissement annulé par le système
        detector.onDown(10f, 10f, 0); detector.onUp(10f, 10f, 60)
        detector.onDown(300f, 300f, 200); detector.onMove(340f, 320f); detector.onMove(400f, 380f); detector.onUp(410f, 390f, 500)
        detector.onDown(20f, 30f, 800); detector.onUp(20f, 30f, 860)
        detector.onDown(600f, 100f, 1_000); detector.onMove(650f, 120f); detector.onCancel()

        val expected = ClientMessages.leftClick(10, 10) +
            ClientMessages.dragStart(300, 300) + ClientMessages.pointerEvent(1, 340, 320) +
            ClientMessages.pointerEvent(1, 400, 380) + ClientMessages.pointerEvent(0, 400, 380) +
            ClientMessages.leftClick(20, 30) +
            ClientMessages.dragStart(600, 100) + ClientMessages.pointerEvent(1, 650, 120) + ClientMessages.pointerEvent(0, 650, 120)
        val stream = p.receiveExactly(expected.size)
        assertArrayEquals(expected, stream)
        val server = ShadowServer().also { it.feed(stream) }
        assertFalse(server.buttonDown)
        assertEquals(4, server.presses.size)
        assertEquals(4, server.releases.size)
        sender.stop()
        p.client.close()
        assertEquals(0, p.receiveUntilEof().size)
    }

    /**
     * Propriété de bout en bout : quelle que soit la suite d'événements tactiles (au hasard, valides ou non), le
     * serveur simulé voit un flux cohérent (aucun déplacement bouton enfoncé sans appui : voir [ShadowServer]) et,
     * une fois le geste terminé ou annulé, le bouton est relâché avec autant de relâchements que d'appuis.
     */
    @Test(timeout = 60_000)
    fun `random touch sequences drained completely end with as many releases as presses`() {
        val rnd = java.util.Random(11)
        repeat(150) { seq ->
            val out = LinkedBlockingQueue<ByteArray>()
            val sender = PointerSender(capacity = 4096) { out += it }.also { it.start(); senders += it }
            val actions = PointerActions(PointerMapper(1280, 800), sender)
            val d = TouchGestureDetector(8f, dragListener = actions) { x, y -> actions.tap(x, y) }
            var t = 0L
            repeat(120) {
                t += rnd.nextInt(400)
                val x = (rnd.nextInt(1500) - 100).toFloat()
                val y = (rnd.nextInt(1000) - 100).toFloat()
                when (rnd.nextInt(6)) {
                    0 -> d.onDown(x, y, t)
                    1, 2 -> d.onMove(x, y)
                    3 -> d.onUp(x, y, t)
                    4 -> d.onSecondFingerDown()
                    else -> d.onCancel()
                }
            }
            d.onCancel()
            val stream = collected(out)
            val server = ShadowServer().also { it.feed(stream) }
            assertFalse("séquence $seq : bouton resté enfoncé", server.buttonDown)
            assertEquals("séquence $seq", server.presses.size, server.releases.size)
        }
    }

    // =========================================================== appui long -> clic droit (SS-043)

    @Test(timeout = 10_000)
    fun `a right click is sent as the right button at the framebuffer pixel`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        assertTrue(actions.rightClick(639.9f, 400.2f))

        assertArrayEquals(ClientMessages.rightClick(639, 400), out.poll(5, TimeUnit.SECONDS))
        assertTrue(out.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a right click outside the framebuffer sends nothing`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        assertFalse(actions.rightClick(1280f, 10f))
        assertFalse(actions.rightClick(Float.NaN, 10f))
        Thread.sleep(50)

        assertTrue(out.isEmpty())
    }

    @Test
    fun `a right click is refused when the sender is stopped`() {
        val actions = PointerActions(PointerMapper(1280, 800), PointerSender { }.also { senders += it })

        assertFalse(actions.rightClick(10f, 10f))
    }

    /** Timer simulé pour la chaîne complète. */
    private class ManualScheduler : DelayScheduler {
        var task: Runnable? = null
        override fun postDelayed(task: Runnable, delayMs: Long) { this.task = task }
        override fun cancel(task: Runnable) { if (this.task === task) this.task = null }
        fun fire() { task?.also { task = null }?.run() }
    }

    @Test(timeout = 10_000)
    fun `long press reaches the server as exactly one right click and never a left button`() = LoopbackPair().use { p ->
        val sender = PointerSender.forSocket(p.client).also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)
        val scheduler = ManualScheduler()
        val detector = TouchGestureDetector(
            8f, dragListener = actions,
            longPress = LongPress(500, scheduler) { x, y -> actions.rightClick(x, y) }
        ) { x, y -> actions.tap(x, y) }

        // 1. appui long : posé, le minuteur expire, le doigt frémit puis se lève
        detector.onDown(300f, 200f, 0); detector.onMove(303f, 201f); scheduler.fire(); detector.onUp(303f, 201f, 900)
        // 2. tap : un clic gauche
        detector.onDown(10f, 10f, 2_000); detector.onUp(10f, 10f, 2_070)
        // 3. appui long puis grand déplacement : ni glissement ni clic gauche
        detector.onDown(600f, 400f, 4_000); scheduler.fire(); detector.onMove(900f, 700f); detector.onUp(900f, 700f, 5_000)
        // 4. appui long interrompu par un deuxième doigt : rien
        detector.onDown(700f, 100f, 6_000); detector.onSecondFingerDown(); scheduler.fire(); detector.onUp(700f, 100f, 7_000)

        val expected = ClientMessages.rightClick(300, 200) + ClientMessages.leftClick(10, 10) + ClientMessages.rightClick(600, 400)
        val stream = p.receiveExactly(expected.size)
        assertArrayEquals(expected, stream)
        // le bouton gauche n'apparaît que dans le clic du tap : masques des trois messages de chaque clic
        val masks = (0 until stream.size step 6).map { stream[it + 1].toInt() }
        assertEquals(listOf(0, 4, 0, 0, 1, 0, 0, 4, 0), masks)
        sender.stop()
        p.client.close()
        assertEquals("aucun octet de plus", 0, p.receiveUntilEof().size)
    }

    // =========================================================== défilement (SS-044)

    @Test(timeout = 10_000)
    fun `scroll clicks are wheel events at the framebuffer pixel of the initial center`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onScrollStart(640.9f, 400.2f)
        actions.onScroll(0, 2)   // molette vers le bas, 2 crans
        actions.onScroll(0, -1)  // vers le haut
        actions.onScroll(1, 0)   // vers la droite
        actions.onScroll(-3, 0)  // vers la gauche

        val stream = collected(out)
        assertArrayEquals(
            ClientMessages.wheel(PointerButtons.WHEEL_DOWN, 2, 640, 400) +
                ClientMessages.wheel(PointerButtons.WHEEL_UP, 1, 640, 400) +
                ClientMessages.wheel(PointerButtons.WHEEL_RIGHT, 1, 640, 400) +
                ClientMessages.wheel(PointerButtons.WHEEL_LEFT, 3, 640, 400),
            stream
        )
    }

    @Test(timeout = 10_000)
    fun `the wheel position stays at the initial center even if the fingers travel far`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))
        val detector = TouchGestureDetector(8f, scroll = Scroll(actions, 40f)) { _, _ -> }

        detector.onDown(450f, 600f, 0)
        detector.onTwoFingersDown(500f, 600f)
        for (i in 1..10) detector.onTwoFingersMove(500f + i, 600f - i * 40f) // le centre parcourt 400 px vers le haut, 10 px de dérive

        val stream = collected(out)
        assertEquals(10 * 12, stream.size)
        for (i in stream.indices step 6) {
            val x = ((stream[i + 2].toInt() and 255) shl 8) or (stream[i + 3].toInt() and 255)
            val y = ((stream[i + 4].toInt() and 255) shl 8) or (stream[i + 5].toInt() and 255)
            assertEquals("x", 500, x)
            assertEquals("y", 600, y)
        }
    }

    @Test(timeout = 10_000)
    fun `an initial center outside the framebuffer is clamped to the edge, and scroll still works`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onScrollStart(1500f, -20f)
        actions.onScroll(0, 1)

        assertArrayEquals(ClientMessages.wheel(PointerButtons.WHEEL_DOWN, 1, 1279, 0), collected(out))
    }

    @Test(timeout = 10_000)
    fun `scroll without a valid start sends nothing`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))

        actions.onScroll(0, 1)                     // jamais démarré
        actions.onScrollStart(Float.NaN, 10f)      // centre illisible
        actions.onScroll(0, 1)

        assertEquals(0, collected(out).size)
    }

    @Test(timeout = 10_000)
    fun `a zero scroll sends nothing and an oversized one is capped to the message limit`() {
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out))
        actions.onScrollStart(10f, 10f)

        actions.onScroll(0, 0)
        actions.onScroll(0, 1_000)

        val stream = collected(out)
        assertEquals(12 * ClientMessages.MAX_WHEEL_CLICKS, stream.size)
    }

    @Test
    fun `scroll is refused silently when the sender is stopped`() {
        val actions = PointerActions(PointerMapper(1280, 800), PointerSender { }.also { senders += it })

        actions.onScrollStart(10f, 10f)
        actions.onScroll(0, 1) // ne plante pas
    }

    @Test(timeout = 20_000)
    fun `on a stuck link wheel messages are dropped whole and the stream stays balanced, the release always gets through`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val out = LinkedBlockingQueue<ByteArray>()
        val sender = PointerSender(capacity = 16) { entered.countDown(); release.await(); out += it }
            .also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)

        actions.tap(5f, 5f) // occupe l'écriture, qui se bloque
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        actions.onScrollStart(300f, 300f)
        for (i in 1..500) actions.onScroll(0, 1 + i % 3) // liaison bloquée : la file se remplit de crans
        actions.tap(9f, 9f)                              // un message d'état passe malgré tout (place réservée)
        release.countDown()

        val stream = collected(out)
        val server = ShadowServerWithWheel().also { it.feed(stream) }
        assertTrue("des crans ont été abandonnés", sender.droppedCount > 400)
        assertEquals("aucune molette restée enfoncée", 0, server.mask)
        assertTrue("le dernier clic de gauche a bien été envoyé", server.leftPresses >= 2)
        assertEquals("chaque appui de molette a son relâchement", server.wheelPresses, server.wheelReleases)
    }

    /** Serveur simulé : suit le masque de boutons, y compris la molette. */
    private class ShadowServerWithWheel {
        var mask = 0
        var leftPresses = 0
        var wheelPresses = 0
        var wheelReleases = 0
        fun feed(stream: ByteArray) {
            assertEquals(0, stream.size % 6)
            for (i in stream.indices step 6) {
                assertEquals(5, stream[i].toInt())
                val new = stream[i + 1].toInt() and 0xFF
                val pressed = new and mask.inv()
                val released = mask and new.inv()
                if (pressed and PointerButtons.LEFT != 0) leftPresses++
                if (pressed and (PointerButtons.WHEEL_UP or PointerButtons.WHEEL_DOWN or PointerButtons.WHEEL_LEFT or PointerButtons.WHEEL_RIGHT) != 0) wheelPresses++
                if (released and (PointerButtons.WHEEL_UP or PointerButtons.WHEEL_DOWN or PointerButtons.WHEEL_LEFT or PointerButtons.WHEEL_RIGHT) != 0) wheelReleases++
                mask = new
            }
        }
    }

    @Test(timeout = 10_000)
    fun `a two finger scroll reaches the server as wheel clicks only - never a button, never a click`() = LoopbackPair().use { p ->
        val sender = PointerSender.forSocket(p.client).also { it.start(); senders += it }
        val actions = PointerActions(PointerMapper(1280, 800), sender)
        val detector = TouchGestureDetector(8f, dragListener = actions, scroll = Scroll(actions, 40f)) { x, y -> actions.tap(x, y) }

        // deux doigts, défilement vers le haut de 130 px (3 crans, reste 10) puis vers la droite de 45 px (1 cran)
        detector.onDown(450f, 500f, 0)
        detector.onTwoFingersDown(500f, 500f)
        detector.onTwoFingersMove(500f, 400f)
        detector.onTwoFingersMove(500f, 370f)
        detector.onTwoFingersMove(560f, 370f)
        detector.onCancel(); detector.onUp(500f, 370f, 900) // les doigts se lèvent : rien de plus
        // un tap ensuite : clic gauche normal
        detector.onDown(10f, 10f, 2_000); detector.onUp(10f, 10f, 2_060)

        val expected = ClientMessages.wheel(PointerButtons.WHEEL_DOWN, 3, 500, 500) +
            ClientMessages.wheel(PointerButtons.WHEEL_LEFT, 1, 500, 500) + // doigts vers la droite, sens naturel : molette gauche
            ClientMessages.leftClick(10, 10)
        val stream = p.receiveExactly(expected.size)
        assertArrayEquals(expected, stream)
        val server = ShadowServerWithWheel().also { it.feed(stream) }
        assertEquals(0, server.mask)
        assertEquals(1, server.leftPresses)
        assertEquals(4, server.wheelPresses)
        sender.stop()
        p.client.close()
        assertEquals("aucun octet de plus", 0, p.receiveUntilEof().size)
    }

    // =========================================================== position partagée (SS-045)

    @Test
    fun `the shared position follows the taps, the drags and the wheel, and only when something is sent`() {
        val position = PointerPosition()
        val out = LinkedBlockingQueue<ByteArray>()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(out), position)

        actions.onScrollStart(640f, 400f) // deux doigts posés, aucun cran encore
        assertFalse("rien n'est parti : le pointeur distant n'a pas bougé", position.known)
        actions.onScroll(0, 0)
        assertFalse(position.known)

        actions.onScroll(0, 1)
        assertEquals(640 to 400, position.x to position.y)

        actions.tap(100.5f, 200.5f)
        assertEquals(100 to 200, position.x to position.y)
        actions.rightClick(300f, 310f)
        assertEquals(300 to 310, position.x to position.y)

        actions.onDragStart(10f, 20f)
        actions.onDragMove(50f, 60f)
        assertEquals(50 to 60, position.x to position.y)
        actions.onDragEnd(70f, 80f)
        assertEquals(70 to 80, position.x to position.y)
    }

    @Test
    fun `a tap outside the framebuffer does not change the shared position`() {
        val position = PointerPosition()
        val actions = PointerActions(PointerMapper(1280, 800), collectingSender(LinkedBlockingQueue()), position)

        actions.tap(5000f, 5000f)

        assertFalse(position.known)
    }
}
