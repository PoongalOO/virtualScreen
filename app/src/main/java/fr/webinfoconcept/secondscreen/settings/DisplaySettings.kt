package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.profile.KeyValueStore

/**
 * Réglages d'affichage mémorisés d'une session à l'autre. Aucune donnée sensible.
 *
 * @property fullscreen mode **plein écran** de l'écran distant : `false` par défaut.
 *
 * ## Pourquoi un mode explicite
 * Sur Android 4.2 il n'existe pas de mode immersif « collant » : tant que la barre système est masquée, **tout toucher
 * qui la fait réapparaître est annulé** (`ACTION_CANCEL`), donc tout toucher après quelques secondes d'inactivité est
 * perdu (ARCHITECTURE.md, « Mode immersif »). On ne l'impose donc pas : par défaut la barre système reste visible et
 * chaque toucher compte, au prix des 48 lignes du bas de l'écran distant (la zone de la barre des tâches d'un PC
 * Windows). Le plein écran, choisi par l'utilisateur, rend ces 48 lignes contre la perte du premier toucher.
 *
 * @property fitToScreen **ajuster à l'écran** avec bandes noires et ratio conservé, même un écran distant en 1280×800
 *   (SS-033). Faux par défaut : un 1280×800 est alors dessiné pixel pour pixel (SS-032), rogné des 48 lignes du bas si la
 *   barre système est visible. Un écran distant qui n'est pas en 1280×800 est toujours ajusté, quel que soit ce réglage.
 *
 * @param store stockage clé/valeur (`SharedPreferences` sur Android, en mémoire dans les tests).
 */
class DisplaySettings(private val store: KeyValueStore) {

    var fullscreen: Boolean
        get() = store.all()[KEY_FULLSCREEN] == TRUE
        set(value) = store.apply(mapOf(KEY_FULLSCREEN to if (value) TRUE else FALSE))

    var fitToScreen: Boolean
        get() = store.all()[KEY_FIT] == TRUE
        set(value) = store.apply(mapOf(KEY_FIT to if (value) TRUE else FALSE))

    companion object {
        /** Nom du fichier de préférences (distinct de celui des profils). */
        const val FILE_NAME = "display_settings"

        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_FIT = "fit_to_screen"
        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}
