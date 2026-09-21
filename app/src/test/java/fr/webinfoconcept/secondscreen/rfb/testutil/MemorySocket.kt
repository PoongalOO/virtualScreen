package fr.webinfoconcept.secondscreen.rfb.testutil

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress

/**
 * Une [Socket] sans réseau : le client lit exactement [incoming] puis rencontre la fin du flux, et ce qu'il écrit est
 * gardé dans [written]. Sert aux tests de robustesse (SS-070) : des dizaines de milliers de flux hostiles sans ouvrir une
 * seule connexion TCP, et **sans jamais bloquer** (la fin du flux arrive toujours, donc aucun test ne peut attendre un octet).
 */
class MemorySocket(private val incoming: ByteArray) : Socket() {
    val written = ByteArrayOutputStream()

    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) = Unit
    override fun getInputStream(): InputStream = ByteArrayInputStream(incoming)
    override fun getOutputStream(): OutputStream = written
    override fun close() = Unit
}

/** Un [RfbSocket] connecté qui lira [incoming] puis la fin du flux. */
fun memoryClient(incoming: ByteArray): RfbSocket =
    RfbSocket(1_000, 1_000, { MemorySocket(incoming) }).also { it.connect("127.0.0.1", 5900) }
