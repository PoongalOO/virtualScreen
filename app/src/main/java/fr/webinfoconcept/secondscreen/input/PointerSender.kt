package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.util.concurrent.ArrayBlockingQueue

/** Où [PointerActions] dépose les messages à envoyer au serveur ; ne bloque jamais. */
interface MessageSink {
    /** Message d'état (appui, relâchement, clic) : ne doit pas se perdre. @return `false` s'il est refusé. */
    fun send(message: ByteArray): Boolean

    /** Message remplaçable (déplacement, molette). @return `false` s'il est abandonné. */
    fun sendMove(message: ByteArray): Boolean
}

/**
 * Envoie les messages d'entrée au serveur **hors du thread UI** (SS-040).
 *
 * Les événements tactiles arrivent sur le thread UI, où aucun accès réseau n'est permis (AGENTS.md) : [send] ne fait
 * que poser le message dans une file **bornée** ([capacity]) et rend la main immédiatement ; un thread démon dédié les
 * écrit dans l'ordre. Un message est un tableau complet écrit en un seul appel, donc jamais entrelacé avec un autre
 * (voir `ClientMessages.leftClick`).
 *
 * - **File pleine** (liaison bloquée) : [send] refuse le message et retourne `false` ; il n'attend jamais et la
 *   mémoire reste bornée. On perd des messages entiers, jamais la moitié d'un clic.
 * - **Deux priorités** (SS-042). Les messages *d'état* ([send] : appui, relâchement, clic) ne doivent pas se perdre,
 *   sinon le serveur garde un bouton enfoncé. Les *déplacements* ([sendMove]) sont remplaçables : le suivant les
 *   corrige. Ils ne sont acceptés que tant qu'il reste de la place pour les messages d'état (le quart de la file est
 *   réservé), donc une liaison lente perd des déplacements, jamais un relâchement.
 * - **Erreur d'écriture** (socket fermée) : le thread s'arrête et [onError] est appelé **une seule fois** ; ensuite
 *   [send] retourne `false`. Le contrôleur de connexion décide de la suite.
 * - **Arrêt** : les messages encore en file sont abandonnés (un clic tardif serait pire qu'un clic perdu). Un
 *   [stop] ne débloque pas une écriture déjà bloquée dans la socket : c'est la fermeture de la socket qui le fait.
 *
 * Idempotent : [start] et [stop] peuvent être appelés plusieurs fois ; un envoyeur arrêté peut être relancé.
 * Rien n'est journalisé (aucune position ni saisie dans les logs).
 *
 * @param write écrit un message sur la connexion ; appelé uniquement depuis le thread de l'envoyeur.
 * @param capacity nombre maximal de messages en attente.
 * @param onError notifié quand [write] échoue.
 */
class PointerSender(
    private val onError: (Throwable) -> Unit = {},
    capacity: Int = DEFAULT_CAPACITY,
    private val write: (ByteArray) -> Unit
) : MessageSink {
    init {
        require(capacity in 1..MAX_CAPACITY) { "capacité hors de 1..$MAX_CAPACITY : $capacity" }
    }

    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    private val stateReserve = maxOf(1, capacity / 4)
    private val lock = Any()
    private var worker: Thread? = null
    private var dropped = 0L

    /** `true` tant que l'envoyeur accepte des messages. */
    val isRunning: Boolean
        get() = synchronized(lock) { worker != null }

    /** Nombre de messages refusés ([send] ou [sendMove]) parce que la file était pleine depuis la création. */
    val droppedCount: Long
        get() = synchronized(lock) { dropped }

    /** Démarre l'envoi. Sans effet s'il tourne déjà. La file est vidée : rien de périmé n'est envoyé. */
    fun start() {
        synchronized(lock) {
            if (worker != null) return
            queue.clear()
            val thread = Thread({ loop() }, THREAD_NAME)
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
    }

    /** Arrête l'envoi, abandonne les messages en attente et attend brièvement la fin du thread. */
    fun stop() {
        val thread: Thread?
        synchronized(lock) {
            thread = worker
            worker = null
            queue.clear()
        }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Met [message] en file. Ne bloque jamais : utilisable depuis le thread UI.
     * @return `false` si l'envoyeur est arrêté ou si la file est pleine (le message est alors perdu).
     */
    override fun send(message: ByteArray): Boolean {
        synchronized(lock) {
            if (worker == null) return false
            if (queue.offer(message)) return true
            dropped++
            return false
        }
    }

    /**
     * Met en file un message de **déplacement** ([message] : `PointerEvent` bouton inchangé). Refusé, sans attendre,
     * dès que la file n'a plus que la place réservée aux messages d'état de [send].
     * @return `false` si l'envoyeur est arrêté ou si le déplacement a été abandonné.
     */
    override fun sendMove(message: ByteArray): Boolean {
        synchronized(lock) {
            if (worker == null) return false
            if (queue.remainingCapacity() > stateReserve && queue.offer(message)) return true
            dropped++
            return false
        }
    }

    private fun loop() {
        val me = Thread.currentThread()
        try {
            while (true) {
                val message = queue.take()
                if (!isCurrent(me)) return // arrêté (ou relancé) pendant l'attente
                write(message)
            }
        } catch (e: InterruptedException) {
            // arrêt demandé
        } catch (t: Throwable) {
            val first = synchronized(lock) {
                if (worker === me) {
                    worker = null
                    queue.clear()
                    true
                } else {
                    false
                }
            }
            if (first) {
                try {
                    onError(t)
                } catch (ignored: Throwable) {
                    // un onError défaillant ne doit pas faire tomber le processus
                }
            }
        }
    }

    private fun isCurrent(thread: Thread) = synchronized(lock) { worker === thread }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_CAPACITY = 4096
        private const val THREAD_NAME = "secondscreen-input"
        private const val JOIN_TIMEOUT_MS = 1_000L

        /** Envoyeur écrivant sur la connexion RFB. */
        fun forSocket(socket: RfbSocket, onError: (Throwable) -> Unit = {}): PointerSender =
            PointerSender(onError) { socket.write(it, 0, it.size) }
    }
}
