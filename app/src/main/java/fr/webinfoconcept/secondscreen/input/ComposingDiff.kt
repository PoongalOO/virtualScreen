package fr.webinfoconcept.secondscreen.input

/**
 * Suit le texte **en cours de composition** d'un clavier virtuel (SS-046) et dit ce qu'il faut taper à distance pour que
 * le serveur reflète l'état courant.
 *
 * Beaucoup de claviers Android ne valident pas chaque lettre : ils envoient d'abord un texte provisoire (« bon », puis
 * « bonj », puis la correction « bonjour ») avant de le valider. Le serveur distant ne connaît que des frappes : on lui
 * envoie donc la partie nouvelle et, quand le clavier **corrige** ce qu'il avait proposé, autant de Retour arrière que de
 * caractères retirés. Le texte suivi est celui **réellement envoyé** (sans les caractères sans keysym), pour que le
 * nombre de Retour arrière soit toujours juste. Les comptes sont en **points de code** (une frappe par caractère, pas par
 * unité UTF-16).
 *
 * Pure : aucune dépendance Android.
 */
class ComposingDiff {
    private var sent: IntArray = EMPTY

    /** Ce qu'il faut faire pour passer du texte provisoire précédent à un nouveau : [backspaces] puis taper [insert]. */
    class Edit(val backspaces: Int, val insert: IntArray) {
        val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()
    }

    /** Nombre de caractères provisoires actuellement affichés à distance. */
    val length: Int
        get() = sent.size

    /** `true` si un texte provisoire est en cours. */
    val isComposing: Boolean
        get() = sent.isNotEmpty()

    /**
     * Le clavier propose [text] à la place du texte provisoire courant (qui devient [text]). Les caractères sans keysym
     * sont ignorés (ils ne sont pas envoyés, donc pas suivis).
     */
    fun update(text: CharSequence): Edit {
        val next = sendable(text)
        var prefix = 0
        val limit = minOf(sent.size, next.size)
        while (prefix < limit && sent[prefix] == next[prefix]) prefix++
        val edit = Edit(sent.size - prefix, next.copyOfRange(prefix, next.size))
        sent = next
        return edit
    }

    /** Le texte provisoire est validé tel quel : il reste à l'écran distant et n'est plus modifiable. */
    fun finish() {
        sent = EMPTY
    }

    /** Oublie tout (fin de session, changement de champ) sans rien envoyer. */
    fun reset() = finish()

    companion object {
        private val EMPTY = IntArray(0)

        /** Les points de code de [text] qui ont un keysym, dans l'ordre. */
        fun sendable(text: CharSequence): IntArray {
            var out = IntArray(text.length)
            var n = 0
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                i += Character.charCount(cp)
                if (Keysyms.fromCodePoint(cp) != 0) out[n++] = cp
            }
            if (n != out.size) out = out.copyOf(n)
            return out
        }
    }
}
