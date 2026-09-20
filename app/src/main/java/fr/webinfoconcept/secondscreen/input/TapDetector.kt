package fr.webinfoconcept.secondscreen.input

/**
 * Reconnaît un **tap** (un doigt qui touche puis se lève sans bouger) dans un flux d'événements tactiles (SS-041).
 * Machine à états pure, sans dépendance Android : testable sur la JVM. L'adaptation depuis `MotionEvent` est dans
 * [TouchInput].
 *
 * Un tap déclenche [onTap] **une seule fois, au relâchement** (`onUp`), à la position où le doigt a touché. Le clic
 * n'est donc jamais émis à l'appui : un appui qui devient un geste (appui long, glissement, deuxième doigt) ne
 * produit jamais de clic gauche parasite. Aucun événement n'est émis pour :
 * - un doigt qui **bouge** de plus de [slopPx] pixels (début d'un glissement : SS-042) ;
 * - un contact **plus long** que [maxTapMs] (appui long : SS-043) ;
 * - un **deuxième doigt** posé pendant le geste (défilement à deux doigts : SS-044) ; le geste reste annulé
 *   jusqu'au prochain `onDown`, donc lever le premier doigt ne clique pas non plus ;
 * - un geste **annulé** par le système ([onCancel]).
 *
 * Un `onUp` sans `onDown` correspondant, ou un second `onUp` du même geste, est ignoré : pas de double clic
 * possible depuis un même contact. Un nouveau `onDown` alors qu'un geste est en cours (relâchement perdu) repart de
 * zéro, sans clic pour l'ancien.
 *
 * @param slopPx déplacement toléré, en pixels de la vue (typiquement `ViewConfiguration.scaledTouchSlop`) ; le
 *   dépasser annule le tap. Une distance égale à [slopPx] reste un tap.
 * @param maxTapMs durée maximale du contact, en ms ; par défaut le délai d'appui long d'Android (500 ms), pour que
 *   SS-043 prenne exactement le relais.
 * @param onTap reçoit la position du toucher initial, en pixels de la vue.
 */
class TapDetector(
    private val slopPx: Float,
    private val maxTapMs: Long = DEFAULT_MAX_TAP_MS,
    private val onTap: (x: Float, y: Float) -> Unit
) {
    init {
        require(slopPx >= 0f && !slopPx.isNaN()) { "slop invalide : $slopPx" }
        require(maxTapMs > 0) { "maxTapMs doit être > 0 : $maxTapMs" }
    }

    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L

    /** `true` tant qu'un geste est un tap possible. */
    val isTracking: Boolean
        get() = tracking

    /** Premier doigt posé en ([x], [y]) à l'instant [timeMs] (horloge monotone, ms). */
    fun onDown(x: Float, y: Float, timeMs: Long) {
        tracking = true
        downX = x
        downY = y
        downTimeMs = timeMs
    }

    /** Le doigt bouge : au-delà du seuil, ce n'est plus un tap. */
    fun onMove(x: Float, y: Float) {
        if (tracking && !withinSlop(x, y)) tracking = false
    }

    /** Un doigt supplémentaire est posé : ce n'est pas un tap. */
    fun onSecondFingerDown() {
        tracking = false
    }

    /**
     * Le doigt se lève en ([x], [y]) à l'instant [timeMs] : émet le tap si le geste en est un.
     * @return `true` si un tap a été émis par cet appel.
     */
    fun onUp(x: Float, y: Float, timeMs: Long): Boolean {
        if (!tracking) return false
        tracking = false // avant le rappel : un second onUp ne peut jamais émettre deux fois
        val duration = timeMs - downTimeMs
        if (duration < 0 || duration > maxTapMs || !withinSlop(x, y)) return false
        onTap(downX, downY)
        return true
    }

    /** Geste annulé (`ACTION_CANCEL`) : jamais de tap. */
    fun onCancel() {
        tracking = false
    }

    private fun withinSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        // Comparaison des carrés (pas de racine) ; NaN échoue et annule le tap.
        return dx * dx + dy * dy <= slopPx * slopPx
    }

    companion object {
        /** Délai d'appui long d'Android (`ViewConfiguration.getLongPressTimeout()` par défaut). */
        const val DEFAULT_MAX_TAP_MS = 500L
    }
}
