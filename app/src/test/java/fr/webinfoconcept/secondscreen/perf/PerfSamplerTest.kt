package fr.webinfoconcept.secondscreen.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calcul des débits et moyennes d'un intervalle (SS-060), sans horloge réelle. */
class PerfSamplerTest {

    private val second = 1_000_000_000L

    private fun counters(
        updates: Long = 0, pixels: Long = 0, decodeNs: Long = 0, renders: Long = 0, copyNs: Long = 0, drawNs: Long = 0,
        copied: Long = 0, full: Long = 0, redraws: Long = 0, rx: Long = 0, tx: Long = 0, cpu: Long = 0
    ) = PerfCounters(updates, updates, pixels, decodeNs, renders, redraws, copyNs, drawNs, copied, full, rx, tx, cpu)

    @Test
    fun `rates are counts over the elapsed time`() {
        val a = counters()
        val b = counters(updates = 40, pixels = 8_000_000, renders = 30, rx = 2_000_000, tx = 4_000)

        val p = PerfSnapshot.between(a, b, 2 * second, 0, 0, 0)

        assertEquals(20f, p.updatesPerSecond, 1e-4f)
        assertEquals(15f, p.rendersPerSecond, 1e-4f)
        assertEquals(4f, p.megapixelsPerSecond, 1e-4f)
        assertEquals(1_000_000L, p.bytesReceivedPerSecond)
        assertEquals(2_000L, p.bytesSentPerSecond)
    }

    @Test
    fun `averages are per event, in milliseconds`() {
        val b = counters(updates = 4, decodeNs = 40_000_000, renders = 5, copyNs = 10_000_000, drawNs = 40_000_000, copied = 500_000)

        val p = PerfSnapshot.between(counters(), b, second, 12_000_000, 30_000_000, 0)

        assertEquals(10f, p.decodeAvgMs, 1e-4f)
        assertEquals(12f, p.decodeMaxMs, 1e-4f)
        assertEquals(10f, p.renderAvgMs, 1e-4f)    // (10 + 40) / 5
        assertEquals(2f, p.copyAvgMs, 1e-4f)
        assertEquals(8f, p.drawAvgMs, 1e-4f)
        assertEquals(30f, p.renderMaxMs, 1e-4f)
        assertEquals(100_000L, p.copiedPixelsPerRender)
    }

    @Test
    fun `no event means zero averages, never a division by zero`() {
        val p = PerfSnapshot.between(counters(), counters(), second, 0, 0, 0)

        assertEquals(0f, p.decodeAvgMs, 0f)
        assertEquals(0f, p.renderAvgMs, 0f)
        assertEquals(0L, p.copiedPixelsPerRender)
        assertEquals(0f, p.updatesPerSecond, 0f)
        assertTrue(p.updatesPerSecond.isFinite() && p.renderAvgMs.isFinite() && p.cpuPercent >= 0)
    }

    @Test
    fun `cpu is the thread cpu time over the elapsed time, as a percentage of one core, clamped`() {
        assertEquals(45, PerfSnapshot.between(counters(), counters(cpu = 450_000_000), second, 0, 0, 0).cpuPercent)
        assertEquals(0, PerfSnapshot.between(counters(), counters(), second, 0, 0, 0).cpuPercent)
        assertEquals(100, PerfSnapshot.between(counters(), counters(cpu = 5 * second), second, 0, 0, 0).cpuPercent)
    }

    @Test
    fun `full screen copies and full redraws are counted over the interval only`() {
        val a = counters(full = 3, redraws = 1)
        val b = counters(full = 8, redraws = 2)

        val p = PerfSnapshot.between(a, b, second, 0, 0, 0)

        assertEquals(5L, p.fullScreenCopies)
        assertEquals(1L, p.fullRedraws)
    }

    @Test
    fun `a counter that went backwards after a reset counts as zero, not as a huge negative rate`() {
        val p = PerfSnapshot.between(counters(updates = 100, rx = 9_999), counters(updates = 3, rx = 5), second, 0, 0, 0)

        assertEquals(0f, p.updatesPerSecond, 0f)
        assertEquals(0L, p.bytesReceivedPerSecond)
    }

    @Test
    fun `a null or negative interval gives the empty snapshot`() {
        assertEquals(PerfSnapshot.EMPTY, PerfSnapshot.between(counters(), counters(updates = 5), 0, 0, 0, 0))
        assertEquals(PerfSnapshot.EMPTY, PerfSnapshot.between(counters(), counters(updates = 5), -second, 0, 0, 0))
    }

    @Test
    fun `heap is reported in kibibytes`() {
        assertEquals(2_048L, PerfSnapshot.between(counters(), counters(), second, 0, 0, 2 * 1024 * 1024).heapUsedKb)
    }

    @Test
    fun `the sampler sets a reference on its first call and then reports each interval`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)

        assertEquals(PerfSnapshot.EMPTY, sampler.sample(10 * second, 0))
        repeat(30) { stats.onUpdate(1, 100, 1_000_000) }
        val first = sampler.sample(11 * second, 0)
        repeat(10) { stats.onUpdate(1, 100, 1_000_000) }
        val second2 = sampler.sample(12 * second, 0)

        assertEquals(30f, first.updatesPerSecond, 1e-4f)
        assertEquals(10f, second2.updatesPerSecond, 1e-4f)
    }

    @Test
    fun `the sampler takes and resets the maxima each time`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)
        sampler.sample(0, 0)
        stats.onUpdate(1, 1, 8_000_000)

        assertEquals(8f, sampler.sample(second, 0).decodeMaxMs, 1e-4f)
        assertEquals("plus rien d'anormal la seconde suivante", 0f, sampler.sample(2 * second, 0).decodeMaxMs, 0f)
    }

    @Test
    fun `reset makes the next sample a fresh reference`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)
        sampler.sample(0, 0)
        stats.onUpdate(1, 1, 1)

        sampler.reset()

        assertEquals(PerfSnapshot.EMPTY, sampler.sample(second, 0))
    }
}
