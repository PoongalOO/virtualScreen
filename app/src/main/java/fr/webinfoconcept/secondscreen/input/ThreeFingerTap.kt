package fr.webinfoconcept.secondscreen.input

/**
 * Reconnaît un **tap à trois doigts** (SS-052) : trois doigts posés ensemble, puis levés vite et sans avoir bougé. Sert à
 * afficher ou masquer la barre de commandes sans occuper un coin de l'écran distant. Machine à états pure, sans Android.
 *
 * Trois doigts ne correspondent à aucun autre geste de l'application (un doigt : tap, glissement, appui long ; deux :
 * défilement), donc aucun conflit : quand le troisième doigt se pose, le défilement en cours est déjà abandonné par
 * [TouchGestureDetector.onSecondFingerDown].
 *
 * @param maxDurationMs durée maximale entre la pose du troisième doigt et la levée du premier.
 * @param maxDriftPx déplacement maximal du centre des trois doigts.
 */
class ThreeFingerTap(private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS, private val maxDriftPx: Float) {
    init {
        require(maxDurationMs > 0) { "durée > 0 requise" }
        require(maxDriftPx >= 0f && !maxDriftPx.isNaN()) { "dérive invalide : $maxDriftPx" }
    }

    private var tracking = false
    private var startX = 0f
    private var startY = 0f
    private var startMs = 0L

    /** `true` entre la pose du troisième doigt et la fin ou l'abandon du geste. */
    val isTracking: Boolean
        get() = tracking

    /** Le troisième doigt vient de se poser ; ([centerX], [centerY]) est le centre des trois doigts. */
    fun onThreeFingersDown(centerX: Float, centerY: Float, timeMs: Long) {
        tracking = centerX.isFinite() && centerY.isFinite()
        startX = centerX
        startY = centerY
        startMs = timeMs
    }

    /** Le centre des trois doigts se déplace : trop loin, ce n'est plus un tap. */
    fun onMove(centerX: Float, centerY: Float) {
        if (!tracking) return
        val dx = centerX - startX
        val dy = centerY - startY
        if (!(dx * dx + dy * dy <= maxDriftPx * maxDriftPx)) tracking = false // NaN échoue aussi
    }

    /**
     * Un doigt se lève.
     * @return `true` (une seule fois par geste) si c'est un tap à trois doigts réussi.
     */
    fun onFingerUp(timeMs: Long): Boolean {
        if (!tracking) return false
        tracking = false
        val duration = timeMs - startMs
        return duration in 0..maxDurationMs
    }

    /** Le geste est abandonné (quatrième doigt, annulation, fin du geste) : jamais de tap. */
    fun cancel() {
        tracking = false
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 350L
    }
}
