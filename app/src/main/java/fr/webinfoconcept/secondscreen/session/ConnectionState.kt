package fr.webinfoconcept.secondscreen.session

/**
 * États d'une connexion (ARCHITECTURE.md, « Gestion d'état »). Toutes les transitions passent par le
 * [ConnectionController] : ni l'Activity ni personne d'autre ne touche la socket.
 *
 * ```text
 *                 connect()                       TCP ouvert                 ServerInit reçu
 * DISCONNECTED ───────────────► CONNECTING ─────────────────► NEGOTIATING ─────────────────► CONNECTED
 *      ▲  ▲                          │                             │                             │
 *      │  └──── reconnect() ─► RECONNECTING (même chemin que CONNECTING, après une session)      │
 *      │                             ▼                             ▼                             ▼
 *      └──── disconnect() ────────  ERROR  ◄──────────────────── (échec, voir [ConnectionFailure]) ┘
 * ```
 */
enum class ConnectionState {
    /** Aucune connexion : état initial, ou après [ConnectionController.disconnect]. */
    DISCONNECTED,

    /** Ouverture de la connexion TCP. */
    CONNECTING,

    /** TCP ouvert : version, sécurité, authentification et `ServerInit`. */
    NEGOTIATING,

    /** Session établie : l'écran distant est affiché et les entrées sont envoyées. */
    CONNECTED,

    /**
     * Comme [CONNECTING], mais après une session : lancé par [ConnectionController.reconnect], ou **automatiquement**
     * (SS-055) quand la connexion est coupée : le contrôleur attend puis retente ([ConnectionController.reconnectStatus]
     * dit où il en est).
     */
    RECONNECTING,

    /** La connexion a échoué ou s'est interrompue ; [ConnectionController.failure] dit pourquoi. */
    ERROR;

    /** `true` tant qu'une connexion est en cours d'établissement ou établie (on ne peut pas en lancer une autre). */
    val isActive: Boolean
        get() = this == CONNECTING || this == NEGOTIATING || this == CONNECTED || this == RECONNECTING

    /** `true` si l'on peut lancer une connexion depuis cet état. */
    val canStartConnection: Boolean
        get() = this == DISCONNECTED || this == ERROR

    /** Transitions autorisées depuis cet état (`this -> next`). Rester dans le même état n'est pas une transition. */
    fun canTransitionTo(next: ConnectionState): Boolean = when (this) {
        DISCONNECTED -> next == CONNECTING || next == RECONNECTING
        CONNECTING, RECONNECTING -> next == NEGOTIATING || next == ERROR || next == DISCONNECTED
        // Reconnexion automatique (SS-055) : une session ou une tentative qui échoue provisoirement repasse en RECONNECTING.
        NEGOTIATING -> next == CONNECTED || next == ERROR || next == DISCONNECTED || next == RECONNECTING
        CONNECTED -> next == ERROR || next == DISCONNECTED || next == RECONNECTING
        ERROR -> next == CONNECTING || next == RECONNECTING || next == DISCONNECTED
    }
}
