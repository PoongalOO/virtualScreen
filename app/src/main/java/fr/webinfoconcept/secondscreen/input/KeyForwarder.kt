package fr.webinfoconcept.secondscreen.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Traduit un `KeyEvent` Android (clavier physique, ou touche envoyée par un clavier virtuel) en frappes pour
 * [KeyboardInput] (SS-046, SS-047). Adaptateur minimal : la logique est dans [Keysyms] et [KeyboardInput], testés sur la JVM.
 *
 * - une touche **spéciale ou modificatrice** ([Keysyms.fromAndroidKeyCode]) : appui et relâchement transmis tels quels,
 *   avec la répétition automatique tant qu'elle est maintenue ;
 * - une touche **de caractère** : le caractère produit (avec Maj) est tapé à l'appui ; Ctrl et Alt physiques sont déjà
 *   partis comme touches modificatrices, on ne les compte donc pas dans le caractère ;
 * - les touches **système** (Retour, Accueil, Menu, volume, marche/arrêt...) ne sont jamais consommées : Android les garde.
 *
 * Seules des API disponibles dès l'API 11 sont utilisées.
 */
class KeyForwarder(private val keyboard: KeyboardInput) {

    /** @return `true` si l'événement a été transmis au serveur (et ne doit plus être traité par Android). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val special = Keysyms.fromAndroidKeyCode(event.keyCode)
        if (special != 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> keyboard.keyDown(special)
                KeyEvent.ACTION_UP -> keyboard.keyUp(special)
            }
            return true
        }
        if (isSystemKey(event.keyCode)) return false

        val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_SHIFT_MASK)
        // Bit 31 : accent « mort » à combiner avec la touche suivante, pas un caractère.
        if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return false
        if (event.action == KeyEvent.ACTION_DOWN) keyboard.typeText(String(Character.toChars(unicode)))
        return true // l'appui a produit le caractère : le relâchement correspondant n'a plus rien à dire
    }

    private fun isSystemKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_CAMERA -> true
        else -> false
    }
}
