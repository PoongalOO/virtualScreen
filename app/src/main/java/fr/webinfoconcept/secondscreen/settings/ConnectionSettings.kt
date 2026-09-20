package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.profile.KeyValueStore

/**
 * Réglages de connexion mémorisés (SS-055). Aucune donnée sensible : **jamais de mot de passe ici**.
 *
 * @property autoReconnect se reconnecter tout seul quand une session établie est coupée. Vrai par défaut. **Attention** : pour
 *   un serveur protégé, cela oblige l'application à garder une copie du mot de passe **en mémoire** (jamais sur le
 *   stockage) jusqu'à la fin de la session, ce que le mode manuel évite ; l'écran de connexion le dit à côté de la case
 *   (SECURITY.md).
 */
class ConnectionSettings(private val store: KeyValueStore) {

    var autoReconnect: Boolean
        get() = store.all()[KEY_AUTO_RECONNECT] != FALSE // absent ou illisible : le défaut, activé
        set(value) = store.apply(mapOf(KEY_AUTO_RECONNECT to if (value) TRUE else FALSE))

    companion object {
        /** Nom du fichier de préférences (distinct de ceux des profils, de l'affichage et des entrées). */
        const val FILE_NAME = "connection_settings"

        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
