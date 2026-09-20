package fr.webinfoconcept.secondscreen.rfb.testutil

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Faux serveur RFB sur loopback, piloté par un script (SS-054). Chaque connexion appelle [onConnection] sur son propre
 * thread ; plusieurs connexions successives sont acceptées (reconnexion). Écrit à part du code de production : le DES
 * de VNC Authentication est refait ici avec `javax.crypto`, pas avec `VncAuthentication`.
 */
class FakeRfbServer(private val onConnection: (ServerSession) -> Unit) : Closeable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    private val open = mutableListOf<Socket>()
    val port: Int = server.localPort
    val connections = AtomicInteger()

    init {
        Thread({
            try {
                while (true) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    synchronized(open) { open += socket }
                    connections.incrementAndGet()
                    Thread({
                        val session = ServerSession(socket)
                        try {
                            onConnection(session)
                        } catch (ignored: IOException) {
                            // le client a coupé : fin normale du script
                        } catch (ignored: InterruptedException) {
                        } finally {
                            try { socket.close() } catch (ignored: IOException) {}
                        }
                    }, "fake-rfb-connection").apply { isDaemon = true }.start()
                }
            } catch (ignored: IOException) {
                // serveur fermé
            }
        }, "fake-rfb-accept").apply { isDaemon = true }.start()
    }

    override fun close() {
        try { server.close() } catch (ignored: IOException) {}
        synchronized(open) { open.forEach { try { it.close() } catch (ignored: IOException) {} } }
    }
}

/** Ce que le client a envoyé après le handshake, avant la première boucle. */
class ClientSetup(val pixelFormat: ByteArray, val encodings: List<Int>, val firstRequest: ByteArray)

/** Un côté serveur de connexion : lecture exacte, écriture, et les étapes usuelles du handshake. */
class ServerSession(val socket: Socket) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    /** Messages client reçus par [startCollecting], dans l'ordre. */
    val received = LinkedBlockingQueue<ByteArray>()

    fun read(n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = input.read(out, done, n - done)
            if (r < 0) throw IOException("le client a fermé la connexion")
            done += r
        }
        return out
    }

    fun send(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    fun send(vararg bytes: Int) = send(ByteArray(bytes.size) { bytes[it].toByte() })

    fun banner(text: String = "RFB 003.008\n"): String {
        send(text.toByteArray(Charsets.US_ASCII))
        return String(read(12), Charsets.US_ASCII)
    }

    /** RFB 3.8, type `None` : offre [1], lit le choix, envoie `SecurityResult` OK. */
    fun securityNone() {
        send(1, 1)
        read(1)
        send(0, 0, 0, 0)
    }

    /**
     * RFB 3.8, VNC Authentication : offre [2], envoie un challenge, vérifie la réponse DES.
     * @return `true` si le mot de passe est bon. Sinon envoie l'échec avec [failureReason].
     */
    fun securityVncAuth(password: String, failureReason: String = "bad password"): Boolean {
        send(1, 2)
        read(1)
        val challenge = ByteArray(16) { (it * 17 + 3).toByte() }
        send(challenge)
        val response = read(16)
        val ok = response.contentEquals(vncResponse(password, challenge))
        if (ok) {
            send(0, 0, 0, 0)
        } else {
            send(0, 0, 0, 1)
            send(u32(failureReason.length.toLong()) + ascii(failureReason))
        }
        return ok
    }

    /** RFB 3.8 : le serveur refuse avant toute authentification (0 type + raison). */
    fun rejectConnection(reason: String) {
        send(0)
        send(u32(reason.length.toLong()) + ascii(reason))
    }

    /** `ClientInit` lu, puis `ServerInit` : taille, format natif 32 bits little-endian, nom. */
    fun serverInit(width: Int, height: Int, name: String = "test") {
        read(1) // ClientInit
        val pixelFormat = byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0)
        send(u16(width) + u16(height) + pixelFormat + u32(name.length.toLong()) + ascii(name))
    }

    /** Lit `SetPixelFormat` (20 octets), `SetEncodings` (4 + 4n) et la première `FramebufferUpdateRequest` (10). */
    fun readSetup(): ClientSetup {
        val pixelFormat = read(20)
        val head = read(4)
        val count = ((head[2].toInt() and 255) shl 8) or (head[3].toInt() and 255)
        val list = read(4 * count)
        val encodings = (0 until count).map { i ->
            ((list[4 * i].toInt() and 255) shl 24) or ((list[4 * i + 1].toInt() and 255) shl 16) or
                ((list[4 * i + 2].toInt() and 255) shl 8) or (list[4 * i + 3].toInt() and 255)
        }
        return ClientSetup(pixelFormat, encodings, read(10))
    }

    /** Handshake complet sans authentification, jusqu'à la première requête : le cas nominal. */
    fun standardHandshake(width: Int = 64, height: Int = 48, name: String = "test"): ClientSetup {
        banner()
        securityNone()
        serverInit(width, height, name)
        return readSetup()
    }

    /** Un `FramebufferUpdate` d'un rectangle RAW tiré du bureau de test. */
    fun sendDesktopUpdate(x: Int, y: Int, w: Int, h: Int) {
        send(framebufferUpdate(rawRect(x, y, w, h, desktopRect(x, y, w, h))))
    }

    /** Lit un message client complet (type + corps) selon sa longueur connue ; `null` pour un type inattendu. */
    fun readClientMessage(): ByteArray {
        val type = read(1)[0].toInt() and 255
        val body = when (type) {
            0 -> read(19)
            2 -> {
                val head = read(3)
                val n = ((head[1].toInt() and 255) shl 8) or (head[2].toInt() and 255)
                head + read(4 * n)
            }
            3 -> read(9)
            4 -> read(7)
            5 -> read(5)
            6 -> {
                val head = read(7)
                val n = ((head[3].toInt() and 255) shl 24) or ((head[4].toInt() and 255) shl 16) or
                    ((head[5].toInt() and 255) shl 8) or (head[6].toInt() and 255)
                head + read(n)
            }
            else -> throw IOException("type de message client inconnu : $type")
        }
        return byteArrayOf(type.toByte()) + body
    }

    /** Lit en tâche de fond tous les messages client et les range dans [received]. */
    fun startCollecting(onMessage: ((ByteArray) -> Unit)? = null) {
        Thread({
            try {
                while (true) {
                    val message = readClientMessage()
                    received += message
                    onMessage?.invoke(message)
                }
            } catch (ignored: IOException) {
            }
        }, "fake-rfb-collector").apply { isDaemon = true }.start()
    }

    /** Attend un message client vérifiant [predicate] ; `null` si aucun n'arrive dans [timeoutMs]. */
    fun awaitMessage(timeoutMs: Long = 5_000, predicate: (ByteArray) -> Boolean): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            val m = received.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (predicate(m)) return m
        }
    }

    /** Attend la fermeture de la connexion par le client. */
    fun awaitClientClose(timeoutMs: Long = 5_000): Boolean {
        socket.soTimeout = timeoutMs.toInt()
        return try {
            input.read() < 0
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }
    }

    fun closeNow() {
        try { socket.close() } catch (ignored: IOException) {}
    }

    companion object {
        /** Réponse VNC Authentication : DES-ECB du challenge, clé = mot de passe sur 8 octets, bits de chaque octet inversés. */
        fun vncResponse(password: String, challenge: ByteArray): ByteArray {
            val key = ByteArray(8)
            for (i in 0 until minOf(8, password.length)) {
                var b = password[i].code and 0xFF
                var reversed = 0
                repeat(8) { reversed = (reversed shl 1) or (b and 1); b = b shr 1 }
                key[i] = reversed.toByte()
            }
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }
    }
}
