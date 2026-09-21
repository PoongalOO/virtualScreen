package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.input.MessageSink
import fr.webinfoconcept.secondscreen.input.PointerSender
import fr.webinfoconcept.secondscreen.net.KeepAlive
import fr.webinfoconcept.secondscreen.perf.PerfStats
import fr.webinfoconcept.secondscreen.render.RenderTarget
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
import fr.webinfoconcept.secondscreen.rfb.protocol.EncodingMode
import fr.webinfoconcept.secondscreen.rfb.protocol.InitExchange
import fr.webinfoconcept.secondscreen.rfb.protocol.NoneSecurity
import fr.webinfoconcept.secondscreen.rfb.protocol.PixelFormat
import fr.webinfoconcept.secondscreen.rfb.protocol.ProtocolVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.RectangleListener
import fr.webinfoconcept.secondscreen.rfb.protocol.RfbVersion
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityHandler
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityNegotiation
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityType
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerInit
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessage
import fr.webinfoconcept.secondscreen.rfb.protocol.ServerMessageReader
import fr.webinfoconcept.secondscreen.rfb.protocol.VncAuthentication
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Ce qu'on sait d'une session établie (SS-054), pour l'écran de diagnostic. Aucun secret. */
class SessionInfo(
    val version: RfbVersion,
    /** Type de sécurité utilisé, voir [SecurityType]. */
    val securityType: Int,
    val server: ServerInit,
    val framebuffer: Framebuffer
)

/** Réglages du contrôleur ; les valeurs par défaut sont celles de l'application, les tests en prennent de plus courtes. */
class ConnectionConfig(
    /** Délai maximal d'ouverture TCP. */
    val connectTimeoutMs: Int = 5_000,
    /** Silence toléré pendant la négociation. */
    val handshakeTimeoutMs: Int = 10_000,
    /** Réveil de la boucle de lecture : sert à vérifier que le serveur donne encore signe de vie. */
    val readTimeoutMs: Int = 5_000,
    /** Silence total au-delà duquel le réseau est déclaré coupé (voir [heartbeatIntervalMs]). */
    val livenessTimeoutMs: Long = 15_000,
    /** Battement Wi-Fi (SS-064) : requête incrémentale d'un pixel. */
    val keepAliveIntervalMs: Long = ClientMessages.KEEP_ALIVE_INTERVAL_MS,
    /** Intervalle de la requête qui **oblige** le serveur à répondre : c'est elle qui prouve que la liaison vit. */
    val heartbeatIntervalMs: Long = 5_000,
    /**
     * Reconnexion automatique (SS-055) : attente avant chaque tentative successive. Le **nombre** de tentatives est celui de la
     * liste (bornée), et l'attente ne dépasse jamais le dernier élément : 1, 2, 4, 8, 15 puis 30 s, soit 8 tentatives sur
     * environ 2 minutes 30, après quoi la reconnexion est abandonnée. Vide : pas de reconnexion automatique.
     */
    val reconnectDelaysMs: List<Long> = DEFAULT_RECONNECT_DELAYS_MS,
    /**
     * Une session qui a tenu au moins ce temps remet le compteur de tentatives à zéro. Une session qui retombe aussitôt
     * (liaison instable) ne le remet **pas** : sans cela la reconnexion pourrait boucler indéfiniment.
     */
    val reconnectStableMs: Long = 30_000
) {
    init {
        require(reconnectDelaysMs.all { it in 1..MAX_RECONNECT_DELAY_MS }) { "délai de reconnexion hors de 1..$MAX_RECONNECT_DELAY_MS ms" }
        require(reconnectDelaysMs.size <= MAX_RECONNECT_ATTEMPTS) { "trop de tentatives de reconnexion : ${reconnectDelaysMs.size}" }
        require(reconnectStableMs >= 0) { "durée de stabilité négative" }
        require(connectTimeoutMs > 0 && handshakeTimeoutMs > 0 && readTimeoutMs > 0) { "délais > 0" }
        require(livenessTimeoutMs > readTimeoutMs) { "le délai de silence doit dépasser le réveil de lecture" }
        require(heartbeatIntervalMs >= keepAliveIntervalMs) { "battement de vie plus rapide que le battement Wi-Fi" }
    }

    companion object {
        val DEFAULT_RECONNECT_DELAYS_MS: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000)
        const val MAX_RECONNECT_DELAY_MS = 5 * 60 * 1_000L
        const val MAX_RECONNECT_ATTEMPTS = 32
    }
}

/**
 * Cycle de vie d'une connexion RFB (SS-054) : ouverture, négociation, session, arrêt, reconnexion. **Seul propriétaire
 * de la socket** ; l'interface ne la voit jamais (ARCHITECTURE.md, « Gestion d'état »).
 *
 * ## Threads
 * - [connect], [reconnect], [disconnect], les accesseurs et les écouteurs sont appelables de **n'importe quel thread**,
 *   y compris le thread UI : aucun n'attend le réseau.
 * - Toute la session (connexion, négociation, boucle de lecture) tourne sur **un thread dédié** `secondscreen-session`.
 * - Les écouteurs sont appelés **sur le thread qui change l'état** (session ou appelant de [disconnect]) : l'interface
 *   doit se remettre sur le thread UI (`runOnUiThread`).
 *
 * ## Déroulement
 * ```text
 * TCP -> version -> sécurité (+ mot de passe) -> ClientInit/ServerInit -> SetPixelFormat, SetEncodings,
 * FramebufferUpdateRequest complète -> boucle : lire un message ; après chaque FramebufferUpdate, rendre puis
 * redemander en incrémental.
 * ```
 * Pendant la session, [KeepAlive] envoie toutes les 100 ms une requête d'un pixel (SS-064) et, toutes les
 * [ConnectionConfig.heartbeatIntervalMs], une requête **non incrémentale** d'un pixel : le serveur doit y répondre, donc
 * un silence de plus de [ConnectionConfig.livenessTimeoutMs] prouve une liaison morte (Wi-Fi coupé, PC en veille) sans
 * attendre les minutes que TCP met à s'en apercevoir.
 *
 * ## Secrets
 * Le mot de passe arrive en `CharArray`, jamais en `String`. [connect] en prend possession : le tableau est **effacé**
 * dans tous les cas (connexion refusée, échec, succès). Par défaut il n'est **pas conservé** : [reconnect] en redemande
 * un si le serveur en exigeait un ([reconnectNeedsPassword]). Rien n'est journalisé.
 *
 * **Exception, demandée explicitement** : avec `autoReconnect = true` (SS-055), une **copie** est gardée **en mémoire
 * seulement** (jamais écrite sur le stockage) pour pouvoir se reconnecter sans l'utilisateur. Elle est **effacée** dès que
 * [disconnect] est appelé, que la reconnexion est arrêtée ou abandonnée, ou que le fil de session se termine. Chaque
 * tentative reçoit sa propre copie, effacée par `VncAuthentication`. Voir SECURITY.md.
 *
 * ## Reconnexion automatique (SS-055)
 * Quand une session **établie** est perdue pour une cause passagère ([FailureKind.isTransient]) et que `autoReconnect` est
 * demandé, le contrôleur passe en [ConnectionState.RECONNECTING], attend ([ConnectionConfig.reconnectDelaysMs], délai
 * croissant et borné), retente, et ainsi de suite jusqu'à [ConnectionConfig.reconnectDelaysMs] tentatives, puis abandonne
 * en [ConnectionState.ERROR] ([gaveUpAfter]). Un échec **non passager** (mot de passe refusé...) l'arrête aussitôt ; un
 * premier échec de connexion, avant toute session établie, n'est **pas** retenté (l'utilisateur est devant l'écran).
 * [retryNow] saute l'attente, [stopAutoReconnect] renonce, [disconnect] arrête tout.
 *
 * ## Sessions successives
 * Chaque tentative a un numéro de génération. Un ancien thread encore en train de se terminer ne peut plus rien publier
 * (état, framebuffer, rendu) une fois qu'une nouvelle tentative ou un [disconnect] a eu lieu.
 *
 * @param socketFactory fabrique de la socket, injectable pour les tests.
 * @param perf compteurs de performance (SS-060), branchés sur la socket et le lecteur de messages ; `null` : aucune mesure.
 */
class ConnectionController(
    private val config: ConnectionConfig = ConnectionConfig(),
    private val socketFactory: () -> RfbSocket = { RfbSocket(config.connectTimeoutMs, config.handshakeTimeoutMs) },
    private val perf: PerfStats? = null
) {
    /** Notifié de chaque changement d'état, sur un thread quelconque. */
    interface Listener {
        fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?)
    }

    private val lock = Object() // les attentes de la reconnexion automatique utilisent wait/notify
    private val listeners = CopyOnWriteArrayList<Listener>()

    // Tout ce qui suit est protégé par `lock`.
    private var currentState = ConnectionState.DISCONNECTED
    private var currentFailure: ConnectionFailure? = null
    private var currentSession: SessionInfo? = null
    private var generation = 0L
    private var activeSocket: RfbSocket? = null
    private var sessionThread: Thread? = null
    private var lastParams: ConnectionParams? = null
    private var serverWantsPassword = false
    private var retainedPassword: CharArray? = null
    private var currentReconnectStatus: ReconnectStatus? = null
    private var gaveUp = 0
    private var retryNowRequested = false
    private var stopAutoRequested = false

    private var sessionSender: PointerSender? = null

    @Volatile private var renderTarget: RenderTarget? = null
    private val updates = AtomicLong()

    /**
     * Destination des entrées (SS-040) : le même objet pendant toute la vie du contrôleur, qui transmet à l'envoyeur
     * **de la session courante** (un par session, arrêté avec elle). Hors session il refuse tout message : un toucher
     * pendant une coupure n'est pas mis de côté pour être rejoué plus tard.
     */
    val input: MessageSink = object : MessageSink {
        override fun send(message: ByteArray): Boolean = synchronized(lock) { sessionSender }?.send(message) ?: false
        override fun sendMove(message: ByteArray): Boolean = synchronized(lock) { sessionSender }?.sendMove(message) ?: false
    }

    val state: ConnectionState
        get() = synchronized(lock) { currentState }

    /**
     * Pourquoi la connexion a échoué ; non nul en état [ConnectionState.ERROR], et en état [ConnectionState.RECONNECTING]
     * pendant une reconnexion automatique (la cause de la coupure).
     */
    val failure: ConnectionFailure?
        get() = synchronized(lock) { currentFailure }

    /** La session établie, ou `null` hors état [ConnectionState.CONNECTED]. */
    val session: SessionInfo?
        get() = synchronized(lock) { currentSession }

    /** Nombre de `FramebufferUpdate` reçus depuis l'établissement de la dernière session. */
    val updateCount: Long
        get() = updates.get()

    /** Reconnexion automatique en cours (attente ou tentative), ou `null` (SS-055). */
    val reconnectStatus: ReconnectStatus?
        get() = synchronized(lock) { currentReconnectStatus }

    /** Nombre de tentatives automatiques après lesquelles la reconnexion a été **abandonnée** ; 0 sinon. */
    val gaveUpAfter: Int
        get() = synchronized(lock) { gaveUp }

    /** Copie du mot de passe conservé en mémoire pour la reconnexion automatique, ou `null` (tests seulement). */
    internal fun retainedPasswordForTest(): CharArray? = synchronized(lock) { retainedPassword?.copyOf() }

    /** Dernière destination, pour [reconnect]. */
    val lastConnection: ConnectionParams?
        get() = synchronized(lock) { lastParams }

    /**
     * `true` si [reconnect] doit recevoir un mot de passe : le dernier serveur en exigeait un (le contrôleur ne le
     * garde pas, SECURITY.md).
     */
    val reconnectNeedsPassword: Boolean
        get() = synchronized(lock) { serverWantsPassword }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Cible du rendu (le [fr.webinfoconcept.secondscreen.render.RemoteSurfaceView] de l'écran distant), ou `null`
     * quand aucun écran n'est affiché : la session continue alors, le framebuffer se met à jour, et l'écran affichera
     * l'état courant en s'attachant (redessin complet).
     */
    fun setRenderTarget(target: RenderTarget?) {
        renderTarget = target
    }

    /**
     * Lance une connexion vers [params]. Ne bloque pas.
     *
     * @param password mot de passe VNC, ou `null`/vide si le serveur n'en demande pas ; le tableau est **effacé**.
     * @param autoReconnect se reconnecter tout seul si la session établie est coupée (SS-055) ; **garde alors une copie du
     *   mot de passe en mémoire** jusqu'à la fin (voir la description de la classe).
     * @return `false` (et rien n'est fait) si une connexion est déjà en cours ou établie.
     */
    fun connect(params: ConnectionParams, password: CharArray? = null, autoReconnect: Boolean = false): Boolean =
        start(params, password, reconnecting = false, autoReconnect = autoReconnect)

    /**
     * Relance la connexion vers la dernière destination (SS-054), sans redémarrer l'application. Mêmes règles que
     * [connect]. Un mot de passe est nécessaire si [reconnectNeedsPassword].
     * @return `false` si une connexion est en cours ou s'il n'y a pas de dernière destination.
     */
    fun reconnect(password: CharArray? = null, autoReconnect: Boolean = false): Boolean {
        val params = synchronized(lock) { lastParams }
        if (params == null) {
            password?.fill('\u0000')
            return false
        }
        return start(params, password, reconnecting = true, autoReconnect = autoReconnect)
    }

    /** Saute l'attente de la reconnexion automatique et retente tout de suite. Sans effet hors attente. */
    fun retryNow() {
        synchronized(lock) {
            if (currentState == ConnectionState.RECONNECTING && currentReconnectStatus?.waiting == true) {
                retryNowRequested = true
                lock.notifyAll()
            }
        }
    }

    /**
     * Renonce à la reconnexion automatique : passe en [ConnectionState.ERROR] avec la cause de la coupure (l'écran propose
     * alors Reconnecter). Sans effet hors reconnexion automatique.
     */
    fun stopAutoReconnect() {
        synchronized(lock) {
            if (currentState == ConnectionState.RECONNECTING && currentReconnectStatus != null) {
                stopAutoRequested = true
                lock.notifyAll()
            }
        }
    }

    /**
     * Ferme la connexion (ou abandonne la tentative en cours) et passe à [ConnectionState.DISCONNECTED]. Ne bloque pas
     * sur le réseau ; idempotent.
     */
    fun disconnect() {
        val socket: RfbSocket?
        val sender: PointerSender?
        val notify: Boolean
        synchronized(lock) {
            generation++ // le thread en cours n'a plus le droit de rien publier
            socket = activeSocket
            activeSocket = null
            sender = sessionSender
            sessionSender = null
            currentSession = null
            wipeRetainedLocked() // le mot de passe gardé pour la reconnexion ne survit pas à l'arrêt
            currentReconnectStatus = null
            gaveUp = 0
            lock.notifyAll() // réveille une attente de reconnexion : elle constate le changement de génération
            notify = currentState != ConnectionState.DISCONNECTED
            currentState = ConnectionState.DISCONNECTED
            currentFailure = null
        }
        socket?.close() // débloque la lecture ou l'écriture en cours du thread de session
        sender?.stop()
        if (notify) notifyListeners(ConnectionState.DISCONNECTED, null)
    }

    /** Attend la fin du thread de session (tests, arrêt de l'application) ; `true` s'il est terminé. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val thread = synchronized(lock) { sessionThread } ?: return true
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    // ------------------------------------------------------------------ lancement

    private fun start(params: ConnectionParams, password: CharArray?, reconnecting: Boolean, autoReconnect: Boolean): Boolean {
        val gen: Long
        synchronized(lock) {
            if (!currentState.canStartConnection) {
                password?.fill('\u0000')
                return false
            }
            generation++
            gen = generation
            lastParams = params
            currentSession = null
            serverWantsPassword = false // redécouvert à la négociation : le nouveau serveur n'est peut-être pas le même
            updates.set(0)
            currentFailure = null
            currentReconnectStatus = null
            gaveUp = 0
            retryNowRequested = false
            stopAutoRequested = false
            wipeRetainedLocked()
            // Copie gardée en mémoire pour se reconnecter sans l'utilisateur : seulement s'il y a un mot de passe et que la
            // reconnexion automatique est demandée. Chaque tentative en reçoit sa propre copie.
            if (autoReconnect && password != null && password.isNotEmpty()) retainedPassword = password.copyOf()
        }
        // L'état est publié avant le démarrage du thread : l'appelant le voit déjà en revenant.
        publish(gen, if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING, null)

        val thread = Thread({ runChain(gen, params, password, autoReconnect) }, THREAD_NAME)
        thread.isDaemon = true
        synchronized(lock) { sessionThread = thread }
        thread.start()
        return true
    }

    // ------------------------------------------------------------------ thread de session

    /** Résultat d'une tentative : l'échec (ou `null` si annulée), et combien de temps la session a tenu. */
    private class Outcome(val failure: ConnectionFailure?, val wasConnected: Boolean, val connectedMs: Long)

    /**
     * Boucle de la connexion (SS-054) et de la **reconnexion automatique** (SS-055) : une tentative, puis, si elle échoue pour
     * une cause passagère après avoir été établie, l'attente et une nouvelle tentative, dans la limite de
     * [ConnectionConfig.reconnectDelaysMs]. Sans reconnexion automatique, une seule tentative.
     */
    private fun runChain(gen: Long, params: ConnectionParams, initialPassword: CharArray?, auto: Boolean) {
        var first = initialPassword
        var inOutage = false
        var failedAttempts = 0
        val delays = config.reconnectDelaysMs
        try {
            while (true) {
                val password = first ?: retainedCopy()
                first = null
                val outcome = attemptOnce(gen, params, password)
                if (!isCurrent(gen)) return // disconnect() : plus rien à publier
                val failure = outcome.failure ?: return

                if (outcome.wasConnected && outcome.connectedMs >= config.reconnectStableMs) failedAttempts = 0
                val retry = auto && delays.isNotEmpty() && failure.kind.isTransient && (inOutage || outcome.wasConnected)
                if (!retry) {
                    giveUp(gen, failure, 0)
                    return
                }
                inOutage = true
                failedAttempts++
                if (failedAttempts > delays.size) {
                    giveUp(gen, failure, delays.size) // abandon : le compteur est borné
                    return
                }
                val delay = delays[failedAttempts - 1]
                val waiting = ReconnectStatus(failedAttempts, delays.size, delay, System.nanoTime(), waiting = true)
                if (!publish(gen, ConnectionState.RECONNECTING, failure) { currentReconnectStatus = waiting }) return
                if (!waitBeforeRetry(gen, delay)) {
                    if (isCurrent(gen)) giveUp(gen, failure, 0) // arrêtée par l'utilisateur : ERROR avec la cause
                    return
                }
                val attempting = ReconnectStatus(failedAttempts, delays.size, delay, System.nanoTime(), waiting = false)
                if (!publish(gen, ConnectionState.RECONNECTING, failure) { currentReconnectStatus = attempting }) return
            }
        } finally {
            synchronized(lock) { if (generation == gen) wipeRetainedLocked() } // fin de la chaîne : plus de mot de passe gardé
        }
    }

    /** Publie l'échec définitif et efface le mot de passe gardé. */
    private fun giveUp(gen: Long, failure: ConnectionFailure, attempts: Int) {
        publish(gen, ConnectionState.ERROR, failure) {
            currentReconnectStatus = null
            gaveUp = attempts
            wipeRetainedLocked()
        }
    }

    /**
     * Attend [delayMs] avant la tentative suivante, interruptible : [disconnect], [retryNow] et [stopAutoReconnect] réveillent
     * l'attente.
     * @return `true` pour tenter maintenant ; `false` si la reconnexion est annulée ou arrêtée.
     */
    private fun waitBeforeRetry(gen: Long, delayMs: Long): Boolean {
        val deadline = System.nanoTime() + delayMs * 1_000_000L
        synchronized(lock) {
            try {
                while (generation == gen && !stopAutoRequested && !retryNowRequested) {
                    val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0) break
                    lock.wait(remainingMs)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            retryNowRequested = false
            return generation == gen && !stopAutoRequested
        }
    }

    private fun retainedCopy(): CharArray? = synchronized(lock) { retainedPassword?.copyOf() }

    /** Efface et oublie le mot de passe gardé pour la reconnexion. Appelé avec [lock] tenu. */
    private fun wipeRetainedLocked() {
        retainedPassword?.fill('\u0000')
        retainedPassword = null
    }

    /** Une tentative de connexion complète : ouverture, négociation, puis session jusqu'à sa fin. */
    private fun attemptOnce(gen: Long, params: ConnectionParams, password: CharArray?): Outcome {
        val passwordGiven = password != null && password.isNotEmpty()
        val socket = try {
            socketFactory()
        } catch (t: Throwable) {
            password?.fill('\u0000')
            return Outcome(ConnectionFailure.classify(t, Phase.CONNECTING, passwordGiven), false, 0)
        }
        val registered = synchronized(lock) {
            if (generation == gen) {
                activeSocket = socket
                true
            } else {
                false
            }
        }
        if (!registered) { // disconnect() est passé entre-temps
            socket.close()
            password?.fill('\u0000')
            return Outcome(null, false, 0)
        }

        socket.traffic = perf?.traffic
        var phase = Phase.CONNECTING
        var vnc: VncAuthentication? = null
        var keepAlive: KeepAlive? = null
        var sender: PointerSender? = null
        var connectedAtNs = 0L
        var failure: ConnectionFailure? = null
        try {
            socket.connect(params.host, params.port)
            phase = Phase.NEGOTIATING
            publish(gen, ConnectionState.NEGOTIATING, null)

            val version = ProtocolVersion.negotiate(socket)
            val handlers = ArrayList<SecurityHandler>(2)
            if (passwordGiven) {
                val auth = VncAuthentication(password!!) // efface `password` dès la clé dérivée
                vnc = auth
                handlers += auth
            }
            handlers += NoneSecurity
            val security = SecurityNegotiation.negotiate(socket, version, handlers)
            if (security.type == SecurityType.VNC_AUTH) markServerWantsPassword(gen)
            val server = InitExchange.perform(socket, params.shared)

            val framebuffer = Framebuffer(server.width, server.height)
            sendSetup(socket, framebuffer, params.encodingMode)
            socket.setReadTimeout(config.readTimeoutMs)

            val info = SessionInfo(version, security.type, server, framebuffer)
            val reader = ServerMessageReader(socket, framebuffer, PixelFormat.XRGB_8888_LE, listener = forwarder(gen), perf = perf)
            phase = Phase.RUNNING
            sender = PointerSender.forSocket(socket)
            updates.set(0) // compteur de la nouvelle session
            if (!establish(gen, info, sender)) return Outcome(null, false, 0) // annulé pendant la négociation
            connectedAtNs = System.nanoTime()

            keepAlive = startKeepAlive(socket)
            readLoop(gen, socket, reader, framebuffer)
        } catch (t: Throwable) {
            failure = ConnectionFailure.classify(t, phase, passwordGiven)
            if (failure.kind == FailureKind.AUTH_FAILED || failure.kind == FailureKind.PASSWORD_REQUIRED) {
                markServerWantsPassword(gen)
            }
        } finally {
            keepAlive?.stop()
            sender?.stop()
            vnc?.close() // efface la clé si le serveur n'a pas choisi VNC Authentication
            password?.fill('\u0000')
            socket.close()
            synchronized(lock) {
                if (activeSocket === socket) activeSocket = null
                if (sessionSender === sender) sessionSender = null
            }
        }
        val connectedMs = if (connectedAtNs == 0L) 0L else (System.nanoTime() - connectedAtNs) / 1_000_000L
        return Outcome(failure, connectedAtNs != 0L, connectedMs)
    }

    /** Envoie la configuration de la session : format de pixels imposé, encodages, première image complète. */
    private fun sendSetup(socket: RfbSocket, framebuffer: Framebuffer, encodingMode: EncodingMode) {
        for (message in listOf(
            ClientMessages.setPixelFormat(PixelFormat.XRGB_8888_LE),
            ClientMessages.setEncodings(encodingMode.encodings),
            ClientMessages.framebufferUpdateRequest(false, 0, 0, framebuffer.width, framebuffer.height)
        )) {
            socket.write(message, 0, message.size)
        }
    }

    /**
     * Publie la session établie ; `false` si la tentative a été annulée entre-temps. Le passage à CONNECTED, la session
     * et l'envoyeur d'entrées sont posés **d'un seul geste** : un [disconnect] les voit tous ou aucun.
     */
    private fun establish(gen: Long, info: SessionInfo, sender: PointerSender): Boolean =
        publish(gen, ConnectionState.CONNECTED, null) {
            currentSession = info
            currentReconnectStatus = null
            gaveUp = 0
            sessionSender = sender
            sender.start() // ne bloque pas : démarre seulement le thread d'envoi
        }

    private fun readLoop(gen: Long, socket: RfbSocket, reader: ServerMessageReader, framebuffer: Framebuffer) {
        // Requête de la zone entière, construite une fois : aucune allocation par mise à jour.
        val nextRequest = ClientMessages.framebufferUpdateRequest(true, 0, 0, framebuffer.width, framebuffer.height)
        var lastReceivedNs = System.nanoTime()

        while (isCurrent(gen)) {
            val message = try {
                reader.readMessage()
            } catch (e: RfbTransportException.ReadTimeout) {
                // Silence à la frontière d'un message : normal tant que le serveur n'a rien à dire. Au milieu d'un
                // message la socket est déjà fermée : c'est une vraie erreur.
                if (socket.isClosed) throw e
                val silentMs = (System.nanoTime() - lastReceivedNs) / 1_000_000L
                if (silentMs > config.livenessTimeoutMs) throw e
                perf?.recordSessionThread()
                continue
            }
            lastReceivedNs = System.nanoTime()
            if (message is ServerMessage.FramebufferUpdated) {
                updates.incrementAndGet()
                if (isCurrent(gen)) renderTarget?.onFramebufferUpdated()
                socket.write(nextRequest, 0, nextRequest.size)
            }
            perf?.recordSessionThread() // processeur et allocations du thread de session (décodage + rendu), si mesuré
        }
    }

    /**
     * Battement Wi-Fi + signe de vie : une requête incrémentale d'un pixel à chaque tick, et une **non incrémentale**
     * (à laquelle le serveur répond toujours) tous les [ConnectionConfig.heartbeatIntervalMs].
     */
    private fun startKeepAlive(socket: RfbSocket): KeepAlive {
        val pulse = ClientMessages.keepAliveRequest()
        val proof = ClientMessages.framebufferUpdateRequest(false, 0, 0, 1, 1)
        val ticksPerProof = maxOf(1L, config.heartbeatIntervalMs / config.keepAliveIntervalMs)
        var tick = 0L
        val keepAlive = KeepAlive(
            config.keepAliveIntervalMs,
            onError = { socket.close() } // la boucle de lecture le constate et classe l'erreur
        ) {
            tick++
            val message = if (tick % ticksPerProof == 0L) proof else pulse
            socket.write(message, 0, message.size)
        }
        keepAlive.start()
        return keepAlive
    }

    // ------------------------------------------------------------------ état

    /** Transmet les rectangles décodés à l'écran, sauf si cette session n'est plus la courante. */
    private fun forwarder(gen: Long) = object : RectangleListener {
        override fun onRectangle(x: Int, y: Int, w: Int, h: Int) {
            if (isCurrent(gen)) renderTarget?.rectangleListener?.onRectangle(x, y, w, h)
        }
    }

    private fun isCurrent(gen: Long) = synchronized(lock) { generation == gen }

    private fun markServerWantsPassword(gen: Long) {
        synchronized(lock) { if (generation == gen) serverWantsPassword = true }
    }

    /**
     * Passe à [next] si [gen] est toujours la tentative courante et si la transition est légale, puis notifie.
     * @return `false` si la tentative n'est plus la courante.
     */
    private fun publish(
        gen: Long,
        next: ConnectionState,
        failure: ConnectionFailure?,
        whileLocked: (() -> Unit)? = null
    ): Boolean {
        synchronized(lock) {
            if (generation != gen) return false
            if (currentState != next && !currentState.canTransitionTo(next)) return false
            currentState = next
            currentFailure = failure
            if (next != ConnectionState.CONNECTED) currentSession = null
            whileLocked?.invoke()
        }
        notifyListeners(next, failure)
        return true
    }

    private fun notifyListeners(state: ConnectionState, failure: ConnectionFailure?) {
        for (listener in listeners) {
            try {
                listener.onStateChanged(state, failure)
            } catch (ignored: Throwable) {
                // un écouteur défaillant ne doit pas interrompre la session ni les autres écouteurs
            }
        }
    }

    private companion object {
        const val THREAD_NAME = "secondscreen-session"
    }
}
