package fr.webinfoconcept.secondscreen.rfb.protocol

import java.io.IOException

/**
 * Erreurs de protocole RFB : le transport a fonctionné mais le serveur a envoyé
 * des données inattendues ou refusé la connexion (SS-011, SS-012). Distinctes de
 * [fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException].
 *
 * Les messages n'incluent jamais d'octets reçus du serveur (donnée non fiable).
 * Seule exception : le texte de raison des refus, exposé à part dans `reason`
 * après assainissement (ASCII imprimable uniquement, longueur bornée).
 */
sealed class RfbProtocolException(message: String) : IOException(message) {

    /**
     * La bannière de version n'est pas au format `RFB xxx.yyy\n`. Cause la plus
     * fréquente : port qui n'appartient pas à un serveur VNC.
     */
    class InvalidBanner :
        RfbProtocolException("Réponse invalide : ce port ne semble pas être un serveur VNC (RFB)")

    /** Version RFB annoncée par le serveur inférieure à la plus ancienne supportée (3.3). */
    class UnsupportedVersion(val major: Int, val minor: Int) :
        RfbProtocolException("Version RFB non supportée : $major.$minor")

    /**
     * Le serveur refuse la connexion avant toute authentification (liste de types
     * de sécurité vide, ou type 0). [reason] : texte du serveur, assaini, éventuellement vide.
     */
    class ConnectionRejected(val reason: String) :
        RfbProtocolException("Le serveur a refusé la connexion")

    /** Aucun des types de sécurité proposés par le serveur n'est supporté par le client. */
    class NoSupportedSecurityType(val offered: List<Long>) :
        RfbProtocolException("Aucun type de sécurité compatible : ${offered.joinToString(",")}")

    /**
     * Le serveur a rejeté l'authentification (`SecurityResult` = échec), typiquement un
     * mauvais mot de passe. [reason] : texte du serveur, assaini, éventuellement vide.
     */
    class AuthenticationFailed(val reason: String) :
        RfbProtocolException("Authentification refusée par le serveur")

    /** `SecurityResult` autre que 0 (succès) ou 1 (échec) : flux désaligné ou serveur non conforme. */
    class InvalidSecurityResult :
        RfbProtocolException("Réponse de sécurité invalide")

    /**
     * Dimensions du framebuffer annoncées par le serveur nulles ou supérieures aux limites
     * ([InitExchange.MAX_DIMENSION], [InitExchange.MAX_PIXELS]). Ce sont des entiers, pas du texte.
     */
    class InvalidFramebufferSize(val width: Int, val height: Int) :
        RfbProtocolException("Taille d'écran distant non supportée : ${width}x$height")

    /**
     * `PIXEL_FORMAT` du serveur incohérent. [detail] est un libellé fixe du client
     * (nom du champ fautif), jamais du texte reçu du serveur.
     */
    class InvalidPixelFormat(val detail: String) :
        RfbProtocolException("Format de pixels invalide envoyé par le serveur ($detail)")

    /** Longueur annoncée du nom du bureau supérieure à [InitExchange.MAX_NAME_LENGTH]. */
    class InvalidDesktopName(val announcedLength: Long) :
        RfbProtocolException("Nom du bureau distant trop long ($announcedLength octets)")

    /**
     * Un rectangle reçu du serveur sort du framebuffer (ou a des coordonnées/dimensions négatives).
     * Ce sont des entiers, pas du texte.
     */
    class RectangleOutOfBounds(val x: Int, val y: Int, val width: Int, val height: Int) :
        RfbProtocolException("Rectangle hors de l'écran distant : ($x,$y) ${width}x$height")

    /**
     * Type de message serveur que le client ne gère pas (dont `SetColourMapEntries`, jamais envoyé
     * à un client en couleurs vraies). Le flux ne peut plus être lu : la connexion est fermée.
     */
    class UnsupportedServerMessage(val type: Int) :
        RfbProtocolException("Message serveur non supporté (type $type)")

    /**
     * Rectangle dans un encodage que le client n'a pas annoncé ou ne sait pas décoder (S32 signé :
     * les pseudo-encodages sont négatifs). Le flux ne peut plus être lu : la connexion est fermée.
     */
    class UnsupportedEncoding(val encoding: Int) :
        RfbProtocolException("Encodage non supporté ($encoding)")

    /** Texte du presse-papiers distant (`ServerCutText`) plus long que la limite acceptée. */
    class CutTextTooLong(val announcedLength: Long) :
        RfbProtocolException("Texte du presse-papiers distant trop long ($announcedLength octets)")

    /**
     * Le chiffrement DES requis par VNC Authentication est indisponible sur cet appareil
     * (aucun fournisseur JCA). Problème local, pas du serveur.
     */
    class DesUnavailable(cause: Throwable) :
        RfbProtocolException("Le chiffrement DES nécessaire à l'authentification VNC est indisponible") {
        init {
            initCause(cause)
        }
    }
}
