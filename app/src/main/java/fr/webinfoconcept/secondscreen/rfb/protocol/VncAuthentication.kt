package fr.webinfoconcept.secondscreen.rfb.protocol

import android.annotation.SuppressLint
import fr.webinfoconcept.secondscreen.rfb.transport.RfbSocket
import java.io.Closeable
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC Authentication, type de sécurité 2 (SS-014, RFC 6143 §7.2.2).
 *
 * Le serveur envoie un challenge de 16 octets ; le client renvoie ce challenge chiffré en
 * **DES-ECB** (deux blocs de 8 octets indépendants) avec pour clé le mot de passe :
 * tronqué à 8 caractères, complété par des octets nuls, et dont **chaque octet a ses bits
 * inversés** (particularité historique de VNC).
 *
 * **Sécurité — à lire avant de s'en servir** : ce schéma est faible (DES, 8 caractères au
 * plus, challenge/réponse attaquable hors ligne) et la session reste **non chiffrée**. Il ne
 * convient qu'à un LAN de confiance (SECURITY.md) ; hors LAN, passer par un VPN ou un tunnel.
 *
 * **Aucune trace du mot de passe** :
 * - [password] est **effacé par le constructeur** (le tableau de l'appelant devient des zéros) ;
 *   la seule copie conservée est la clé DES dérivée, effacée dès l'authentification faite ou
 *   à la fermeture ;
 * - ni journal, ni message d'exception, ni `toString()` ne contient de secret ;
 * - challenge et réponse sont effacés après usage. Meilleur effort : la couche JCA
 *   (`SecretKeySpec`) garde sa propre copie de la clé que l'on ne peut pas effacer, et la
 *   JVM peut avoir copié des tableaux avant leur effacement.
 *
 * Usage unique : un handler = une tentative. Après échec, en créer un nouveau avec un mot de
 * passe ressaisi (SECURITY.md : saisie à chaque connexion). Appeler [close] si la négociation
 * n'a pas atteint [authenticate] (ex. le serveur n'a pas proposé ce type).
 *
 * @param password mot de passe, 1 caractère ou plus, chaque caractère dans Latin-1 (U+0000..U+00FF).
 *   Au-delà de [MAX_PASSWORD_LENGTH] caractères, le surplus est ignoré, comme le font les serveurs VNC.
 * @throws IllegalArgumentException mot de passe vide ou caractère hors Latin-1. Le message ne
 *   révèle aucun caractère du mot de passe.
 */
class VncAuthentication(password: CharArray) : SecurityHandler, Closeable {

    override val type: Int = SecurityType.VNC_AUTH

    // Protégé par `this`. Passe à null dès que la clé a été consommée ou effacée.
    private var key: ByteArray? = null

    init {
        try {
            key = deriveKey(password)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Lit le challenge, envoie la réponse. Bloquant.
     *
     * @throws IllegalStateException handler déjà utilisé ou fermé.
     * @throws RfbProtocolException.DesUnavailable DES absent de cet appareil.
     * @throws fr.webinfoconcept.secondscreen.rfb.transport.RfbTransportException erreur réseau/timeout/EOF.
     */
    override fun authenticate(socket: RfbSocket) {
        val des = synchronized(this) {
            val k = key ?: throw IllegalStateException("VncAuthentication déjà utilisée ou fermée")
            key = null // usage unique : la clé ne peut plus servir
            k
        }
        val challenge = ByteArray(CHALLENGE_LENGTH)
        var response: ByteArray? = null
        try {
            socket.readFully(challenge, 0, CHALLENGE_LENGTH)
            response = encryptChallenge(des, challenge)
            socket.write(response, 0, response.size)
        } finally {
            des.fill(0)
            challenge.fill(0)
            response?.fill(0)
        }
    }

    /** Efface la clé si elle n'a pas été consommée. Idempotent. */
    override fun close() {
        synchronized(this) {
            key?.fill(0)
            key = null
        }
    }

    /** La clé interne, pour vérifier son effacement dans les tests. `null` une fois consommée/fermée. */
    internal fun keyReferenceForTest(): ByteArray? = synchronized(this) { key }

    // Pas de data class ni de toString() : rien du contenu ne doit pouvoir être imprimé.

    companion object {
        /** Taille du challenge (2 blocs DES de 8 octets) et de la réponse. */
        const val CHALLENGE_LENGTH = 16

        /** Le protocole n'utilise que les 8 premiers caractères du mot de passe. */
        const val MAX_PASSWORD_LENGTH = 8

        private const val TRANSFORMATION = "DES/ECB/NoPadding"

        /**
         * Clé DES de 8 octets : les [MAX_PASSWORD_LENGTH] premiers caractères en Latin-1, complétés
         * de zéros, chaque octet en miroir binaire (bit 0 <-> bit 7).
         */
        internal fun deriveKey(password: CharArray): ByteArray {
            require(password.isNotEmpty()) { "mot de passe vide" }
            val key = ByteArray(MAX_PASSWORD_LENGTH)
            try {
                for (i in 0 until minOf(password.size, MAX_PASSWORD_LENGTH)) {
                    val c = password[i].code
                    // Ne jamais citer le caractère fautif : c'est un secret.
                    require(c <= 0xFF) { "caractère hors Latin-1 dans le mot de passe" }
                    key[i] = reverseBits(c).toByte()
                }
            } catch (e: IllegalArgumentException) {
                key.fill(0)
                throw e
            }
            return key
        }

        /** Miroir des 8 bits de poids faible : 0b00000001 -> 0b10000000. */
        private fun reverseBits(value: Int): Int = Integer.reverse(value) ushr 24

        /**
         * Chiffre [challenge] (multiple de 8 octets) en DES-ECB avec [key] (8 octets).
         *
         * Lint (`GetInstance`) déconseille ECB en général. Ici DES-ECB est **imposé par le
         * protocole** VNC Authentication : en changer le mode casserait la compatibilité avec
         * tous les serveurs. La faiblesse est connue et documentée sur la classe.
         */
        @SuppressLint("GetInstance")
        internal fun encryptChallenge(key: ByteArray, challenge: ByteArray): ByteArray {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                return cipher.doFinal(challenge)
            } catch (e: GeneralSecurityException) {
                throw RfbProtocolException.DesUnavailable(e)
            }
        }
    }
}
