package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Lecture big-endian non signée des primitifs RFB (fixtures déterministes). */
class RfbReaderTest {

    @Test(timeout = 10_000)
    fun `readU8 is unsigned`() = LoopbackPair().use { p ->
        p.send(0x00, 0x7F, 0x80, 0xFF)
        val r = RfbReader(p.client)

        assertEquals(0, r.readU8())
        assertEquals(127, r.readU8())
        assertEquals(128, r.readU8())
        assertEquals(255, r.readU8())
    }

    @Test(timeout = 10_000)
    fun `readU32 is big endian and unsigned`() = LoopbackPair().use { p ->
        p.send(
            0x00, 0x00, 0x00, 0x01,   // 1
            0x12, 0x34, 0x56, 0x78,   // 0x12345678 (l'ordre des octets compte)
            0x80, 0x00, 0x00, 0x00,   // 2^31 : négatif si lu en Int signé
            0xFF, 0xFF, 0xFF, 0xFF    // 2^32 - 1
        )
        val r = RfbReader(p.client)

        assertEquals(1L, r.readU32())
        assertEquals(0x12345678L, r.readU32())
        assertEquals(2147483648L, r.readU32())
        assertEquals(4294967295L, r.readU32())
    }

    @Test(timeout = 10_000)
    fun `readU32 reassembles a fragmented value`() = LoopbackPair().use { p ->
        val writer = Thread { p.sendFragmented(u32(0xCAFEBABEL), 1) }
        writer.start()

        assertEquals(0xCAFEBABEL, RfbReader(p.client).readU32())
        writer.join()
    }

    @Test(timeout = 10_000)
    fun `readU32 on a truncated value is an end of stream`() = LoopbackPair().use { p ->
        p.send(0x00, 0x01)
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { RfbReader(p.client).readU32() }
        assertEquals(2, e.bytesRead)
    }

    @Test(timeout = 10_000)
    fun `readBytes returns exactly the requested bytes`() = LoopbackPair().use { p ->
        p.send(1, 2, 3, 4, 5)
        val r = RfbReader(p.client)

        assertArrayEquals(byteArrayOf(1, 2, 3), r.readBytes(3))
        assertArrayEquals(byteArrayOf(), r.readBytes(0))
        assertArrayEquals(byteArrayOf(4, 5), r.readBytes(2))
    }

    @Test(timeout = 10_000)
    fun `readBytes rejects a negative length`() {
        LoopbackPair().use { p ->
            assertThrows(IllegalArgumentException::class.java) { RfbReader(p.client).readBytes(-1) }
        }
    }
}
