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

        val p = PerfSnapshot.between(a, b, 2 * second, 0, 0)

        assertEquals(20f, p.updatesPerSecond, 1e-4f)
        assertEquals(15f, p.rendersPerSecond, 1e-4f)
        assertEquals(4f, p.megapixelsPerSecond, 1e-4f)
        assertEquals(1_000_000L, p.bytesReceivedPerSecond)
        assertEquals(2_000L, p.bytesSentPerSecond)
    }

    @Test
    fun `averages are per event, in milliseconds`() {
        val b = counters(updates = 4, decodeNs = 40_000_000, renders = 5, copyNs = 10_000_000, drawNs = 40_000_000, copied = 500_000)

        val p = PerfSnapshot.between(counters(), b, second, 12_000_000, 30_000_000)

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
        val p = PerfSnapshot.between(counters(), counters(), second, 0, 0)

        assertEquals(0f, p.decodeAvgMs, 0f)
        assertEquals(0f, p.renderAvgMs, 0f)
        assertEquals(0L, p.copiedPixelsPerRender)
        assertEquals(0f, p.updatesPerSecond, 0f)
        assertTrue(p.updatesPerSecond.isFinite() && p.renderAvgMs.isFinite() && p.cpuPercent >= 0)
    }

    @Test
    fun `cpu is the thread cpu time over the elapsed time, as a percentage of one core, clamped`() {
        assertEquals(45, PerfSnapshot.between(counters(), counters(cpu = 450_000_000), second, 0, 0).cpuPercent)
        assertEquals(0, PerfSnapshot.between(counters(), counters(), second, 0, 0).cpuPercent)
        assertEquals(100, PerfSnapshot.between(counters(), counters(cpu = 5 * second), second, 0, 0).cpuPercent)
    }

    @Test
    fun `full screen copies and full redraws are counted over the interval only`() {
        val a = counters(full = 3, redraws = 1)
        val b = counters(full = 8, redraws = 2)

        val p = PerfSnapshot.between(a, b, second, 0, 0)

        assertEquals(5L, p.fullScreenCopies)
        assertEquals(1L, p.fullRedraws)
    }

    @Test
    fun `a counter that went backwards after a reset counts as zero, not as a huge negative rate`() {
        val p = PerfSnapshot.between(counters(updates = 100, rx = 9_999), counters(updates = 3, rx = 5), second, 0, 0)

        assertEquals(0f, p.updatesPerSecond, 0f)
        assertEquals(0L, p.bytesReceivedPerSecond)
    }

    @Test
    fun `a null or negative interval gives the empty snapshot`() {
        assertEquals(PerfSnapshot.EMPTY, PerfSnapshot.between(counters(), counters(updates = 5), 0, 0, 0))
        assertEquals(PerfSnapshot.EMPTY, PerfSnapshot.between(counters(), counters(updates = 5), -second, 0, 0))
    }

    @Test
    fun `heap is reported in kibibytes`() {
        assertEquals(2_048L, PerfSnapshot.between(counters(), counters(), second, 0, 0, MemoryReading(heapUsedBytes = 2L * 1024 * 1024)).heapUsedKb)
    }

    @Test
    fun `the sampler sets a reference on its first call and then reports each interval`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)

        assertEquals(PerfSnapshot.EMPTY, sampler.sample(10 * second))
        repeat(30) { stats.onUpdate(1, 100, 1_000_000) }
        val first = sampler.sample(11 * second)
        repeat(10) { stats.onUpdate(1, 100, 1_000_000) }
        val second2 = sampler.sample(12 * second)

        assertEquals(30f, first.updatesPerSecond, 1e-4f)
        assertEquals(10f, second2.updatesPerSecond, 1e-4f)
    }

    @Test
    fun `the sampler takes and resets the maxima each time`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)
        sampler.sample(0)
        stats.onUpdate(1, 1, 8_000_000)

        assertEquals(8f, sampler.sample(second).decodeMaxMs, 1e-4f)
        assertEquals("plus rien d'anormal la seconde suivante", 0f, sampler.sample(2 * second).decodeMaxMs, 0f)
    }

    @Test
    fun `reset makes the next sample a fresh reference`() {
        val stats = PerfStats()
        stats.enabled = true
        val sampler = PerfSampler(stats)
        sampler.sample(0)
        stats.onUpdate(1, 1, 1)

        sampler.reset()

        assertEquals(PerfSnapshot.EMPTY, sampler.sample(second))
    }

    // ============================================================ allocations (SS-061)

    private fun mem(objects: Long = 0, bytes: Long = 0, ui: Long = 0, heap: Long = 0, native: Long = 0) =
        MemoryReading(heapUsedBytes = heap, nativeHeapBytes = native, allocObjects = objects, allocBytes = bytes, uiAllocObjects = ui)

    private fun withSession(objects: Long, bytes: Long, updates: Long = 0) =
        PerfCounters(updates = updates, sessionAllocObjects = objects, sessionAllocBytes = bytes)

    @Test
    fun `session thread allocations are per second and per update`() {
        val a = withSession(1_000, 50_000, updates = 10)
        val b = withSession(1_600, 90_000, updates = 40)

        val p = PerfSnapshot.between(a, b, 2 * second, 0, 0)

        assertEquals(300L, p.sessionAllocsPerSecond)
        assertEquals(20_000L, p.sessionAllocBytesPerSecond)
        assertEquals(20f, p.sessionAllocsPerUpdate, 1e-4f) // 600 objets pour 30 mises à jour
    }

    @Test
    fun `no update in the interval means no per update figure, not a division by zero`() {
        val p = PerfSnapshot.between(withSession(0, 0, updates = 5), withSession(80, 0, updates = 5), second, 0, 0)

        assertEquals(80L, p.sessionAllocsPerSecond)
        assertEquals(0f, p.sessionAllocsPerUpdate, 0f)
    }

    @Test
    fun `a new session thread starting again from zero counts as nothing for that interval`() {
        val p = PerfSnapshot.between(withSession(9_000, 900_000, updates = 3), withSession(20, 1_000, updates = 5), second, 0, 0)

        assertEquals(0L, p.sessionAllocsPerSecond)
        assertEquals(0L, p.sessionAllocBytesPerSecond)
    }

    @Test
    fun `process wide allocations and ui allocations are differences of two readings`() {
        val before = mem(objects = 10_000, bytes = 1_000_000, ui = 500)
        val after = mem(objects = 14_000, bytes = 1_600_000, ui = 620, heap = 8L * 1024 * 1024, native = 3L * 1024 * 1024)

        val p = PerfSnapshot.between(counters(), counters(), 2 * second, 0, 0, after, before)

        assertEquals(2_000L, p.allocsPerSecond)
        assertEquals(300_000L, p.allocBytesPerSecond)
        assertEquals(60L, p.uiAllocsPerSecond)
        assertEquals(8_192L, p.heapUsedKb)
        assertEquals(3_072L, p.nativeHeapKb)
    }

    @Test
    fun `without a reference reading the process figures are 0, never the raw cumulative`() {
        val p = PerfSnapshot.between(counters(), counters(), second, 0, 0, mem(objects = 5_000_000, ui = 9))

        assertEquals(0L, p.allocsPerSecond)
        assertEquals(0L, p.uiAllocsPerSecond)
    }

    @Test
    fun `the sampler takes the reference reading from the previous sample`() {
        val stats = PerfStats(object : ThreadMeter {
            var n = 0L
            override fun cpuNanos() = 0L
            override fun allocatedObjects() = 100L * ++n
            override fun allocatedBytes() = 0L
        }).also { it.enabled = true }
        val sampler = PerfSampler(stats)

        stats.recordSessionThread()
        assertEquals(PerfSnapshot.EMPTY, sampler.sample(0, mem(objects = 1_000)))
        stats.recordSessionThread()
        val p = sampler.sample(second, mem(objects = 1_450))

        assertEquals(450L, p.allocsPerSecond)
        assertEquals(100L, p.sessionAllocsPerSecond)
    }
}
