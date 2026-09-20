package fr.webinfoconcept.secondscreen.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** Compteurs de performance (SS-060) : rien quand c'est désactivé, des chiffres exacts quand c'est activé. */
class PerfStatsTest {

    private fun PerfStats.hitEverything() {
        onUpdate(3, 5_000, 2_000_000)
        onRender(false, 4_000, 1_000_000, 3_000_000, 100_000)
        traffic.onReceived(1_500)
        traffic.onSent(10)
        recordSessionThreadCpu()
    }

    @Test
    fun `disabled by default, and while disabled nothing is counted, not even traffic or cpu`() {
        val stats = PerfStats { 123L }

        assertEquals(false, stats.enabled)
        stats.hitEverything()

        assertEquals(PerfCounters(), stats.counters())
        assertEquals(0L, stats.takeDecodeMaxNs())
        assertEquals(0L, stats.takeRenderMaxNs())
    }

    @Test
    fun `enabled counts updates, decoded pixels, decode time and traffic exactly`() {
        val stats = PerfStats { 777L }
        stats.enabled = true

        stats.hitEverything()
        stats.onUpdate(1, 100, 500_000)

        val c = stats.counters()
        assertEquals(2, c.updates)
        assertEquals(4, c.rectangles)
        assertEquals(5_100, c.decodedPixels)
        assertEquals(2_500_000, c.decodeNs)
        assertEquals(1_500, c.bytesReceived)
        assertEquals(10, c.bytesSent)
        assertEquals(777L, c.sessionCpuNs)
    }

    @Test
    fun `renders count copy and draw time separately, and copied pixels`() {
        val stats = PerfStats()
        stats.enabled = true

        stats.onRender(false, 4_000, 1_000_000, 3_000_000, 100_000)
        stats.onRender(true, 100_000, 8_000_000, 20_000_000, 100_000)

        val c = stats.counters()
        assertEquals(2, c.renders)
        assertEquals(1, c.fullRedraws)
        assertEquals(104_000, c.copiedPixels)
        assertEquals(9_000_000, c.copyNs)
        assertEquals(23_000_000, c.drawNs)
    }

    @Test
    fun `a full screen copy is a copy of at least 90 percent outside a full redraw, and only that`() {
        val stats = PerfStats()
        stats.enabled = true
        val frame = 1_000

        stats.onRender(false, 899, 0, 0, frame)   // 89,9 % : pas plein écran
        stats.onRender(false, 900, 0, 0, frame)   // 90 % : plein écran
        stats.onRender(false, 1_000, 0, 0, frame) // 100 %
        stats.onRender(true, 1_000, 0, 0, frame)  // rendu complet voulu (surface) : pas une copie inutile
        stats.onRender(false, 5_000, 0, 0, 0)     // taille inconnue : jamais comptée

        assertEquals(2, stats.counters().fullScreenCopies)
    }

    @Test
    fun `maxima are the largest values and are reset when taken`() {
        val stats = PerfStats()
        stats.enabled = true

        stats.onUpdate(1, 1, 3_000_000); stats.onUpdate(1, 1, 9_000_000); stats.onUpdate(1, 1, 1_000_000)
        stats.onRender(false, 1, 1_000_000, 2_000_000, 10); stats.onRender(false, 1, 5_000_000, 6_000_000, 10)

        assertEquals(9_000_000, stats.takeDecodeMaxNs())
        assertEquals(11_000_000, stats.takeRenderMaxNs())
        assertEquals(0, stats.takeDecodeMaxNs())
        assertEquals(0, stats.takeRenderMaxNs())
    }

    @Test
    fun `reset clears every counter including traffic`() {
        val stats = PerfStats { 5L }
        stats.enabled = true
        stats.hitEverything()

        stats.reset()

        assertEquals(PerfCounters(), stats.counters())
    }

    @Test
    fun `the session thread cpu clock is read only when enabled`() {
        var reads = 0
        val stats = PerfStats { reads++; 1L }

        stats.recordSessionThreadCpu()
        assertEquals(0, reads)
        stats.enabled = true
        stats.recordSessionThreadCpu()
        assertEquals(1, reads)
    }

    @Test
    fun `turning it off stops counting at once and keeps what was counted`() {
        val stats = PerfStats()
        stats.enabled = true
        stats.onUpdate(1, 10, 1)
        stats.enabled = false

        stats.onUpdate(1, 10, 1)
        stats.traffic.onReceived(99)

        assertEquals(1, stats.counters().updates)
        assertEquals(0, stats.counters().bytesReceived)
    }

    @Test
    fun `negative or zero byte counts are ignored`() {
        val stats = PerfStats()
        stats.enabled = true

        stats.traffic.onReceived(-1)
        stats.traffic.onReceived(0)
        stats.traffic.onSent(-5)

        assertEquals(0, stats.counters().bytesReceived)
        assertEquals(0, stats.counters().bytesSent)
    }

    @Test(timeout = 20_000)
    fun `concurrent writers lose nothing`() {
        val stats = PerfStats()
        stats.enabled = true
        val threads = 6
        val each = 20_000
        val start = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(each) {
                    stats.onUpdate(2, 10, 5)
                    stats.onRender(false, 7, 3, 4, 100)
                    stats.traffic.onSent(3)
                }
            }.also { t -> t.start() }
        }
        start.countDown()
        workers.forEach { it.join() }

        val c = stats.counters()
        val total = (threads * each).toLong()
        assertEquals(total, c.updates)
        assertEquals(2 * total, c.rectangles)
        assertEquals(10 * total, c.decodedPixels)
        assertEquals(total, c.renders)
        assertEquals(7 * total, c.copiedPixels)
        assertEquals(3 * total, c.bytesSent)
    }

    // ============================================================ « peu coûteuses »

    @Test(timeout = 30_000)
    fun `a measurement point costs next to nothing when disabled, and stays cheap when enabled`() {
        val stats = PerfStats { 1L }
        val calls = 2_000_000

        repeat(200_000) { stats.onUpdate(1, 1, 1); stats.traffic.onReceived(1) } // échauffement
        var t = System.nanoTime()
        repeat(calls) { stats.onUpdate(1, 1, 1); stats.onRender(false, 1, 1, 1, 10); stats.traffic.onReceived(1) }
        val disabledNsPerCall = (System.nanoTime() - t) / (3.0 * calls)

        stats.enabled = true
        t = System.nanoTime()
        repeat(calls) { stats.onUpdate(1, 1, 1); stats.onRender(false, 1, 1, 1, 10); stats.traffic.onReceived(1) }
        val enabledNsPerCall = (System.nanoTime() - t) / (3.0 * calls)

        // Bornes très larges (machine chargée) : de l'ordre de la microseconde au pire ; mesure réelle dans PERFORMANCE.md.
        assertTrue("désactivé : $disabledNsPerCall ns/appel", disabledNsPerCall < 500)
        assertTrue("activé : $enabledNsPerCall ns/appel", enabledNsPerCall < 5_000)
    }
}
