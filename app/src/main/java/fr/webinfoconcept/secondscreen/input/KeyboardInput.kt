package fr.webinfoconcept.secondscreen.input

import fr.webinfoconcept.secondscreen.rfb.protocol.ClientMessages

/**
 * Envoie les frappes au serveur (SS-046 texte, SS-047 touches spéciales et modificateurs). Pure : ne dépend pas d'Android,
 * appelée depuis le thread UI, ne bloque jamais (les messages passent par un [MessageSink]).
 *
 * ## Texte (SS-046)
 * [typeText] envoie une frappe (appui + relâchement dans le **même message**) par caractère : ASCII, Latin-1, puis
 * keysyms Unicode (voir [Keysyms.fromCodePoint]). `\n` est Entrée, `\t` Tab. Les caractères sans keysym sont ignorés.
 * Un long texte est découpé en messages de [ClientMessages.MAX_KEY_PRESSES] frappes.
 *
 * ## Touches spéciales et modificateurs (SS-047)
 * - [pressKey] : une frappe complète (Échap, Tab, Entrée, Retour arrière, flèches...).
 * - **Ctrl, Alt, Maj** sont des bascules ([toggleModifier]) « à un coup » : la touche est **enfoncée tout de suite** côté
 *   serveur et **relâchée après la prochaine frappe non modificatrice** (Ctrl puis `c` donne Ctrl+C). Dans un texte de
 *   plusieurs caractères le modificateur ne s'applique qu'au **premier**.
 * - Maj enfoncé transforme les lettres a-z en A-Z (les serveurs relâchent Maj pour un keysym minuscule).
 * - Un modificateur n'est mémorisé que si son message est parti ; [releaseModifiers] relâche tout (fin de saisie, pause,
 *   fermeture du clavier) et [reset] oublie tout sans rien envoyer (session terminée : le serveur relâche de lui-même).
 *
 * ## Texte provisoire des claviers virtuels
 * [setComposingText], [commitText], [finishComposing] : voir [ComposingDiff]. [deleteSurrounding] envoie des Retour arrière
 * et des Suppr.
 *
 * ## Touches physiques
 * [keyDown]/[keyUp] transmettent tels quels appui et relâchement d'un clavier physique ou d'un clavier virtuel qui
 * envoie des `KeyEvent` ; une touche non modificatrice relâchée libère aussi les modificateurs à un coup. Limite connue : un
 * Maj/Ctrl/Alt **physique** gauche et la bascule **à l'écran** correspondante ont le même keysym ; les utiliser en même temps
 * peut produire un relâchement superflu, que le serveur ignore.
 *
 * **Confidentialité** : aucune frappe n'est journalisée ni conservée (un mot de passe peut être saisi à distance). Elles
 * circulent non chiffrées comme le reste de la session VNC (SECURITY.md : LAN de confiance).
 */
class KeyboardInput(private val sink: MessageSink) {

    /** Les trois modificateurs proposés à l'écran. */
    enum class Modifier(val keysym: Int) {
        CTRL(Keysyms.CONTROL_L),
        ALT(Keysyms.ALT_L),
        SHIFT(Keysyms.SHIFT_L)
    }

    /** Notifié quand l'état des modificateurs change (pour synchroniser les boutons), sur le thread appelant. */
    interface Listener {
        fun onModifiersChanged(ctrl: Boolean, alt: Boolean, shift: Boolean)
    }

    var listener: Listener? = null

    private val held = BooleanArray(Modifier.values().size)
    private val composing = ComposingDiff()

    fun isHeld(modifier: Modifier): Boolean = held[modifier.ordinal]

    /** `true` si au moins un modificateur est enfoncé côté serveur. */
    val anyModifierHeld: Boolean
        get() = held.any { it }

    // ------------------------------------------------------------------ modificateurs

    /**
     * Enfonce le modificateur s'il était relâché, le relâche sinon.
     * @return `true` s'il est enfoncé après l'appel (si le message n'est pas parti, l'état ne change pas).
     */
    fun toggleModifier(modifier: Modifier): Boolean {
        val wasHeld = held[modifier.ordinal]
        if (sink.send(ClientMessages.keyEvent(!wasHeld, modifier.keysym))) {
            held[modifier.ordinal] = !wasHeld
            notifyModifiers()
        }
        return held[modifier.ordinal]
    }

    /** Relâche tous les modificateurs enfoncés (dans l'ordre inverse de [Modifier] : Maj, Alt, Ctrl). */
    fun releaseModifiers() {
        var changed = false
        for (m in Modifier.values().reversed()) {
            if (held[m.ordinal]) {
                sink.send(ClientMessages.keyEvent(false, m.keysym)) // si le message est perdu, il n'y a rien de plus à faire
                held[m.ordinal] = false
                changed = true
            }
        }
        if (changed) notifyModifiers()
    }

    /** Oublie les modificateurs et le texte provisoire **sans rien envoyer** (session terminée). */
    fun reset() {
        val changed = anyModifierHeld
        held.fill(false)
        composing.reset()
        if (changed) notifyModifiers()
    }

    // ------------------------------------------------------------------ texte

    /**
     * Tape [text]. Le modificateur à un coup éventuel ne s'applique qu'au premier caractère.
     * @return le nombre de caractères envoyés (ceux qui ont un keysym).
     */
    fun typeText(text: CharSequence): Int {
        val chars = ComposingDiff.sendable(text)
        if (chars.isEmpty()) return 0
        var sent = 0
        var from = 0
        if (anyModifierHeld) { // le premier caractère porte les modificateurs, puis ils sont relâchés
            if (sink.send(ClientMessages.keyPress(keysymFor(chars[0])))) sent++
            releaseModifiers()
            from = 1
        }
        while (from < chars.size) {
            val n = minOf(ClientMessages.MAX_KEY_PRESSES, chars.size - from)
            val keysyms = IntArray(n) { Keysyms.fromCodePoint(chars[from + it]) }
            if (sink.send(ClientMessages.keyPresses(keysyms))) sent += n
            from += n
        }
        return sent
    }

    /**
     * Une frappe complète de [keysym] (touche spéciale ou caractère), avec les modificateurs enfoncés, puis relâche les
     * modificateurs à un coup.
     * @return `true` si le message est parti.
     */
    fun pressKey(keysym: Int): Boolean {
        val sentOk = sink.send(ClientMessages.keyPress(keysym))
        if (!Keysyms.isModifier(keysym)) releaseModifiers()
        return sentOk
    }

    /** Keysym d'un caractère avec l'effet de Maj (a-z -> A-Z). */
    private fun keysymFor(codePoint: Int): Int {
        val cp = if (isHeld(Modifier.SHIFT) && codePoint in 'a'.code..'z'.code) codePoint - 32 else codePoint
        return Keysyms.fromCodePoint(cp)
    }

    // ------------------------------------------------------------------ texte provisoire

    /** Le clavier propose [text] comme texte provisoire : ce qui a changé depuis la proposition précédente est envoyé. */
    fun setComposingText(text: CharSequence) = apply(composing.update(text))

    /** Le clavier valide [text] : il remplace le texte provisoire éventuel, qui n'est plus modifiable ensuite. */
    fun commitText(text: CharSequence) {
        apply(composing.update(text))
        composing.finish()
    }

    /** Le texte provisoire est validé tel quel. */
    fun finishComposing() = composing.finish()

    private fun apply(edit: ComposingDiff.Edit) {
        repeat(edit.backspaces) { sink.send(ClientMessages.keyPress(Keysyms.BACKSPACE)) }
        if (edit.insert.isNotEmpty()) typeText(String(edit.insert, 0, edit.insert.size))
    }

    /**
     * Supprime [before] caractères avant le curseur (Retour arrière) et [after] après (Suppr), bornés à
     * [MAX_DELETE] chacun : une valeur absurde ne doit pas inonder le serveur.
     */
    fun deleteSurrounding(before: Int, after: Int) {
        composing.reset() // le clavier a repris la main sur le texte : ce qui était provisoire est maintenant figé
        repeat(before.coerceIn(0, MAX_DELETE)) { sink.send(ClientMessages.keyPress(Keysyms.BACKSPACE)) }
        repeat(after.coerceIn(0, MAX_DELETE)) { sink.send(ClientMessages.keyPress(Keysyms.DELETE)) }
    }

    // ------------------------------------------------------------------ touches physiques

    /** Appui de la touche de keysym [keysym] (répété tant qu'elle est maintenue). */
    fun keyDown(keysym: Int): Boolean = sink.send(ClientMessages.keyEvent(true, keysym))

    /** Relâchement de [keysym] ; une touche non modificatrice libère les modificateurs à un coup. */
    fun keyUp(keysym: Int): Boolean {
        val sentOk = sink.send(ClientMessages.keyEvent(false, keysym))
        if (!Keysyms.isModifier(keysym)) releaseModifiers()
        return sentOk
    }

    private fun notifyModifiers() {
        listener?.onModifiersChanged(isHeld(Modifier.CTRL), isHeld(Modifier.ALT), isHeld(Modifier.SHIFT))
    }

    companion object {
        /** Plus grand nombre de Retour arrière ou de Suppr d'une seule demande de suppression du clavier. */
        const val MAX_DELETE = 256
    }
}
