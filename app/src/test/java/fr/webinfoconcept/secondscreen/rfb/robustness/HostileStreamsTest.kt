package fr.webinfoconcept.secondscreen.rfb.robustness

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.InitExchange
import fr.webinfoconcept.secondscreen.rfb.protocol.NoneSecurity
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.ProtocolVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityNegotiation
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.protocol.VncAuthentication
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfCurrentThread
import fr.webinfoconcept.secondscreen.rfb.testutil.memoryClient
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.u16
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.testutil.updateHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * SS-070 : des flux hostiles de toutes les formes, à graine fixe (les échecs se rejouent). Pour chacun : **aucune exception
 * autre qu'une erreur typée** (IOException), la lecture **termine** (chaque message consomme au moins un octet, donc au plus
 * autant de messages que d'octets), les **pixels hors du rectangle annoncé ne changent jamais**, et l'allocation reste bornée.
 */
class HostileStreamsTest {

    private val xrgb = PixelFormat.XRGB_8888_LE

    /** Lit des messages jusqu'à la première erreur typée ; renvoie le nombre de messages lus. */
    private fun drain(stream: ByteArray, fb: Framebuffer, what: String): Int {
        val reader = ServerMessageReader(memoryClient(stream), fb, xrgb)
        var messages = 0
        while (true) {
            val failure = typedFailure({ "$what : ${hex(stream)}" }) { reader.readMessage() }
            if (failure != null) return messages
            messages++
            assertTrue("$what : plus de messages (${messages}) que d'octets (${stream.size}) : ${hex(stream)}", messages <= stream.size)
        }
    }

    // ================================================================== bannière de version

    @Test(timeout = 60_000)
    fun `protocol version - every possible byte at every position of the banner`() {
        val valid = "RFB 003.008\n".toByteArray(Charsets.US_ASCII)
        for (at in valid.indices) for (b in 0..255) {
            val banner = valid.copyOf().also { it[at] = b.toByte() }
            typedFailure({ "bannière ${hex(banner)}" }) { ProtocolVersion.negotiate(memoryClient(banner)) }
        }
    }

    @Test(timeout = 60_000)
    fun `protocol version - random banners`() {
        val random = Random(70)
        repeat(3_000) {
            val banner = randomBytes(random, 12)
            typedFailure({ "bannière ${hex(banner)}" }) { ProtocolVersion.negotiate(memoryClient(banner)) }
        }
    }

    // ================================================================== ServerInit

    private fun validServerInit(name: String = "bureau") =
        u16(64) + u16(48) + byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0) +
            u32(name.length.toLong()) + name.toByteArray()

    @Test(timeout = 60_000)
    fun `server init - every possible byte at every position of the header`() {
        val valid = validServerInit()
        for (at in 0 until 24) for (b in 0..255) {
            val stream = valid.copyOf().also { it[at] = b.toByte() }
            typedFailure({ "ServerInit ${hex(stream)}" }) { InitExchange.perform(memoryClient(stream)) }
        }
    }

    @Test(timeout = 60_000)
    fun `server init - a desktop name of any bytes is sanitised, whatever it contains`() {
        val random = Random(71)
        repeat(1_000) {
            val length = random.nextInt(200)
            val name = randomBytes(random, length)
            val init = InitExchange.perform(memoryClient(validServerInit("").copyOfRange(0, 20) + u32(length.toLong()) + name))
            assertEquals(length, init.desktopName.length)
            assertTrue("caractère non imprimable dans « ${init.desktopName} »", init.desktopName.all { it.code in 0x20..0x7E })
        }
    }

    @Test(timeout = 60_000)
    fun `server init - random and mutated streams`() {
        val random = Random(72)
        repeat(3_000) { i ->
            val stream = if (i % 2 == 0) randomBytes(random, random.nextInt(80)) else mutate(random, validServerInit())
            typedFailure({ "ServerInit ${hex(stream)}" }) { InitExchange.perform(memoryClient(stream)) }
        }
    }

    // ================================================================== négociation de sécurité

    private fun securityFlows(random: Random): List<Pair<RfbVersion, ByteArray>> {
        val challenge = randomBytes(random, 16)
        val reason = "refusé".toByteArray()
        return listOf(
            RfbVersion.V3_8 to byteArrayOf(1, 1) + u32(0),                                          // None, accepté
            RfbVersion.V3_8 to byteArrayOf(2, 1, 2) + u32(0),                                       // deux types
            RfbVersion.V3_8 to byteArrayOf(1, 2) + challenge + u32(0),                              // VNC auth
            RfbVersion.V3_8 to byteArrayOf(1, 2) + challenge + u32(1) + u32(reason.size.toLong()) + reason,
            RfbVersion.V3_8 to byteArrayOf(0) + u32(reason.size.toLong()) + reason,                 // refus
            RfbVersion.V3_3 to u32(1),                                                              // None
            RfbVersion.V3_3 to u32(2) + challenge + u32(0),                                         // VNC auth
            RfbVersion.V3_3 to u32(0) + u32(reason.size.toLong()) + reason                          // refus
        )
    }

    @Test(timeout = 120_000)
    fun `security negotiation - random and mutated flows, both versions`() {
        val random = Random(73)
        val flows = securityFlows(random)
        repeat(3_000) { i ->
            val (version, valid) = flows[i % flows.size]
            val stream = if (i % 3 == 0) randomBytes(random, random.nextInt(40)) else mutate(random, valid)
            val vnc = VncAuthentication("secret".toCharArray())
            try {
                typedFailure({ "sécurité $version ${hex(stream)}" }) {
                    SecurityNegotiation.negotiate(memoryClient(stream), version, listOf(NoneSecurity, vnc))
                }
            } finally {
                vnc.close()
            }
        }
    }

    // ================================================================== messages serveur

    @Test(timeout = 120_000)
    fun `server messages - mutated valid streams`() {
        assumeTrue(allocatedBytesOfCurrentThread() != null)
        val random = Random(74)
        val valid = validServerStream(random)
        // Le flux valide lui-même passe en entier : sans cela les mutations ne prouveraient rien.
        assertEquals(4, drain(valid, patterned(64, 48), "flux valide"))
        repeat(20) { drain(mutate(random, valid), patterned(64, 48), "échauffement") }

        var worst = 0L
        repeat(4_000) {
            val stream = mutate(random, valid)
            val fb = patterned(64, 48)
            val reader = ServerMessageReader(memoryClient(stream), fb, xrgb)
            val before = allocatedBytesOfCurrentThread()!!
            var messages = 0
            while (typedFailure({ "flux muté ${hex(stream)}" }) { reader.readMessage() } == null) {
                messages++
                assertTrue("boucle sans fin : ${hex(stream)}", messages <= stream.size)
            }
            worst = maxOf(worst, allocatedBytesOfCurrentThread()!! - before)
        }
        // Chaque message alloue son seul objet résultat ; une erreur, sa trace d'appels (~20 Kio sous Gradle).
        assertTrue("allocation maximale sur un flux de ~1,6 Kio : $worst octets", worst < 256 * 1024)
    }

    @Test(timeout = 120_000)
    fun `server messages - random bytes`() {
        val random = Random(75)
        repeat(4_000) {
            val stream = randomBytes(random, random.nextInt(200))
            drain(stream, patterned(64, 48), "octets aléatoires")
        }
    }

    @Test(timeout = 120_000)
    fun `server messages - a valid stream cut at every possible position ends with a typed error`() {
        val valid = validServerStream(Random(76))
        for (cut in 0..valid.size) {
            val messages = drain(valid.copyOf(cut), patterned(64, 48), "flux coupé à $cut")
            assertTrue("coupé à $cut : $messages messages", messages <= 4)
        }
    }

    // ================================================================== rien hors du rectangle annoncé

    @Test(timeout = 120_000)
    fun `hostile tile data never changes a pixel outside the announced rectangle`() {
        val random = Random(77)
        repeat(6_000) { i ->
            val encoding = intArrayOf(0, 1, 5)[i % 3]
            val x = random.nextInt(64)
            val y = random.nextInt(48)
            val w = random.nextInt(64 - x + 1)
            val h = random.nextInt(48 - y + 1)
            val fb = patterned(64, 48)
            val before = fb.pixels.copyOf()
            val payload = randomBytes(random, random.nextInt(if (encoding == 5) 4_000 else 1_200))
            val stream = updateHeader(1) + rectHeader(x, y, w, h, encoding) + payload

            drain(stream, fb, "encodage $encoding x=$x y=$y w=$w h=$h")

            for (py in 0 until 48) for (px in 0 until 64) {
                val inside = px >= x && px < x + w && py >= y && py < y + h
                // Le message n'est construit qu'en cas d'échec : 3 072 pixels x 6 000 cas.
                if (!inside && before[py * 64 + px] != fb.pixels[py * 64 + px]) {
                    fail("encodage $encoding rect ($x,$y,$w,$h) : pixel ($px,$py) modifié hors du rectangle : ${hex(stream)}")
                }
            }
        }
    }
}
