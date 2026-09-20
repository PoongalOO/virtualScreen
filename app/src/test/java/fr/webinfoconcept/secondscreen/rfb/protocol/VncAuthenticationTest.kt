package fr.webinfoconcept.secondscreen.rfb.protocol

import fr.webinfoconcept.secondscreen.rfb.testutil.LoopbackPair
import fr.webinfoconcept.secondscreen.rfb.testutil.reasonString
import fr.webinfoconcept.secondscreen.rfb.testutil.u32
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC Authentication : dérivation de clé, chiffrement DES, flux complet valide/invalide, et
 * hygiène du secret. Les vecteurs viennent d'OpenSSL (`openssl enc -des-ecb`), donc
 * indépendants du code testé.
 */
class VncAuthenticationTest {

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { String.format("%02x", it) }
    private fun chars(s: String) = s.toCharArray()

    /** Vecteurs : mot de passe, clé DES attendue, challenge, réponse attendue. */
    private class Vector(val password: String, val key: String, val challenge: String, val response: String)

    private val vectors = listOf(
        Vector("password", "0e86ceceeef64e26", "000102030405060708090a0b0c0d0e0f", "b866924125c8eebb9debc1db61c538e2"),
        Vector("abc", "8646c60000000000", "101112131415161718191a1b1c1d1e1f", "5d30c09d600699fa6c8f4ee5f8fbf50b"),
        Vector("abcdefgh", "8646c626a666e616", "ffffffffffffffff8081828384858687", "e1a6a1ba09cafa33a3136ccb860358c6"),
        // 10 caractères : seuls les 8 premiers comptent, même clé et même réponse que "abcdefgh".
        Vector("abcdefghij", "8646c626a666e616", "ffffffffffffffff8081828384858687", "e1a6a1ba09cafa33a3136ccb860358c6"),
        Vector("café", "c686669700000000", "000102030405060708090a0b0c0d0e0f", "392a24157ee99595959ffbf8c551be82"),
        // Challenge de zéros : deux blocs identiques donnent deux blocs chiffrés identiques (ECB).
        Vector("A", "8200000000000000", "00000000000000000000000000000000", "0ab3cce9c90d1ad40ab3cce9c90d1ad4")
    )

    // ---------------------------------------------------------- deriveKey

    @Test
    fun `derives the bit reversed zero padded key`() {
        for (v in vectors) {
            assertEquals(v.password, v.key, VncAuthentication.deriveKey(chars(v.password)).toHex())
        }
    }

    @Test
    fun `every Latin-1 character is bit reversed correctly`() {
        for (c in 0..0xFF) {
            val expected = Integer.parseInt(String.format("%8s", Integer.toBinaryString(c)).replace(' ', '0').reversed(), 2)
            assertEquals("U+%04X".format(c), expected, VncAuthentication.deriveKey(charArrayOf(c.toChar()))[0].toInt() and 0xFF)
        }
    }

    @Test
    fun `bit reversal spot checks`() {
        fun first(c: Char) = VncAuthentication.deriveKey(charArrayOf(c))[0].toInt() and 0xFF
        assertEquals(0x80, first('\u0001'))
        assertEquals(0x01, first('\u0080'))
        assertEquals(0xFF, first('ÿ'))
        assertEquals(0x0F, first('ð'))
        assertEquals(0x86, first('a'))   // 01100001 -> 10000110
        assertEquals(0x97, first('é')) // é : 11101001 -> 10010111
    }

    @Test
    fun `password is padded with zeros and truncated to 8 characters`() {
        assertArrayEquals(hex("8646c60000000000"), VncAuthentication.deriveKey(chars("abc")))
        assertEquals(8, VncAuthentication.deriveKey(chars("abcdefghijklmnop")).size)
        assertArrayEquals(
            VncAuthentication.deriveKey(chars("abcdefgh")),
            VncAuthentication.deriveKey(chars("abcdefghZZZZ"))
        )
    }

    @Test
    fun `rejects an empty password`() {
        assertThrows(IllegalArgumentException::class.java) { VncAuthentication.deriveKey(CharArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { VncAuthentication(CharArray(0)) }
    }

    @Test
    fun `rejects characters outside Latin-1 without revealing them`() {
        for (bad in listOf("€", "Ā", "中", "ok€ok")) {
            val e = assertThrows(IllegalArgumentException::class.java) { VncAuthentication.deriveKey(chars(bad)) }
            assertFalse(e.message!!.contains("€") || e.message!!.contains("ok") || e.message!!.contains("中"))
        }
    }

    @Test
    fun `a rejected password is still wiped`() {
        val pw = chars("ok€")
        assertThrows(IllegalArgumentException::class.java) { VncAuthentication(pw) }
        assertTrue(pw.all { it == '\u0000' })
    }

    @Test
    fun `characters after the eighth are not validated`() {
        // Ignorés par le protocole : un € en 9e position n'empêche pas de se connecter.
        assertArrayEquals(
            VncAuthentication.deriveKey(chars("abcdefgh")),
            VncAuthentication.deriveKey(chars("abcdefgh€"))
        )
    }

    // ---------------------------------------------------- encryptChallenge

    @Test
    fun `encrypts challenges like OpenSSL DES ECB`() {
        for (v in vectors) {
            val response = VncAuthentication.encryptChallenge(hex(v.key), hex(v.challenge))
            assertEquals(v.password, v.response, response.toHex())
        }
    }

    @Test
    fun `matches the classic DES known answer test`() {
        // Clé 133457799BBCDFF1, clair 0123456789ABCDEF -> 85E813540F0AB405 (deux blocs identiques).
        val challenge = hex("0123456789abcdef0123456789abcdef")

        assertEquals(
            "85e813540f0ab40585e813540f0ab405",
            VncAuthentication.encryptChallenge(hex("133457799bbcdff1"), challenge).toHex()
        )
    }

    @Test
    fun `the two challenge blocks are encrypted independently`() {
        val a = hex("0011223344556677")
        val b = hex("8899aabbccddeeff")
        val key = hex("0e86ceceeef64e26")

        val whole = VncAuthentication.encryptChallenge(key, a + b)
        val first = VncAuthentication.encryptChallenge(key, a + a).copyOfRange(0, 8)
        val second = VncAuthentication.encryptChallenge(key, b + b).copyOfRange(0, 8)

        assertArrayEquals(first + second, whole) // pas de chaînage entre blocs
    }

    // ---------------------------------------------------- flux complet

    /** Référence de test : dérivation et DES réécrits ici, indépendamment du code testé. */
    private fun referenceResponse(password: String, challenge: ByteArray): ByteArray {
        val raw = password.toByteArray(Charsets.ISO_8859_1).copyOf(8) // tronque ou complète de zéros
        val key = ByteArray(8) { (Integer.reverse(raw[it].toInt() and 0xFF) ushr 24).toByte() }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    private val challenge = ByteArray(16) { (it * 7 + 3).toByte() }

    /**
     * Joue le serveur : propose VNC Auth, envoie [challenge], compare la réponse à celle que donnerait
     * [serverPassword], répond par un SecurityResult. Renvoie la réponse reçue du client.
     */
    private fun runVncServer(
        p: LoopbackPair,
        version: RfbVersion,
        serverPassword: String,
        received: AtomicReference<ByteArray>,
        offered: List<Int> = listOf(2)
    ): Thread = Thread {
        if (version == RfbVersion.V3_8) {
            p.send(offered.size, *offered.toIntArray())
            check(p.receiveExactly(1)[0].toInt() == 2) { "le client n'a pas choisi le type 2" }
        } else {
            p.send(u32(2))                     // 3.3 : type imposé
        }
        p.send(challenge)
        val response = p.receiveExactly(16)
        received.set(response)
        if (response.contentEquals(referenceResponse(serverPassword, challenge))) {
            p.send(u32(0))
        } else {
            p.send(u32(1))
            if (version == RfbVersion.V3_8) p.send(reasonString("Authentication failed"))
        }
    }.also { it.start() }

    private fun negotiate(p: LoopbackPair, version: RfbVersion, password: String): SecurityHandler =
        SecurityNegotiation.negotiate(p.client, version, listOf(VncAuthentication(chars(password))))

    @Test(timeout = 10_000)
    fun `3_8 valid password is accepted`() = LoopbackPair().use { p ->
        val received = AtomicReference<ByteArray>()
        val server = runVncServer(p, RfbVersion.V3_8, "secret12", received)

        val handler = negotiate(p, RfbVersion.V3_8, "secret12")
        server.join()

        assertEquals(SecurityType.VNC_AUTH, handler.type)
        assertArrayEquals(referenceResponse("secret12", challenge), received.get())
        assertTrue(p.client.isConnected)
    }

    @Test(timeout = 10_000)
    fun `3_8 wrong password is reported as authentication failed`() = LoopbackPair().use { p ->
        val received = AtomicReference<ByteArray>()
        val server = runVncServer(p, RfbVersion.V3_8, "secret12", received)

        val e = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) {
            negotiate(p, RfbVersion.V3_8, "wrongpwd")
        }
        server.join()

        assertEquals("Authentication failed", e.reason)
        assertArrayEquals(referenceResponse("wrongpwd", challenge), received.get())
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `3_3 valid password is accepted`() = LoopbackPair().use { p ->
        val received = AtomicReference<ByteArray>()
        val server = runVncServer(p, RfbVersion.V3_3, "secret12", received)

        negotiate(p, RfbVersion.V3_3, "secret12")
        server.join()

        assertArrayEquals(referenceResponse("secret12", challenge), received.get())
    }

    @Test(timeout = 10_000)
    fun `3_3 wrong password is reported as authentication failed`() = LoopbackPair().use { p ->
        val server = runVncServer(p, RfbVersion.V3_3, "secret12", AtomicReference())

        val e = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) {
            negotiate(p, RfbVersion.V3_3, "nope")
        }
        server.join()

        assertEquals("", e.reason) // 3.3 : pas de raison
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `characters after the eighth are ignored like VNC servers do`() = LoopbackPair().use { p ->
        val server = runVncServer(p, RfbVersion.V3_8, "secret12", AtomicReference())

        negotiate(p, RfbVersion.V3_8, "secret12-and-more-typed-by-the-user")
        server.join()
    }

    @Test(timeout = 10_000)
    fun `only the first eight characters matter`() {
        val cases = listOf("secret12XX" to true, "secret1" to false, "Secret12" to false)
        for ((typed, ok) in cases) {
            LoopbackPair().use { p ->
                val server = runVncServer(p, RfbVersion.V3_8, "secret12", AtomicReference())
                if (ok) {
                    negotiate(p, RfbVersion.V3_8, typed)
                } else {
                    assertThrows("« $typed »", RfbProtocolException.AuthenticationFailed::class.java) {
                        negotiate(p, RfbVersion.V3_8, typed)
                    }
                }
                server.join()
            }
        }
    }

    @Test(timeout = 10_000)
    fun `client sends the choice byte then exactly 16 response bytes`() = LoopbackPair().use { p ->
        val handler = VncAuthentication(chars("secret12"))
        p.send(byteArrayOf(1, 2) + challenge + u32(0))

        SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, listOf(handler))
        p.client.close()

        val written = p.receiveUntilEof()
        assertEquals(1 + 16, written.size)
        assertEquals(2, written[0].toInt())
        assertArrayEquals(referenceResponse("secret12", challenge), written.copyOfRange(1, 17))
    }

    @Test(timeout = 20_000)
    fun `challenge survives TCP fragmentation`() {
        for (chunk in listOf(1, 3, 5)) {
            LoopbackPair().use { p ->
                val received = AtomicReference<ByteArray>()
                val stream = byteArrayOf(1, 2) + challenge + u32(0)
                val server = Thread {
                    p.sendFragmented(stream, chunk)
                    received.set(p.receiveExactly(1 + 16))
                }
                server.start()

                SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, listOf(VncAuthentication(chars("secret12"))))
                server.join()

                assertArrayEquals("fragments de $chunk", referenceResponse("secret12", challenge), received.get().copyOfRange(1, 17))
            }
        }
    }

    @Test(timeout = 10_000)
    fun `server closing in the middle of the challenge is an end of stream`() = LoopbackPair().use { p ->
        p.send(byteArrayOf(1, 2) + challenge.copyOfRange(0, 5))
        p.peer.close()

        val e = assertThrows(RfbTransportException.EndOfStream::class.java) { negotiate(p, RfbVersion.V3_8, "secret12") }

        assertEquals(5, e.bytesRead)
        assertEquals(16, e.bytesExpected)
        assertTrue(p.client.isClosed)
    }

    @Test(timeout = 10_000)
    fun `prefers VNC authentication over None when the server offers both`() = LoopbackPair().use { p ->
        // Le serveur liste None (1) avant VNC (2) ; il vérifie que le client a bien choisi 2.
        val server = runVncServer(p, RfbVersion.V3_8, "secret12", AtomicReference(), offered = listOf(1, 2))
        val handlers = listOf(VncAuthentication(chars("secret12")), NoneSecurity)

        val chosen = SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, handlers)
        server.join()

        assertEquals(SecurityType.VNC_AUTH, chosen.type)
    }

    // ------------------------------------------------ hygiène du secret

    @Test
    fun `constructor wipes the callers password array`() {
        val pw = chars("secret12")

        VncAuthentication(pw).close()

        assertTrue("le tableau de l'appelant doit être effacé", pw.all { it == '\u0000' })
    }

    @Test(timeout = 10_000)
    fun `key is wiped after authentication and the handler is single use`() {
        LoopbackPair().use { p ->
            val handler = VncAuthentication(chars("secret12"))
            val key = handler.keyReferenceForTest()!!
            assertFalse(key.all { it == 0.toByte() })
            p.send(byteArrayOf(1, 2) + challenge + u32(0))

            SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, listOf(handler))

            assertTrue("clé non effacée : ${key.toHex()}", key.all { it == 0.toByte() })
            assertNull(handler.keyReferenceForTest())
            assertThrows(IllegalStateException::class.java) { handler.authenticate(p.client) }
        }
    }

    @Test(timeout = 10_000)
    fun `key is wiped even when authentication fails on the network`() = LoopbackPair().use { p ->
        val handler = VncAuthentication(chars("secret12"))
        val key = handler.keyReferenceForTest()!!
        p.send(byteArrayOf(1, 2, 9, 9, 9)) // challenge tronqué puis fermeture
        p.peer.close()

        assertThrows(RfbTransportException.EndOfStream::class.java) {
            SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, listOf(handler))
        }

        assertTrue(key.all { it == 0.toByte() })
        assertNull(handler.keyReferenceForTest())
    }

    @Test
    fun `close wipes an unused key and is idempotent`() {
        LoopbackPair().use { p ->
            val handler = VncAuthentication(chars("secret12"))
            val key = handler.keyReferenceForTest()!!

            handler.close()
            handler.close()

            assertTrue(key.all { it == 0.toByte() })
            assertThrows(IllegalStateException::class.java) { handler.authenticate(p.client) }
        }
    }

    @Test
    fun `nothing printable or thrown contains the password`() = LoopbackPair().use { p ->
        val secret = "Sup3rS3c"
        val handler = VncAuthentication(chars(secret))
        val printed = mutableListOf(handler.toString(), handler.type.toString())

        // Échec d'authentification côté serveur.
        val server = runVncServer(p, RfbVersion.V3_8, "other123", AtomicReference())
        val failure = assertThrows(RfbProtocolException.AuthenticationFailed::class.java) {
            SecurityNegotiation.negotiate(p.client, RfbVersion.V3_8, listOf(handler))
        }
        server.join()
        printed += listOf(failure.message.orEmpty(), failure.reason, failure.stackTraceToString())

        // Mot de passe invalide.
        val bad = assertThrows(IllegalArgumentException::class.java) { VncAuthentication(chars("\u20ACSup3rS3c")) }
        printed += listOf(bad.message.orEmpty(), bad.stackTraceToString())

        for (text in printed) {
            assertFalse("fuite dans : $text", text.contains(secret) || text.contains("Sup3r"))
        }
    }

    @Test
    fun `constants match the protocol`() {
        assertEquals(16, VncAuthentication.CHALLENGE_LENGTH)
        assertEquals(8, VncAuthentication.MAX_PASSWORD_LENGTH)
        assertEquals(2, SecurityType.VNC_AUTH)
    }
}
