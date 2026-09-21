package fr.webinfoconcept.secondscreen.rfb.robustness

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.InitExchange
import fr.webinfoconcept.secondscreen.rfb.protocol.NoneSecurity
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityNegotiation
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessage
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.testutil.allocatedBytesOfCurrentThread
import fr.webinfoconcept.secondscreen.rfb.testutil.memoryClient
import fr.webinfoconcept.secondscreen.rfb.testutil.rectHeader
import fr.webinfoconcept.secondscreen.rfb.testutil.u16
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.testutil.updateHeader
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * SS-070 : chaque longueur, taille ou compte annoncé par le serveur, aux valeurs limites d'un U8, U16, U32 et d'un Int signé.
 * Une longueur trop grande est refusée **avant** toute allocation ou lecture proportionnelle (mesuré : moins de 64 Kio alloués,
 * trace d'appels de l'exception comprise, pour un texte « de 4 Gio »), et une valeur qui serait négative une fois lue comme un Int (`0x80000000`, `0xFFFFFFFF`) est
 * refusée comme une grande valeur, jamais prise pour un petit nombre ni pour une longueur négative.
 */
class LengthFieldsTest {

    /** Les U32 qui piègent : plus petite/grande valeur valide, juste au-dessus, le bit de signe, le maximum. */
    private fun u32Boundaries(limit: Long) =
        longArrayOf(0, 1, limit - 1, limit, limit + 1, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFEL, 0xFFFFFFFFL).filter { it >= 0 }.distinct()

    /**
     * Les octets alloués par le thread pendant [block], **moins le coût de la mesure elle-même** : lire le compteur passe par de
     * la réflexion, qui alloue (~33 Kio mesurés, constants). Ce coût est mesuré à vide, médiane de 9 lectures.
     */
    private fun allocatedDuring(block: () -> Unit): Long {
        val before = allocatedBytesOfCurrentThread()!!
        block()
        return maxOf(0L, allocatedBytesOfCurrentThread()!! - before - measurementOverhead)
    }

    private val measurementOverhead: Long by lazy {
        (0 until 9).map {
            val before = allocatedBytesOfCurrentThread()!!
            allocatedBytesOfCurrentThread()!! - before
        }.sorted()[4]
    }

    // ---------------------------------------------------------------- nom du bureau (ServerInit)

    @Test
    fun `desktop name length - up to 1024 is read, above is refused before any allocation`() {
        assumeTrue(allocatedBytesOfCurrentThread() != null)
        for (announced in u32Boundaries(InitExchange.MAX_NAME_LENGTH.toLong())) {
            val available = ByteArray(minOf(announced, 2_000L).toInt()) { 'a'.code.toByte() }
            val client = memoryClient(serverInit(64, 48, announced, available))
            var failure: Throwable? = null
            var result: Any? = null
            val allocated = allocatedDuring {
                failure = typedFailure({ "nom de $announced octets" }) { result = InitExchange.perform(client) }
            }
            if (announced <= InitExchange.MAX_NAME_LENGTH) {
                assertTrue("annoncé $announced : $failure", failure == null)
                assertEquals(announced.toInt(), (result as fr.webinfoconcept.secondscreen.rfb.protocol.ServerInit).desktopName.length)
            } else {
                assertTrue("annoncé $announced : attendu InvalidDesktopName, obtenu $failure", failure is RfbProtocolException.InvalidDesktopName)
                assertEquals(announced, (failure as RfbProtocolException.InvalidDesktopName).announcedLength)
            }
            assertTrue("annoncé $announced : $allocated octets alloués", allocated < ALLOCATION_BOUND)
        }
    }

    @Test
    fun `a desktop name announced but not sent is an end of stream, not a hang`() {
        val e = typedFailure({ "nom coupé" }) { InitExchange.perform(memoryClient(serverInit(64, 48, 100, ByteArray(10)))) }
        assertTrue("$e", e is RfbTransportException.EndOfStream)
    }

    // ---------------------------------------------------------------- taille de l'écran (ServerInit)

    @Test
    fun `framebuffer size - exactly the inside of 1 to 4096 per axis and 1920 x 1200 pixels is accepted`() {
        val sizes = intArrayOf(0, 1, 2, 1199, 1200, 1201, 1919, 1920, 1921, 2304, 4095, 4096, 4097, 32768, 65535)
        for (w in sizes) for (h in sizes) {
            val expected = w in 1..4096 && h in 1..4096 && w.toLong() * h <= 1920L * 1200L // oracle indépendant
            val e = typedFailure({ "écran ${w}x$h" }) { InitExchange.perform(memoryClient(serverInit(w, h, 0))) }
            if (expected) assertTrue("${w}x$h devrait être accepté : $e", e == null)
            else assertTrue("${w}x$h devrait être refusé (InvalidFramebufferSize) : $e", e is RfbProtocolException.InvalidFramebufferSize)
        }
    }

    @Test
    fun `an accepted size can always be allocated, the product never overflows`() {
        // 65535 x 65535 = 4 294 836 225 dépasse Int.MAX_VALUE : refusé, jamais interprété comme un produit négatif.
        val e = typedFailure({ "65535x65535" }) { InitExchange.perform(memoryClient(serverInit(65535, 65535, 0))) }
        assertTrue("$e", e is RfbProtocolException.InvalidFramebufferSize)
        val ok = InitExchange.perform(memoryClient(serverInit(1920, 1200, 0)))
        Framebuffer(ok.width, ok.height) // ne lève pas
    }

    // ---------------------------------------------------------------- raison d'un refus (SecurityNegotiation)

    @Test
    fun `a rejection reason is truncated at its limit before allocation, whatever the announced length`() {
        assumeTrue(allocatedBytesOfCurrentThread() != null)
        for (announced in u32Boundaries(SecurityNegotiation.MAX_REASON_LENGTH.toLong())) {
            val text = ByteArray(minOf(announced, 5_000L).toInt()) { 'x'.code.toByte() }
            // 3.8 : zéro type de sécurité, puis la raison.
            val stream = byteArrayOf(0) + u32(announced) + text
            val client = memoryClient(stream)
            var e: Throwable? = null
            val allocated = allocatedDuring {
                e = typedFailure({ "raison de $announced octets" }) { SecurityNegotiation.negotiate(client, RfbVersion.V3_8, listOf(NoneSecurity)) }
            }
            assertTrue("annoncé $announced : attendu ConnectionRejected, obtenu $e", e is RfbProtocolException.ConnectionRejected)
            val reason = (e as RfbProtocolException.ConnectionRejected).reason
            assertTrue("annoncé $announced : raison de ${reason.length} caractères", reason.length <= SecurityNegotiation.MAX_REASON_LENGTH)
            assertTrue("annoncé $announced : $allocated octets alloués", allocated < ALLOCATION_BOUND)
        }
    }

    @Test
    fun `the security type count and the 3_3 type are read as unsigned values`() {
        // 3.3 : U32 = 0xFFFFFFFF ne doit pas devenir -1 ni « invalide » (0) ; 0x80000000 non plus.
        for (type in longArrayOf(3, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL)) {
            val e = typedFailure({ "type 3.3 = $type" }) { SecurityNegotiation.negotiate(memoryClient(u32(type)), RfbVersion.V3_3, listOf(NoneSecurity)) }
            assertTrue("$type : $e", e is RfbProtocolException.NoSupportedSecurityType)
            assertEquals(listOf(type), (e as RfbProtocolException.NoSupportedSecurityType).offered)
        }
        // 3.8 : 255 types annoncés (le maximum d'un U8), dont aucun connu.
        val e = typedFailure({ "255 types" }) {
            SecurityNegotiation.negotiate(memoryClient(byteArrayOf(255.toByte()) + ByteArray(255) { 3 }), RfbVersion.V3_8, listOf(NoneSecurity))
        }
        assertTrue("$e", e is RfbProtocolException.NoSupportedSecurityType)
    }

    // ---------------------------------------------------------------- texte du presse-papiers (ServerCutText)


    @Test
    fun `cut text - up to 1 MiB is skipped, above is refused before reading it`() {
        assumeTrue(allocatedBytesOfCurrentThread() != null)
        val limit = ServerMessageReader.MAX_CUT_TEXT_LENGTH
        for (announced in u32Boundaries(limit)) {
            val body = ByteArray(if (announced <= limit) announced.toInt() else 16) // au-dessus de la limite : presque rien à lire
            val reader = reader(byteArrayOf(3, 0, 0, 0) + u32(announced) + body)
            var message: Any? = null
            var failure: Throwable? = null
            val allocated = allocatedDuring {
                failure = typedFailure({ "texte de $announced octets" }) { message = reader.readMessage() }
            }
            if (announced <= limit) {
                assertTrue("annoncé $announced : $failure", failure == null)
                assertEquals(announced.toInt(), (message as ServerMessage.CutTextIgnored).length)
            } else {
                assertTrue("annoncé $announced : attendu CutTextTooLong, obtenu $failure", failure is RfbProtocolException.CutTextTooLong)
                assertEquals(announced, (failure as RfbProtocolException.CutTextTooLong).announcedLength)
            }
            assertTrue("annoncé $announced : $allocated octets alloués", allocated < ALLOCATION_BOUND)
        }
    }

    @Test
    fun `a cut text announced but not sent is an end of stream`() {
        val e = typedFailure({ "texte coupé" }) { reader(byteArrayOf(3, 0, 0, 0) + u32(5_000) + ByteArray(10)).readMessage() }
        assertTrue("$e", e is RfbTransportException.EndOfStream)
    }

    // ---------------------------------------------------------------- type de message et d'encodage

    @Test
    fun `all 256 message types - only framebuffer update, bell and cut text are accepted, the rest is a typed error`() {
        for (type in 0..255) {
            val stream = byteArrayOf(type.toByte()) + updateHeader(0).copyOfRange(1, 4) + ByteArray(16)
            val e = typedFailure({ "type de message $type" }) { reader(stream).readMessage() }
            when (type) {
                0, 2 -> assertTrue("type $type : $e", e == null)
                3 -> assertTrue("type 3 : $e", e is RfbTransportException.EndOfStream || e is RfbProtocolException.CutTextTooLong || e == null)
                else -> {
                    assertTrue("type $type : attendu UnsupportedServerMessage, obtenu $e", e is RfbProtocolException.UnsupportedServerMessage)
                    assertEquals(type, (e as RfbProtocolException.UnsupportedServerMessage).type)
                }
            }
        }
    }

    @Test
    fun `encodings the client never advertised are refused, including every pseudo encoding and the sign bit`() {
        val unadvertised = intArrayOf(2, 3, 4, 6, 7, 16, 255, 256, -1, -223, -224, -239, -240, -256, 0x7FFFFFFF, Int.MIN_VALUE)
        for (encoding in unadvertised) {
            val fb = patterned(64, 48)
            val before = fb.pixels.copyOf()
            val stream = updateHeader(1) + rectHeader(0, 0, 4, 4, encoding) + ByteArray(64)
            val e = typedFailure({ "encodage $encoding" }) { ServerMessageReader(memoryClient(stream), fb, PixelFormat.XRGB_8888_LE).readMessage() }
            assertTrue("encodage $encoding : $e", e is RfbProtocolException.UnsupportedEncoding)
            assertEquals(encoding, (e as RfbProtocolException.UnsupportedEncoding).encoding)
            assertTrue("encodage $encoding : aucun pixel ne doit changer", before.contentEquals(fb.pixels))
        }
    }

    @Test
    fun `the rectangle count of an update is bounded by what the stream can carry, never trusted`() {
        // 65535 rectangles annoncés, un seul envoyé : la lecture s'arrête à la fin du flux, sans allocation en fonction du nombre.
        assumeTrue(allocatedBytesOfCurrentThread() != null)
        val stream = updateHeader(65535) + rectHeader(0, 0, 1, 1, 0) + ByteArray(4)
        val reader = reader(stream)
        var e: Throwable? = null
        val allocated = allocatedDuring { e = typedFailure({ "65535 rectangles annoncés" }) { reader.readMessage() } }
        assertTrue("$e", e is RfbTransportException.EndOfStream)
        assertTrue("$allocated octets alloués", allocated < ALLOCATION_BOUND)
    }

    companion object {
        /**
         * Allocation tolérée pour refuser une longueur hostile : **la trace d'appels de l'exception** (~19 Kio mesurés sous
         * Gradle, dont la pile est profonde ; bien moins sur l'appareil), pas un tampon. Quatre ordres de grandeur sous une
         * longueur annoncée de 1 Mio, six sous 4 Gio : une allocation proportionnelle à la longueur annoncée est impossible à manquer.
         */
        private const val ALLOCATION_BOUND = 64 * 1024

        /**
         * La toute première exécution d'un chemin charge des classes et alloue ~85 Kio de plus que le code lui-même : on la
         * fait ici, avant les tests qui mesurent des allocations, pour ne mesurer que le code.
         */
        @BeforeClass
        @JvmStatic
        fun warmUp() {
            repeat(3) {
                typedFailure({ "" }) { InitExchange.perform(memoryClient(serverInit(64, 48, 10, ByteArray(10)))) }
                typedFailure({ "" }) { InitExchange.perform(memoryClient(serverInit(64, 48, 5_000, ByteArray(10)))) }
                typedFailure({ "" }) { SecurityNegotiation.negotiate(memoryClient(byteArrayOf(0) + u32(5_000) + ByteArray(300)), RfbVersion.V3_8, listOf(NoneSecurity)) }
                typedFailure({ "" }) { reader(byteArrayOf(3, 0, 0, 0) + u32(10) + ByteArray(10)).readMessage() }
                typedFailure({ "" }) { reader(byteArrayOf(3, 0, 0, 0) + u32(0xFFFFFFFFL) + ByteArray(4)).readMessage() }
                typedFailure({ "" }) { reader(updateHeader(65535) + rectHeader(0, 0, 1, 1, 0) + ByteArray(4)).readMessage() }
            }
        }
    }
}

private fun serverInit(width: Int, height: Int, nameLength: Long, name: ByteArray = ByteArray(0)): ByteArray {
    val pixelFormat = byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0)
    return u16(width) + u16(height) + pixelFormat + u32(nameLength) + name
}

private fun reader(stream: ByteArray) = ServerMessageReader(memoryClient(stream), Framebuffer(64, 48), PixelFormat.XRGB_8888_LE)
