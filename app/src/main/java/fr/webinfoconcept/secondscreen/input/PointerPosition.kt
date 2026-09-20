package fr.webinfoconcept.secondscreen.input

/**
 * Dernière position du pointeur distant, en pixels du framebuffer, **partagée** par le mode direct ([PointerActions]) et
 * le mode touchpad ([TouchpadActions]) : quand l'utilisateur passe de l'un à l'autre, le pointeur repart d'où il est au
 * lieu de sauter. Thread UI seulement.
 */
class PointerPosition(var x: Int = 0, var y: Int = 0) {
    /** `true` une fois qu'une position réelle a été envoyée au serveur (avant : valeur par défaut, inconnue). */
    var known: Boolean = false
        private set

    /** Nombre de remises à zéro : permet à un utilisateur de la position de savoir qu'elle a été oubliée depuis sa dernière lecture. */
    var resets: Int = 0
        private set

    /** Oublie la position (nouvelle session : le serveur, la taille de l'écran ou les deux ont changé). */
    fun reset() {
        x = 0
        y = 0
        known = false
        resets++
    }

    fun set(newX: Int, newY: Int) {
        x = newX
        y = newY
        known = true
    }
}
