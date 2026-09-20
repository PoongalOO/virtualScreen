package fr.webinfoconcept.secondscreen.perf

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopPixel
import fr.webinfoconcept.secondscreen.rfb.testutil.desktopRect
import fr.webinfoconcept.secondscreen.rfb.testutil.framebufferUpdate
import fr.webinfoconcept.secondscreen.rfb.testutil.rawRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Points de mesure de la socket et du lecteur de messages (SS-060). */
class PerfHooksTest {

    private fun twoRects() = framebufferUpdate(
        rawRect(0, 0, 4, 3, desktopRect(0, 0, 4, 3)),
        rawRect(10, 5, 6, 2, desktopRect(10, 5, 6, 2))
    )

    @Test(timeout = 10_000)
    fun `an update is counted with its rectangles and its pixels when measures are on`() = LoopbackPair().use { p ->
        val stats = PerfStats().also { it.enabled = true }
        val fb = Framebuffer(20, 10)
        p.send(twoRects())

        ServerMessageReader(p.client, fb, perf = stats).readMessage()

        val c = stats.counters()
        assertEquals(1, c.updates)
        assertEquals(2, c.rectangles)
        assertEquals(4L * 3 + 6L * 2, c.decodedPixels)
        assertTrue("durée mesurée", c.decodeNs > 0)
    }

    @Test(timeout = 10_000)
    fun `nothing is counted when measures are off, and the picture is decoded just the same`() = LoopbackPair().use { p ->
        val stats = PerfStats()
        val fb = Framebuffer(20, 10)
        p.send(twoRects())

        ServerMessageReader(p.client, fb, perf = stats).readMessage()

        assertEquals(PerfCounters(), stats.counters())
        assertEquals(desktopPixel(1, 2), fb.getPixel(1, 2))
        assertEquals(desktopPixel(12, 6), fb.getPixel(12, 6))
    }

    @Test(timeout = 10_000)
    fun `bytes received and sent by the socket are counted, only while enabled`() = LoopbackPair().use { p ->
        val stats = PerfStats()
        p.client.traffic = stats.traffic
        val payload = ByteArray(10_000) { it.toByte() }

        // désactivé : rien
        p.send(payload); p.client.readFully(ByteArray(10_000), 0, 10_000); p.client.write(payload, 0, 300)
        assertEquals(0L, stats.counters().bytesReceived)
        assertEquals(0L, stats.counters().bytesSent)

        // activé
        stats.enabled = true
        p.send(payload); p.client.readFully(ByteArray(10_000), 0, 10_000); p.client.write(payload, 0, 300)
        p.receiveExactly(600) // le pair vide ce qui a été écrit
        assertEquals(10_000L, stats.counters().bytesReceived)
        assertEquals(300L, stats.counters().bytesSent)
    }

    @Test(timeout = 10_000)
    fun `a socket with no counter works as before`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(1, 2, 3))

        val out = ByteArray(3)
        p.client.readFully(out, 0, 3)

        assertEquals(listOf<Byte>(1, 2, 3), out.toList())
    }
}
