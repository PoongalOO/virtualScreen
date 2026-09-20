package fr.webinfoconcept.secondscreen.session

import fr.webinfoconcept.secondscreen.rfb.protocol.RfbProtocolException
import fr.webinfoconcept.secondscreen.rfb.protocol.SecurityType
import fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException

/** Où en était la connexion quand elle a échoué : le même symptôme n'a pas le même sens partout. */
enum class Phase {
    /** Ouverture TCP. */
    CONNECTING,

    /** Version, sécurité, authentification, `ServerInit`. */
    NEGOTIATING,

    /** Session établie. */
    RUNNING
}

/** Cause d'un échec, en catégories que l'utilisateur peut comprendre et sur lesquelles il peut agir (SS-053). */
enum class FailureKind {
    /** Le nom d'hôte n'a pas pu être résolu. */
    UNKNOWN_HOST,

    /** Pas de réponse à l'ouverture TCP dans le délai. */
    CONNECT_TIMEOUT,

    /** Machine joignable mais rien n'écoute sur ce port. */
    CONNECTION_REFUSED,

    /** Réseau ou machine injoignable (Wi-Fi coupé, mauvaise adresse, pas de route). */
    NETWORK_UNREACHABLE,

    /** Un service répond mais ce n'est pas un serveur VNC. */
    NOT_A_VNC_SERVER,

    /** Le serveur parle une version de RFB trop ancienne. */
    UNSUPPORTED_VERSION,

    /** Le serveur refuse la connexion avant l'authentification ([ConnectionFailure.serverReason] : sa raison). */
    SERVER_REJECTED,

    /** Le serveur exige un mot de passe et aucun n'a été saisi. */
    PASSWORD_REQUIRED,

    /** Le mot de passe contient des caractères que VNC ne sait pas envoyer (hors Latin-1). */
    PASSWORD_INVALID,

    /** Le serveur n'offre aucun mode d'authentification que le client sait faire. */
    NO_COMPATIBLE_SECURITY,

    /** Mot de passe refusé par le serveur. */
    AUTH_FAILED,

    /** Le serveur n'a pas répondu pendant la négociation. */
    HANDSHAKE_TIMEOUT,

    /** Écran distant plus grand que ce que la tablette peut afficher (mémoire). */
    UNSUPPORTED_SERVER_SIZE,

    /** Connexion coupée par le serveur ou par le réseau (fin de flux, réinitialisation). */
    CONNECTION_LOST,

    /** Plus aucune nouvelle du serveur pendant la session : réseau coupé, PC éteint ou en veille. */
    NETWORK_LOST,

    /** Le serveur a envoyé des données que le client ne peut pas interpréter. */
    PROTOCOL_ERROR,

    /** Problème propre à la tablette (chiffrement DES absent, erreur interne). */
    LOCAL_ERROR
}

/**
 * `true` si l'échec peut disparaître tout seul et mérite une **nouvelle tentative automatique** (SS-055) : réseau ou PC
 * momentanément injoignable, connexion coupée, serveur qui redémarre, ancienne connexion que le serveur n'a pas encore
 * libérée (refus). `false` pour tout ce qu'une nouvelle tentative identique ne changerait pas : mot de passe absent, faux ou
 * invalide, ce n'est pas un serveur VNC, version ou authentification non prises en charge, écran trop grand, données
 * incohérentes, erreur locale.
 */
val FailureKind.isTransient: Boolean
    get() = when (this) {
        FailureKind.UNKNOWN_HOST, // le DNS échoue quand le Wi-Fi est coupé
        FailureKind.CONNECT_TIMEOUT,
        FailureKind.CONNECTION_REFUSED, // le serveur redémarre
        FailureKind.NETWORK_UNREACHABLE,
        FailureKind.SERVER_REJECTED, // l'ancienne connexion n'est pas encore libérée côté serveur
        FailureKind.HANDSHAKE_TIMEOUT,
        FailureKind.CONNECTION_LOST,
        FailureKind.NETWORK_LOST -> true
        FailureKind.NOT_A_VNC_SERVER,
        FailureKind.UNSUPPORTED_VERSION,
        FailureKind.PASSWORD_REQUIRED,
        FailureKind.PASSWORD_INVALID,
        FailureKind.NO_COMPATIBLE_SECURITY,
        FailureKind.AUTH_FAILED,
        FailureKind.UNSUPPORTED_SERVER_SIZE,
        FailureKind.PROTOCOL_ERROR,
        FailureKind.LOCAL_ERROR -> false
    }

/**
 * Pourquoi une connexion a échoué. Ne contient **jamais** de secret ni d'octets bruts du serveur ; la seule chaîne du
 * serveur, [serverReason], a été assainie (ASCII imprimable, longueur bornée) par la couche protocole.
 *
 * La mise en forme du message pour l'utilisateur est faite par l'interface (ressources de chaînes) à partir de
 * [kind] : cette classe n'a aucune dépendance Android et se teste sur la JVM.
 */
class ConnectionFailure(
    val kind: FailureKind,
    val phase: Phase,
    /** Raison donnée par le serveur (assainie), éventuellement vide. */
    val serverReason: String = ""
) {
    override fun toString(): String = "ConnectionFailure($kind, $phase)" // jamais la raison du serveur dans un journal

    /** Un nouveau mot de passe peut corriger l'échec : l'écran de connexion y remet le curseur. */
    val isPasswordProblem: Boolean
        get() = kind == FailureKind.AUTH_FAILED || kind == FailureKind.PASSWORD_REQUIRED ||
            kind == FailureKind.PASSWORD_INVALID

    companion object {
        /**
         * Classe une exception levée par le transport ou le protocole.
         *
         * @param phase où en était la connexion.
         * @param passwordGiven un mot de passe a été saisi : distingue « il en faut un » de « il est faux ».
         */
        fun classify(error: Throwable, phase: Phase, passwordGiven: Boolean): ConnectionFailure = when (error) {
            is RfbTransportException.UnknownHost -> ConnectionFailure(FailureKind.UNKNOWN_HOST, phase)
            is RfbTransportException.ConnectTimeout -> ConnectionFailure(FailureKind.CONNECT_TIMEOUT, phase)
            is RfbTransportException.ConnectionRefused -> ConnectionFailure(FailureKind.CONNECTION_REFUSED, phase)
            is RfbTransportException.ConnectFailed -> ConnectionFailure(FailureKind.NETWORK_UNREACHABLE, phase)
            is RfbTransportException.ReadTimeout -> ConnectionFailure(
                if (phase == Phase.RUNNING) FailureKind.NETWORK_LOST else FailureKind.HANDSHAKE_TIMEOUT, phase
            )
            is RfbTransportException -> ConnectionFailure(FailureKind.CONNECTION_LOST, phase) // fin de flux, E/S, fermée
            is RfbProtocolException.InvalidBanner -> ConnectionFailure(FailureKind.NOT_A_VNC_SERVER, phase)
            is RfbProtocolException.UnsupportedVersion -> ConnectionFailure(FailureKind.UNSUPPORTED_VERSION, phase)
            is RfbProtocolException.ConnectionRejected ->
                ConnectionFailure(FailureKind.SERVER_REJECTED, phase, error.reason)
            is RfbProtocolException.NoSupportedSecurityType ->
                if (!passwordGiven && error.offered.contains(SecurityType.VNC_AUTH.toLong())) {
                    ConnectionFailure(FailureKind.PASSWORD_REQUIRED, phase)
                } else {
                    ConnectionFailure(FailureKind.NO_COMPATIBLE_SECURITY, phase)
                }
            is RfbProtocolException.AuthenticationFailed ->
                ConnectionFailure(FailureKind.AUTH_FAILED, phase, error.reason)
            is RfbProtocolException.InvalidFramebufferSize ->
                ConnectionFailure(FailureKind.UNSUPPORTED_SERVER_SIZE, phase)
            is RfbProtocolException.DesUnavailable -> ConnectionFailure(FailureKind.LOCAL_ERROR, phase)
            is RfbProtocolException -> ConnectionFailure(FailureKind.PROTOCOL_ERROR, phase)
            is IllegalArgumentException ->
                // Seul cas connu : mot de passe hors Latin-1 (VncAuthentication). Le message ne contient aucun secret.
                ConnectionFailure(if (passwordGiven) FailureKind.PASSWORD_INVALID else FailureKind.LOCAL_ERROR, phase)
            else -> ConnectionFailure(FailureKind.LOCAL_ERROR, phase)
        }
    }
}
