package fr.webinfoconcept.secondscreen.net

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket

/**
 * Émet un petit message à intervalle régulier pour que la liaison Wi-Fi ne devienne jamais silencieuse.
 *
 * **Le problème, mesuré** (PERFORMANCE.md) : sur la GT-P5110, quand la liaison est silencieuse la radio de la
 * tablette s'endort et un paquet entrant attend en médiane ~700 ms, jusqu'à ~1,9 s, avant d'être délivré. Pour
 * un écran distant qui ne change pas pendant un moment, la première mise à jour qui suit arrive avec ce retard.
 * Le verrou Wi-Fi haute performance d'Android (`WifiLock`) n'y change **rien** sur cet appareil (mesuré, A/B/A/B).
 * En revanche un petit paquet **émis** toutes les 100 ms suffit : latence médiane ~4 ms.
 *
 * **Coût** : ~10 paquets de quelques dizaines d'octets par seconde, soit quelques kbit/s, et une radio qui reste
 * éveillée. Acceptable pour un moniteur branché ; à ne faire tourner que pendant une session.
 *
 * Le battement s'exécute sur un thread démon dédié. Si [action] lève une exception (typiquement la socket a été
 * fermée), le battement s'arrête de lui-même et [onError] est appelé **une seule fois**.
 *
 * Idempotent : [start] et [stop] peuvent être appelés plusieurs fois ; un battement arrêté peut être relancé.
 * Peut être arrêté depuis n'importe quel thread, y compris depuis [action] ou [onError].
 *
 * @param intervalMs délai entre deux battements, de 10 à [MAX_INTERVAL_MS] : au-delà de 400 ms la liaison
 *   redevient silencieuse et le mécanisme perd son effet (mesuré).
 * @param action ce qui est envoyé à chaque battement ; doit être rapide et ne pas allouer.
 * @param onError notifié si [action] échoue ; le battement est alors arrêté.
 */
class KeepAlive(
    private val intervalMs: Long = ClientMessages.KEEP_ALIVE_INTERVAL_MS,
    private val onError: (Throwable) -> Unit = {},
    private val action: () -> Unit
) {
    init {
        require(intervalMs in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            "intervalle hors de $MIN_INTERVAL_MS..$MAX_INTERVAL_MS ms : $intervalMs"
        }
    }

    private val lock = Object()
    private var thread: Thread? = null
    private var stopRequested = false

    /** `true` tant que le battement tourne. */
    val isRunning: Boolean
        get() = synchronized(lock) { thread != null }

    /** Démarre le battement. Sans effet s'il tourne déjà. */
    fun start() {
        synchronized(lock) {
            if (thread != null) return
            stopRequested = false
            val worker = Thread({ loop() }, THREAD_NAME)
            worker.isDaemon = true
            thread = worker
            worker.start()
        }
    }

    /**
     * Arrête le battement et attend brièvement la fin du thread. Sans effet s'il n'est pas démarré. Appelé depuis le
     * thread du battement lui-même (dans [action] ou [onError]), il ne l'attend pas.
     */
    fun stop() {
        val worker: Thread?
        synchronized(lock) {
            worker = thread
            stopRequested = true
            lock.notifyAll()
            if (worker !== Thread.currentThread()) thread = null // le thread du battement se retire lui-même
        }
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun loop() {
        // Échéances absolues : pas de dérive cumulée quand l'action prend un peu de temps.
        var due = System.nanoTime() + intervalMs * 1_000_000L
        while (true) {
            synchronized(lock) {
                while (!stopRequested) {
                    val remainingMs = (due - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0) break
                    try {
                        lock.wait(remainingMs)
                    } catch (e: InterruptedException) {
                        stopRequested = true
                    }
                }
                if (stopRequested) {
                    if (thread === Thread.currentThread()) thread = null
                    return
                }
            }
            try {
                action()
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (thread === Thread.currentThread()) thread = null
                    stopRequested = true
                }
                try {
                    onError(t)
                } catch (ignored: Throwable) {
                    // un onError défaillant ne doit pas faire tomber le processus
                }
                return
            }
            due += intervalMs * 1_000_000L
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 10L
        const val MAX_INTERVAL_MS = 400L
        private const val THREAD_NAME = "secondscreen-keepalive"
        private const val JOIN_TIMEOUT_MS = 1_000L

        /**
         * Battement sur la connexion RFB : envoie [ClientMessages.keepAliveRequest] toutes les
         * [ClientMessages.KEEP_ALIVE_INTERVAL_MS] ms. Le message est construit une fois : aucune allocation par
         * battement. `RfbSocket.write` étant sérialisé, il ne s'entrelace pas avec les autres messages du client.
         */
        fun forSocket(
            socket: RfbSocket,
            intervalMs: Long = ClientMessages.KEEP_ALIVE_INTERVAL_MS,
            onError: (Throwable) -> Unit = {}
        ): KeepAlive {
            val message = ClientMessages.keepAliveRequest()
            return KeepAlive(intervalMs, onError) { socket.write(message, 0, message.size) }
        }
    }
}
