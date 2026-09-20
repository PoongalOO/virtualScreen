package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.input.MessageSink
import fr.webinfoconcept.secondscreen.input.PointerSender
import fr.webinfoconcept.secondscreen.net.KeepAlive
import fr.webinfoconcept.secondscreen.render.RenderTarget
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages
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
    val heartbeatIntervalMs: Long = 5_000
) {
    init {
        require(connectTimeoutMs > 0 && handshakeTimeoutMs > 0 && readTimeoutMs > 0) { "délais > 0" }
        require(livenessTimeoutMs > readTimeoutMs) { "le délai de silence doit dépasser le réveil de lecture" }
        require(heartbeatIntervalMs >= keepAliveIntervalMs) { "battement de vie plus rapide que le battement Wi-Fi" }
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
 * dans tous les cas (connexion refusée, échec, succès). Il n'est **pas conservé** pour une reconnexion : [reconnect] en
 * redemande un si le serveur en exigeait un ([reconnectNeedsPassword]). Rien n'est journalisé.
 *
 * ## Sessions successives
 * Chaque tentative a un numéro de génération. Un ancien thread encore en train de se terminer ne peut plus rien publier
 * (état, framebuffer, rendu) une fois qu'une nouvelle tentative ou un [disconnect] a eu lieu.
 *
 * @param socketFactory fabrique de la socket, injectable pour les tests.
 */
class ConnectionController(
    private val config: ConnectionConfig = ConnectionConfig(),
    private val socketFactory: () -> RfbSocket = { RfbSocket(config.connectTimeoutMs, config.handshakeTimeoutMs) }
) {
    /** Notifié de chaque changement d'état, sur un thread quelconque. */
    interface Listener {
        fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?)
    }

    private val lock = Any()
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

    /** Pourquoi la connexion a échoué ; non nul seulement en état [ConnectionState.ERROR]. */
    val failure: ConnectionFailure?
        get() = synchronized(lock) { currentFailure }

    /** La session établie, ou `null` hors état [ConnectionState.CONNECTED]. */
    val session: SessionInfo?
        get() = synchronized(lock) { currentSession }

    /** Nombre de `FramebufferUpdate` reçus depuis l'établissement de la dernière session. */
    val updateCount: Long
        get() = updates.get()

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
     * @return `false` (et rien n'est fait) si une connexion est déjà en cours ou établie.
     */
    fun connect(params: ConnectionParams, password: CharArray? = null): Boolean =
        start(params, password, reconnecting = false)

    /**
     * Relance la connexion vers la dernière destination (SS-054), sans redémarrer l'application. Mêmes règles que
     * [connect]. Un mot de passe est nécessaire si [reconnectNeedsPassword].
     * @return `false` si une connexion est en cours ou s'il n'y a pas de dernière destination.
     */
    fun reconnect(password: CharArray? = null): Boolean {
        val params = synchronized(lock) { lastParams }
        if (params == null) {
            password?.fill('\u0000')
            return false
        }
        return start(params, password, reconnecting = true)
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

    private fun start(params: ConnectionParams, password: CharArray?, reconnecting: Boolean): Boolean {
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
        }
        // L'état est publié avant le démarrage du thread : l'appelant le voit déjà en revenant.
        publish(gen, if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING, null)

        val thread = Thread({ run(gen, params, password) }, THREAD_NAME)
        thread.isDaemon = true
        synchronized(lock) { sessionThread = thread }
        thread.start()
        return true
    }

    // ------------------------------------------------------------------ thread de session

    private fun run(gen: Long, params: ConnectionParams, password: CharArray?) {
        val passwordGiven = password != null && password.isNotEmpty()
        val socket = try {
            socketFactory()
        } catch (t: Throwable) {
            password?.fill('\u0000')
            fail(gen, ConnectionFailure.classify(t, Phase.CONNECTING, passwordGiven))
            return
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
            return
        }

        var phase = Phase.CONNECTING
        var vnc: VncAuthentication? = null
        var keepAlive: KeepAlive? = null
        var sender: PointerSender? = null
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
            sendSetup(socket, framebuffer)
            socket.setReadTimeout(config.readTimeoutMs)

            val info = SessionInfo(version, security.type, server, framebuffer)
            val reader = ServerMessageReader(socket, framebuffer, PixelFormat.XRGB_8888_LE, listener = forwarder(gen))
            phase = Phase.RUNNING
            sender = PointerSender.forSocket(socket)
            if (!establish(gen, info, sender)) return // annulé pendant la négociation

            keepAlive = startKeepAlive(socket)
            readLoop(gen, socket, reader, framebuffer)
        } catch (t: Throwable) {
            val failure = ConnectionFailure.classify(t, phase, passwordGiven)
            if (failure.kind == FailureKind.AUTH_FAILED || failure.kind == FailureKind.PASSWORD_REQUIRED) {
                markServerWantsPassword(gen)
            }
            fail(gen, failure)
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
    }

    /** Envoie la configuration de la session : format de pixels imposé, encodages, première image complète. */
    private fun sendSetup(socket: RfbSocket, framebuffer: Framebuffer) {
        for (message in listOf(
            ClientMessages.setPixelFormat(PixelFormat.XRGB_8888_LE),
            ClientMessages.setEncodings(),
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
                continue
            }
            lastReceivedNs = System.nanoTime()
            if (message is ServerMessage.FramebufferUpdated) {
                updates.incrementAndGet()
                if (isCurrent(gen)) renderTarget?.onFramebufferUpdated()
                socket.write(nextRequest, 0, nextRequest.size)
            }
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

    private fun fail(gen: Long, failure: ConnectionFailure) {
        publish(gen, ConnectionState.ERROR, failure) // l'envoyeur de la session est arrêté par le `finally` de run()
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
