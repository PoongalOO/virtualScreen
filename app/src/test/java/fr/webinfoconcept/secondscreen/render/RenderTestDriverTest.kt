package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.RectangleListener
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfThread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pilote de mise à jour de test (SS-031). La propriété centrale : **tout pixel modifié a été signalé**. Un pixel
 * modifié sans signalement ne serait jamais dessiné (le rendu ne copie que la zone signalée).
 */
class RenderTestDriverTest {

    /** Cible qui reconstruit l'écran EXACTEMENT comme le ferait le rendu : uniquement à partir des zones signalées. */
    private class ShadowTarget(private val framebuffer: Framebuffer) : RenderTarget {
        val shadow = Framebuffer(framebuffer.width, framebuffer.height)
        val dirty = DirtyRegion(framebuffer.width, framebuffer.height)
        var updates = 0
        val rects = mutableListOf<IntArray>()
        private val bounds = IntArray(4)

        init { framebuffer.pixels.copyInto(shadow.pixels) } // l'état déjà affiché

        override val rectangleListener = object : RectangleListener {
            override fun onRectangle(x: Int, y: Int, w: Int, h: Int) {
                dirty.add(x, y, w, h)
                synchronized(rects) { rects += intArrayOf(x, y, w, h) }
            }
        }

        override fun onFramebufferUpdated() {
            updates++
            if (dirty.take(bounds)) {
                // Comme le rendu : on ne recopie que la zone signalée.
                shadow.writeRect(bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1],
                    framebuffer.pixels, bounds[1] * framebuffer.width + bounds[0], framebuffer.width)
            }
        }
    }

    private fun awaitFinished(driver: RenderTestDriver) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!driver.finished && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("le pilote n'a pas terminé", driver.finished)
    }

    @Test(timeout = 30_000)
    fun `every modified pixel is signalled so the rendered copy equals the framebuffer`() {
        val fb = RenderTestPattern.create(400, 300)
        val target = ShadowTarget(fb)
        val driver = RenderTestDriver(fb, target, maxFrames = 300)

        driver.start()
        awaitFinished(driver)
        driver.stop()

        assertEquals(300, target.updates)
        assertArrayEquals("l'écran reconstruit d'après les zones signalées doit être identique", fb.pixels, target.shadow.pixels)
    }

    @Test(timeout = 30_000)
    fun `the final content is deterministic pattern plus the square of the last frame`() {
        val frames = 250
        val fb = RenderTestPattern.create(320, 240)
        val driver = RenderTestDriver(fb, ShadowTarget(fb), maxFrames = frames)

        driver.start(); awaitFinished(driver); driver.stop()

        val lastX = RenderTestDriver.squareX(frames - 1, 320)
        val lastY = RenderTestDriver.squareY(frames - 1, 240)
        val colour = RenderTestDriver.squareColor(frames - 1)
        for (y in 0 until 240) for (x in 0 until 320) {
            val inSquare = x in lastX until lastX + 64 && y in lastY until lastY + 64
            val expected = if (inSquare) colour else RenderTestPattern.pixel(x, y, 320, 240)
            assertEquals("($x,$y)", expected, fb.getPixel(x, y))
        }
    }

    @Test(timeout = 30_000)
    fun `each frame signals two rectangles and asks for one render`() {
        val fb = RenderTestPattern.create(320, 240)
        val target = ShadowTarget(fb)
        val driver = RenderTestDriver(fb, target, maxFrames = 10)

        driver.start(); awaitFinished(driver); driver.stop()

        assertEquals(10, target.updates)
        assertEquals("1 rectangle à l'image 0, puis 2 par image", 1 + 9 * 2, target.rects.size)
        assertTrue(target.rects.all { it[2] == 64 && it[3] == 64 })
    }

    @Test
    fun `the square always stays fully inside the screen`() {
        for (frame in 0 until 5_000) {
            val x = RenderTestDriver.squareX(frame, 1280)
            val y = RenderTestDriver.squareY(frame, 800)
            assertTrue("x=$x", x >= 0 && x + 64 <= 1280)
            assertTrue("y=$y", y >= 0 && y + 64 <= 800)
        }
    }

    @Test
    fun `the square colour is opaque and changes`() {
        assertTrue((0 until 100).all { RenderTestDriver.squareColor(it) ushr 24 == 0xFF })
        assertTrue((0 until 100).map { RenderTestDriver.squareColor(it) }.toSet().size > 50)
    }

    @Test(timeout = 30_000)
    fun `stop halts the production and is idempotent`() {
        val fb = RenderTestPattern.create(320, 240)
        val target = ShadowTarget(fb)
        val driver = RenderTestDriver(fb, target, targetFps = 200)

        driver.start()
        Thread.sleep(100)
        driver.stop()
        val atStop = target.updates
        Thread.sleep(100)
        driver.stop()

        assertEquals("plus aucune image après stop()", atStop, target.updates)
        assertTrue(atStop > 0)
        assertFalse(driver.isRunning)
        assertFalse("arrêté, pas terminé de lui-même", driver.finished)
    }

    @Test(timeout = 30_000)
    fun `a target frame rate is respected and not exceeded`() {
        val fb = RenderTestPattern.create(320, 240)
        val driver = RenderTestDriver(fb, ShadowTarget(fb), maxFrames = 20, targetFps = 100)

        driver.start(); awaitFinished(driver); driver.stop()

        // 20 images à 100 par seconde : au moins ~190 ms (jamais plus rapide), avec une marge pour une machine chargée.
        assertTrue("${driver.elapsedMs} ms pour 20 images à 100 i/s", driver.elapsedMs >= 150)
    }

    @Test(timeout = 60_000)
    fun `producing frames allocates nothing`() {
        assumeTrue("mesure d'allocation indisponible", allocatedBytesOfThread(Thread.currentThread().id) != null)
        val fb = RenderTestPattern.create(640, 480)
        val driver = RenderTestDriver(fb, object : RenderTarget {
            override val rectangleListener = object : RectangleListener { override fun onRectangle(x: Int, y: Int, w: Int, h: Int) {} }
            override fun onFramebufferUpdated() {}
        })

        driver.start()
        Thread.sleep(500) // échauffement
        val thread = Thread.getAllStackTraces().keys.single { it.name == "secondscreen-render-test" && it.isAlive }
        val framesBefore = driver.frames
        val before = allocatedBytesOfThread(thread.id)!!
        Thread.sleep(1_000)
        val allocated = allocatedBytesOfThread(thread.id)!! - before
        val framesDone = driver.frames - framesBefore
        driver.stop()

        assertTrue("trop peu d'images pour conclure : $framesDone", framesDone > 50)
        // Un IntArray par image ferait plusieurs centaines de Kio par seconde.
        assertTrue("$allocated octets pour $framesDone images", allocated < 4_096)
    }
}
