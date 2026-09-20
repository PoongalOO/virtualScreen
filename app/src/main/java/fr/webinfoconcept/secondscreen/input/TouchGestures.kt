package fr.webinfoconcept.secondscreen.input

/**
 * Ce que [TouchInput] attend d'un interpréteur de gestes : le mode direct ([TouchGestureDetector]) et le mode touchpad
 * ([TouchpadDetector]) l'implémentent, ce qui permet de changer de mode sans que `TouchInput` connaisse leurs détails.
 * Tout se passe sur le thread UI.
 */
interface TouchGestures {
    /** `true` pendant un défilement à deux doigts. */
    val isScrolling: Boolean

    /** Premier doigt posé en ([x], [y]) à l'instant [timeMs] (horloge monotone, ms). */
    fun onDown(x: Float, y: Float, timeMs: Long)

    /** Le premier doigt bouge. */
    fun onMove(x: Float, y: Float)

    /** Le premier doigt se lève. @return `true` si un clic a été émis par cet appel. */
    fun onUp(x: Float, y: Float, timeMs: Long): Boolean

    /** Un doigt supplémentaire (troisième ou plus, ou défilement non reconnu) : le geste est abandonné. */
    fun onSecondFingerDown()

    /** Le deuxième doigt vient d'être posé ; ([centerX], [centerY]) est le centre des deux doigts. */
    fun onTwoFingersDown(centerX: Float, centerY: Float)

    /** Le centre des deux doigts se déplace. */
    fun onTwoFingersMove(centerX: Float, centerY: Float)

    /** Geste annulé (annulation système, perte du focus, changement de mode) : tout bouton enfoncé est relâché. */
    fun onCancel()
}
