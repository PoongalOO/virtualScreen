package fr.webinfoconcept.secondscreen.input

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Vue invisible qui reçoit ce que tape le **clavier virtuel** Android (SS-046). Une `SurfaceView` n'est pas un champ de
 * texte : sans cette vue, aucun clavier ne peut lui envoyer de texte.
 *
 * Elle se déclare éditeur de texte ([onCheckIsTextEditor]) et fournit une [InputConnection] qui **ne garde aucun texte** :
 * tout ce que le clavier valide, propose ou supprime est transmis à [KeyboardInput] puis au serveur, l'état réel étant
 * de l'autre côté. Les options demandées au clavier vont dans le sens de la frappe brute : pas de suggestions ni de
 * correction (`NO_SUGGESTIONS`, mot de passe visible), pas de plein écran d'édition, Entrée qui envoie une vraie touche
 * Entrée (multi-lignes, pas d'action « OK »).
 *
 * @see KeyboardInput
 */
class KeyboardInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Destination des frappes ; `null` tant qu'aucune session n'en fournit (le clavier ne fait alors rien). */
    var keyboard: KeyboardInput? = null

    /** Pour les touches envoyées comme `KeyEvent` par le clavier virtuel (Retour arrière, Entrée, flèches...). */
    var forwarder: KeyForwarder? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return Connection()
    }

    /** Affiche le clavier virtuel. @return `false` si le système ne l'a pas affiché (pas de clavier installé...). */
    fun showKeyboard(): Boolean {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Connexion au clavier virtuel : convertit chaque opération en frappes. `BaseInputConnection` avec `fullEditor = false`
     * n'a pas de vrai texte ; aucune méthode de modification n'appelle la classe parente, donc rien n'est mémorisé.
     */
    private inner class Connection : BaseInputConnection(this, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            keyboard?.commitText(text)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            keyboard?.setComposingText(text)
            return true
        }

        override fun finishComposingText(): Boolean {
            keyboard?.finishComposing()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            keyboard?.deleteSurrounding(beforeLength, afterLength)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = forwarder?.onKeyEvent(event) ?: false

        override fun performEditorAction(actionCode: Int): Boolean {
            keyboard?.pressKey(Keysyms.RETURN) // action « OK »/« Entrée » d'un clavier qui n'a pas respecté IME_ACTION_NONE
            return true
        }

        // Le clavier interroge parfois le texte autour du curseur (majuscule automatique...) : il n'y en a pas.
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }
}
