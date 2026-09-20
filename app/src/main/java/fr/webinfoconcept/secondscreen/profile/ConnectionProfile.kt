package fr.webinfoconcept.secondscreen.profile

import fr.webinfoconcept.secondscreen.session.ConnectionParams

/**
 * Un PC enregistré (SS-051) : nom, hôte, port. **Aucun champ de mot de passe** : il est saisi à chaque connexion
 * (SECURITY.md) et n'existe dans aucune structure persistante de l'application.
 */
data class ConnectionProfile(val id: Long, val name: String, val host: String, val port: Int) {
    /** La destination de ce profil. */
    fun toParams(): ConnectionParams = ConnectionParams(host, port)

    /** Libellé de liste : `nom — hôte:port`. */
    fun label(): String = "$name — $host:$port"
}

/** Erreur de saisie d'un champ du formulaire de connexion (SS-050). */
enum class FieldError { NAME_TOO_LONG, NAME_INVALID, HOST_EMPTY, HOST_INVALID, PORT_INVALID }

/**
 * Validation du formulaire de connexion (SS-050, SS-051), sans Android : les textes saisis sont normalisés ici, et
 * les erreurs sont désignées par champ pour que l'écran les affiche au bon endroit.
 */
class ProfileForm private constructor(
    /** Les valeurs normalisées si la saisie est valide, sinon `null`. */
    val name: String?,
    val host: String?,
    val port: Int?,
    /** Une erreur au plus par champ. */
    val nameError: FieldError?,
    val hostError: FieldError?,
    val portError: FieldError?
) {
    val isValid: Boolean
        get() = nameError == null && hostError == null && portError == null

    companion object {
        const val MAX_NAME_LENGTH = 40

        /**
         * @param nameText nom saisi ; vide = l'hôte sert de nom. Espaces de bord retirés ; ni retour à la ligne ni
         *   caractère de contrôle ; au plus [MAX_NAME_LENGTH] caractères.
         * @param hostText hôte saisi ; espaces de bord retirés (un espace collé avec l'adresse est une faute de frappe,
         *   pas une adresse invalide).
         * @param portText port saisi ; vide = 5900.
         */
        fun validate(nameText: String, hostText: String, portText: String): ProfileForm {
            val host = hostText.trim()
            val hostError = when {
                host.isEmpty() -> FieldError.HOST_EMPTY
                !ConnectionParams.isValidHost(host) -> FieldError.HOST_INVALID
                else -> null
            }
            val port = ConnectionParams.parsePort(portText)
            val portError = if (port == null) FieldError.PORT_INVALID else null

            val trimmedName = nameText.trim()
            val name = trimmedName.ifEmpty { host }
            val nameError = when {
                trimmedName.any { it.isISOControl() } -> FieldError.NAME_INVALID
                name.length > MAX_NAME_LENGTH -> FieldError.NAME_TOO_LONG
                else -> null
            }
            return ProfileForm(
                name = if (nameError == null && hostError == null) name else null,
                host = if (hostError == null) host else null,
                port = port,
                nameError = nameError,
                hostError = hostError,
                portError = portError
            )
        }
    }
}
