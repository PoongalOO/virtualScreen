package fr.webinfoconcept.secondscreen.settings

import fr.webinfoconcept.secondscreen.input.TouchpadActions
import fr.webinfoconcept.secondscreen.profile.KeyValueStore

/**
 * Réglages des entrées tactiles mémorisés d'une session à l'autre (SS-045). Aucune donnée sensible.
 *
 * @property touchpad mode **touchpad** : le doigt déplace le pointeur au lieu de le placer. Faux par défaut (mode direct).
 * @property touchpadSensitivity facteur entre le déplacement du doigt et celui du pointeur, borné à
 *   [TouchpadActions.MIN_SENSITIVITY]..[TouchpadActions.MAX_SENSITIVITY] : une valeur illisible, non finie ou hors bornes
 *   dans le stockage redonne la valeur par défaut ou la borne, jamais une exception.
 */
class InputSettings(private val store: KeyValueStore) {

    var touchpad: Boolean
        get() = store.all()[KEY_TOUCHPAD] == TRUE
        set(value) = store.apply(mapOf(KEY_TOUCHPAD to if (value) TRUE else FALSE))

    var touchpadSensitivity: Float
        get() {
            val stored = store.all()[KEY_SENSITIVITY]?.toFloatOrNull()
            return if (stored == null || !stored.isFinite()) TouchpadActions.DEFAULT_SENSITIVITY else clamp(stored)
        }
        set(value) {
            require(value.isFinite()) { "sensibilité non finie" }
            store.apply(mapOf(KEY_SENSITIVITY to clamp(value).toString()))
        }

    companion object {
        /** Nom du fichier de préférences (distinct de celui des profils et de l'affichage). */
        const val FILE_NAME = "input_settings"

        private const val KEY_TOUCHPAD = "touchpad"
        private const val KEY_SENSITIVITY = "touchpad_sensitivity"
        private const val TRUE = "true"
        private const val FALSE = "false"

        fun clamp(v: Float): Float = v.coerceIn(TouchpadActions.MIN_SENSITIVITY, TouchpadActions.MAX_SENSITIVITY)
    }
}
