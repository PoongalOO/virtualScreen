package fr.webinfoconcept.secondscreen.rfb.transport

import fr.webinfoconcept.secondscreen.perf.TrafficCounter
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Transport TCP du client RFB (SS-010).
 *
 * Enveloppe une [Socket] avec :
 * - timeouts de connexion et de lecture ;
 * - [readFully] qui boucle jusqu'à obtenir exactement les octets demandés
 *   (un `read()` TCP ne remplit jamais le buffer garanti) ;
 * - [close] idempotent et utilisable depuis un autre thread pour débloquer une
 *   lecture en cours ;
 * - erreurs typées ([RfbTransportException]) au lieu d'exceptions brutes.
 *
 * **Threads** : [connect] et [readFully] bloquent et ne doivent jamais être
 * appelés depuis le thread UI (ARCHITECTURE.md). [write] est sérialisé : plusieurs
 * threads peuvent envoyer des messages sans les entrelacer. [close] peut être
 * appelé depuis n'importe quel thread.
 *
 * **Sécurité** : cette classe ne journalise rien et ne lit aucune longueur du
 * flux ; la validation des tailles reçues incombe aux couches protocolaires.
 * Implémente [Closeable] et non `AutoCloseable` (absent avant API 19).
 *
 * @param connectTimeoutMs délai maximal de connexion TCP, strictement positif.
 * @param readTimeoutMs délai maximal d'attente de données, 0 = illimité.
 * @param socketFactory fabrique de socket, injectable pour les tests.
 */
class RfbSocket(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val socketFactory: () -> Socket = { Socket() }
) : Closeable {

    private enum class State { NEW, CONNECTING, CONNECTED, CLOSED }

    private val stateLock = Any()
    private val writeLock = Any()

    // Protégés par stateLock. Jamais tenu pendant une opération bloquante, pour
    // que close() puisse toujours interrompre connect()/readFully().
    private var state = State.NEW
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readTimeout = readTimeoutMs

    /**
     * Compteur d'octets échangés (SS-060), ou `null`. Alimenté **seulement si les mesures sont actives** : sinon chaque
     * lecture ou écriture ne coûte qu'une lecture de booléen. À poser avant [connect].
     */
    @Volatile
    var traffic: TrafficCounter? = null

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs doit être > 0" }
        require(readTimeoutMs >= 0) { "readTimeoutMs doit être >= 0" }
    }

    val isConnected: Boolean
        get() = synchronized(stateLock) { state == State.CONNECTED }

    val isClosed: Boolean
        get() = synchronized(stateLock) { state == State.CLOSED }

    /**
     * Ouvre la connexion TCP. Bloquant (résolution DNS incluse, sans timeout propre).
     * En cas d'échec la socket est fermée avant de lancer l'exception.
     *
     * @throws IllegalArgumentException hôte vide ou port hors de 1..65535.
     * @throws IllegalStateException si déjà appelée.
     * @throws RfbTransportException.Closed si [close] a été appelée avant ou pendant.
     */
    fun connect(host: String, port: Int) {
        require(host.isNotBlank()) { "host vide" }
        require(port in 1..65535) { "port hors limites : $port" }

        val s: Socket
        synchronized(stateLock) {
            when (state) {
                State.NEW -> Unit
                State.CLOSED -> throw RfbTransportException.Closed()
                else -> throw IllegalStateException("connect() déjà appelée")
            }
            s = socketFactory()
            socket = s
            state = State.CONNECTING
        }

        try {
            val address = InetSocketAddress(host, port)
            if (address.isUnresolved) throw RfbTransportException.UnknownHost(host)

            // Latence prioritaire : les messages client (pointeur, clavier) sont petits.
            s.tcpNoDelay = true
            s.connect(address, connectTimeoutMs)

            // Les octets sont comptés au niveau de la socket (une fois par remplissage du tampon, pas par octet lu).
            val counted = object : java.io.FilterInputStream(s.getInputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    traffic?.onReceived(n)
                    return n
                }

                override fun read(): Int {
                    val n = super.read()
                    if (n >= 0) traffic?.onReceived(1)
                    return n
                }
            }
            val stream = BufferedInputStream(counted, INPUT_BUFFER_SIZE)
            val out = s.getOutputStream()
            synchronized(stateLock) {
                if (state == State.CLOSED) throw RfbTransportException.Closed()
                s.soTimeout = readTimeout
                input = stream
                output = out
                state = State.CONNECTED
            }
        } catch (e: RfbTransportException) {
            close()
            throw e
        } catch (e: SocketTimeoutException) {
            close()
            throw RfbTransportException.ConnectTimeout(connectTimeoutMs, e)
        } catch (e: UnknownHostException) {
            close()
            throw RfbTransportException.UnknownHost(host, e)
        } catch (e: IOException) {
            val closedLocally = isClosed
            close()
            throw when {
                closedLocally -> RfbTransportException.Closed(e)
                isRefused(e) -> RfbTransportException.ConnectionRefused(e)
                else -> RfbTransportException.ConnectFailed(e)
            }
        }
    }

    /**
     * Modifie le délai de lecture (0 = illimité). Typiquement court pendant le
     * handshake, puis illimité en boucle de réception (le serveur n'envoie rien
     * tant que l'écran ne change pas).
     */
    fun setReadTimeout(timeoutMs: Int) {
        require(timeoutMs >= 0) { "timeoutMs doit être >= 0" }
        synchronized(stateLock) {
            readTimeout = timeoutMs
            val s = socket
            if (state == State.CONNECTED && s != null) {
                try {
                    s.soTimeout = timeoutMs
                } catch (e: IOException) {
                    throw RfbTransportException.Closed(e)
                }
            }
        }
    }

    /**
     * Lit exactement [length] octets dans [buffer] à partir de [offset], en
     * bouclant sur les lectures partielles. Bloquant.
     *
     * Sur [RfbTransportException.EndOfStream] ou [RfbTransportException.Io] la
     * socket est fermée. Sur [RfbTransportException.ReadTimeout] elle reste ouverte.
     */
    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        checkBounds(buffer.size, offset, length)
        val stream = synchronized(stateLock) {
            if (state == State.CONNECTED) input else null
        } ?: throw RfbTransportException.Closed()

        var total = 0
        try {
            while (total < length) {
                val n = stream.read(buffer, offset + total, length - total)
                if (n < 0) {
                    close()
                    throw RfbTransportException.EndOfStream(total, length)
                }
                total += n
            }
        } catch (e: RfbTransportException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // Timeout de lecture ; si la socket a été fermée entre-temps, c'est Closed.
            if (isClosed) throw RfbTransportException.Closed(e)
            throw RfbTransportException.ReadTimeout(total, length, e)
        } catch (e: IOException) {
            if (isClosed) throw RfbTransportException.Closed(e)
            close()
            throw RfbTransportException.Io(e)
        }
    }

    /**
     * Envoie [length] octets puis vide le tampon. Un message RFB doit être
     * assemblé dans un seul buffer et envoyé en un seul appel.
     */
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkBounds(buffer.size, offset, length)
        val out = synchronized(stateLock) {
            if (state == State.CONNECTED) output else null
        } ?: throw RfbTransportException.Closed()

        synchronized(writeLock) {
            try {
                out.write(buffer, offset, length)
                out.flush()
                traffic?.onSent(length)
            } catch (e: IOException) {
                if (isClosed) throw RfbTransportException.Closed(e)
                close()
                throw RfbTransportException.Io(e)
            }
        }
    }

    /**
     * Ferme la socket. Idempotent, sans exception, utilisable depuis n'importe
     * quel thread : une lecture bloquée sur un autre thread échoue alors avec
     * [RfbTransportException.Closed].
     */
    override fun close() {
        val s: Socket?
        synchronized(stateLock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            s = socket
            socket = null
            input = null
            output = null
        }
        try {
            s?.close()
        } catch (ignored: IOException) {
            // Fermeture au mieux : rien d'exploitable à signaler.
        }
    }

    private fun checkBounds(size: Int, offset: Int, length: Int) {
        // Formulation sans addition pour éviter tout dépassement d'Int.
        require(offset >= 0 && length >= 0 && offset <= size - length) {
            "plage invalide : offset=$offset length=$length taille=$size"
        }
    }

    /**
     * `ConnectException` = refus de connexion, sauf marqueur explicite d'un autre
     * échec. On ne cherche pas le mot « refused » : les messages de la JVM de
     * bureau sont localisés ("Connexion refusée"). Sur Android (API 17 inclus,
     * `ErrnoException` n'existe qu'à partir d'API 21) les messages contiennent le
     * nom errno anglais, ex. "... connect failed: ENETUNREACH (Network is unreachable)".
     */
    private fun isRefused(e: IOException): Boolean {
        if (e !is ConnectException) return false
        val message = e.message ?: return true
        return NOT_REFUSED_MARKERS.none { message.contains(it, ignoreCase = true) }
    }

    companion object {
        private val NOT_REFUSED_MARKERS =
            listOf("unreachable", "ENETUNREACH", "EHOSTUNREACH", "timed out", "ETIMEDOUT")

        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val INPUT_BUFFER_SIZE = 16 * 1024
    }
}
