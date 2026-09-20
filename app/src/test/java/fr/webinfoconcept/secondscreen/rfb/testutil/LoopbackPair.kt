package fr.webinfoconcept.secondscreen.rfb.testutil

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Un [RfbSocket] connecté à un « faux serveur » sur loopback, piloté par le test
 * (aucun appareil requis). Le test joue le serveur avec [send]/[sendFragmented]
 * et observe ce que le client a écrit avec [receiveExactly]/[receiveUntilEof].
 *
 * Le serveur peut envoyer d'avance toutes ses réponses : TCP les met en tampon
 * jusqu'à ce que le client les lise.
 */
class LoopbackPair(readTimeoutMs: Int = 2_000) : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val client = RfbSocket(2_000, readTimeoutMs)
    val peer: Socket

    init {
        client.connect("127.0.0.1", server.localPort)
        peer = server.accept()
        peer.tcpNoDelay = true
        peer.soTimeout = 2_000
    }

    fun send(vararg bytes: Int) = send(ByteArray(bytes.size) { bytes[it].toByte() })

    fun send(bytes: ByteArray) {
        peer.getOutputStream().apply {
            write(bytes)
            flush()
        }
    }

    /** Envoie [bytes] par morceaux de [chunk] octets, avec une courte pause entre chaque. */
    fun sendFragmented(bytes: ByteArray, chunk: Int) {
        for (from in bytes.indices step chunk) {
            send(bytes.copyOfRange(from, minOf(from + chunk, bytes.size)))
            Thread.sleep(3)
        }
    }

    fun receiveExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = peer.getInputStream().read(out, read, n - read)
            check(r >= 0) { "EOF prématuré après $read/$n octets" }
            read += r
        }
        return out
    }

    /** Tout ce que le client a écrit jusqu'à la fermeture de sa socket. */
    fun receiveUntilEof(): ByteArray = peer.getInputStream().readBytes()

    override fun close() {
        for (c in listOf<Closeable>(client, peer, server)) {
            try {
                c.close()
            } catch (ignored: IOException) {
            }
        }
    }
}

/** Big-endian, comme sur le fil RFB. */
fun u32(value: Long): ByteArray = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
)

fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

/** U32 longueur + texte, format d'une raison RFB. */
fun reasonString(s: String): ByteArray = u32(s.length.toLong()) + ascii(s)
