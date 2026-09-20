package fr.webinfoconcept.secondscreen.input

import android.view.KeyEvent

/**
 * Keysyms X11, la « langue » des touches de RFB (SS-046, SS-047). Un `KeyEvent` RFB désigne le **symbole** produit (« la
 * lettre a », « la touche Entrée »), pas une touche physique : le serveur retrouve lui-même la touche de son clavier.
 *
 * Références : `X11/keysymdef.h` et RFC 6143 §7.5.4. Les valeurs ci-dessous sont celles de ce fichier.
 */
object Keysyms {
    const val BACKSPACE = 0xFF08
    const val TAB = 0xFF09
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF

    const val HOME = 0xFF50
    const val LEFT = 0xFF51
    const val UP = 0xFF52
    const val RIGHT = 0xFF53
    const val DOWN = 0xFF54
    const val PAGE_UP = 0xFF55
    const val PAGE_DOWN = 0xFF56
    const val END = 0xFF57
    const val INSERT = 0xFF63

    const val F1 = 0xFFBE // F2 = F1 + 1 ... F12 = F1 + 11

    const val SHIFT_L = 0xFFE1
    const val SHIFT_R = 0xFFE2
    const val CONTROL_L = 0xFFE3
    const val CONTROL_R = 0xFFE4
    const val CAPS_LOCK = 0xFFE5
    const val META_L = 0xFFE7
    const val META_R = 0xFFE8
    const val ALT_L = 0xFFE9
    const val ALT_R = 0xFFEA
    const val SUPER_L = 0xFFEB
    const val SUPER_R = 0xFFEC

    /** Décalage des keysyms « Unicode » de X11 : `0x01000000 + point de code` (plus grand : `0x0110FFFF`). */
    const val UNICODE_OFFSET = 0x01000000

    /** `true` pour les touches de modification (Maj, Ctrl, Alt, Méta, Super), qui n'ont pas d'effet seules. */
    fun isModifier(keysym: Int): Boolean =
        keysym in SHIFT_L..CONTROL_R || keysym in META_L..SUPER_R

    /**
     * Keysym d'un caractère, ou `0` s'il n'y en a pas (caractère de contrôle sans touche, réservé, ou point de code
     * invalide).
     *
     * - ASCII imprimable (U+0020..U+007E) et Latin-1 (U+00A0..U+00FF) : **le point de code lui-même** (c'est la
     *   définition des keysyms Latin-1) ;
     * - `\n` et `\r` : Entrée ; `\t` : Tab ; `\b` : Retour arrière ; U+001B : Échap ; U+007F : Suppr ;
     * - au-delà de U+00FF : keysym Unicode `0x01000000 + point de code` (euro, œ, lettres grecques...), à la charge du
     *   serveur (TigerVNC et x11vnc le font) ;
     * - les autres contrôles (C0, C1), les demi-paires de substitution et tout ce qui dépasse U+10FFFF donnent `0`.
     */
    fun fromCodePoint(codePoint: Int): Int = when {
        codePoint in 0x20..0x7E -> codePoint
        codePoint in 0xA0..0xFF -> codePoint
        codePoint == 0x0A || codePoint == 0x0D -> RETURN
        codePoint == 0x09 -> TAB
        codePoint == 0x08 -> BACKSPACE
        codePoint == 0x1B -> ESCAPE
        codePoint == 0x7F -> DELETE
        codePoint in 0x100..0x10FFFF && codePoint !in 0xD800..0xDFFF -> UNICODE_OFFSET + codePoint
        else -> 0
    }

    /**
     * Keysym d'une touche Android, ou `0` si elle n'en a pas ici : touches système (Retour, Accueil, Menu, volume...) qui
     * doivent rester à Android, et touches de caractères (traitées par [fromCodePoint] avec le caractère produit).
     */
    fun fromAndroidKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> CONTROL_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_R
        KeyEvent.KEYCODE_ALT_LEFT -> ALT_L
        KeyEvent.KEYCODE_ALT_RIGHT -> ALT_R
        KeyEvent.KEYCODE_META_LEFT -> SUPER_L
        KeyEvent.KEYCODE_META_RIGHT -> SUPER_R
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> 0
    }
}
