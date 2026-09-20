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
}
