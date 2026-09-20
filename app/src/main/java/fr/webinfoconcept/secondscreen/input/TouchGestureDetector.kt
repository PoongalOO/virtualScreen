package fr.webinfoconcept.secondscreen.input

/**
 * Reçoit un glissement reconnu par [TouchGestureDetector] (SS-042). Les positions sont en pixels de la vue.
 * Un glissement est toujours fermé : après [onDragStart], [onDragEnd] est appelé **exactement une fois**, même si le
 * geste est annulé, interrompu par un deuxième doigt ou remplacé par un nouveau toucher.
 */
interface DragListener {
    /** Le doigt a dépassé le seuil : le bouton doit être enfoncé à la position **de départ** (où il s'est posé). */
    fun onDragStart(x: Float, y: Float)

    /** Le doigt est maintenant en ([x], [y]), bouton toujours enfoncé. */
    fun onDragMove(x: Float, y: Float)

    /** Fin du glissement : relâcher le bouton en ([x], [y]), la dernière position du doigt (voir [TouchGestureDetector]). */
    fun onDragEnd(x: Float, y: Float)
}

/**
 * Reconnaît un **tap** ou un **glissement** dans un flux d'événements tactiles (SS-041, SS-042). Machine à états pure,
 * sans dépendance Android : testable sur la JVM. L'adaptation depuis `MotionEvent` est dans [TouchInput].
 *
 * ## Tap (SS-041)
 * Un tap déclenche [onTap] **une seule fois, au relâchement** (`onUp`), à la position où le doigt a touché. Le clic
 * n'est donc jamais émis à l'appui : un appui qui devient un geste ne produit jamais de clic gauche parasite. Pas
 * de tap :
 * - si le doigt **bouge** de plus de [slopPx] pixels (c'est alors un glissement) ;
 * - si le contact dure plus de [maxTapMs] (appui long : SS-043) ;
 * - si un **deuxième doigt** se pose (défilement à deux doigts : SS-044) ;
 * - si le système **annule** le geste ([onCancel]).
 *
 * ## Glissement (SS-042)
 * Quand le doigt dépasse [slopPx], le glissement commence : `onDragStart(position de départ)` puis
 * `onDragMove(position actuelle)`, puis un `onDragMove` par déplacement, et `onDragEnd` au relâchement. Le bouton est
 * donc enfoncé là où l'utilisateur a posé le doigt, pas là où le seuil a été franchi.
 *
 * **Le relâchement se fait à la dernière position de déplacement, pas à celle de `ACTION_UP`** : le pointeur distant
 * y est déjà, il ne « saute » donc pas au relâchement. Mesuré sur la GT-P5110 : l'émulation `input swipe`
 * d'Android 4.2 envoie un `ACTION_UP` à la position de **départ** ; l'utiliser aurait ramené le pointeur au point de
 * départ à chaque relâchement. Sur un vrai doigt les deux positions coïncident. Revenir vers le départ ne
 * transforme pas le glissement en tap. Sans [dragListener], un déplacement au-delà du seuil annule simplement le geste.
 * Un relâchement loin du départ sans aucun événement de déplacement compte aussi comme un glissement (départ,
 * déplacement, fin).
 *
 * **Un bouton n'est jamais laissé enfoncé** : annulation, deuxième doigt et nouveau `onDown` pendant un glissement
 * appellent tous [DragListener.onDragEnd] à la dernière position connue. Après un deuxième doigt le geste reste
 * ignoré jusqu'au prochain `onDown` : lever le premier doigt ne clique pas.
 *
 * Un `onUp` sans `onDown` correspondant, ou un second `onUp` du même geste, est ignoré : pas de double événement.
 * Les coordonnées non finies (NaN, infini) sont ignorées en déplacement.
 *
 * @param slopPx déplacement toléré, en pixels de la vue (typiquement `ViewConfiguration.scaledTouchSlop`) ; le
 *   dépasser annule le tap et démarre le glissement. Une distance égale à [slopPx] reste un tap.
 * @param maxTapMs durée maximale d'un tap, en ms ; par défaut le délai d'appui long d'Android (500 ms), pour que
 *   SS-043 prenne exactement le relais. Ne limite pas le début d'un glissement.
 * @param dragListener reçoit les glissements ; `null` pour ne reconnaître que les taps.
 * @param onTap reçoit la position du toucher initial, en pixels de la vue.
 */
class TouchGestureDetector(
    private val slopPx: Float,
    private val maxTapMs: Long = DEFAULT_MAX_TAP_MS,
    private val dragListener: DragListener? = null,
    private val onTap: (x: Float, y: Float) -> Unit
) {
    init {
        require(slopPx >= 0f && !slopPx.isNaN()) { "slop invalide : $slopPx" }
        require(maxTapMs > 0) { "maxTapMs doit être > 0 : $maxTapMs" }
    }

    private enum class State { IDLE, PENDING, DRAGGING }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** `true` tant qu'un geste est un tap possible (doigt posé, pas encore de mouvement significatif). */
    val isTracking: Boolean
        get() = state == State.PENDING

    /** `true` entre [DragListener.onDragStart] et [DragListener.onDragEnd]. */
    val isDragging: Boolean
        get() = state == State.DRAGGING

    /** Premier doigt posé en ([x], [y]) à l'instant [timeMs] (horloge monotone, ms). */
    fun onDown(x: Float, y: Float, timeMs: Long) {
        endDragIfAny() // un relâchement perdu ne doit pas laisser le bouton enfoncé
        state = State.PENDING
        downX = x
        downY = y
        downTimeMs = timeMs
        lastX = x
        lastY = y
    }

    /** Le doigt bouge : au-delà du seuil ce n'est plus un tap, c'est un glissement (s'il y a un [dragListener]). */
    fun onMove(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        when (state) {
            State.PENDING -> if (!withinSlop(x, y)) startDrag(x, y)
            State.DRAGGING -> moveDrag(x, y)
            State.IDLE -> Unit
        }
    }

    /** Un doigt supplémentaire est posé : ce n'est ni un tap ni un glissement ; un glissement en cours se termine. */
    fun onSecondFingerDown() {
        endDragIfAny()
        state = State.IDLE
    }

    /**
     * Le doigt se lève en ([x], [y]) à l'instant [timeMs] : termine le glissement (à sa dernière position de
     * déplacement), ou émet le tap si le geste en est un.
     * @return `true` si un tap a été émis par cet appel.
     */
    fun onUp(x: Float, y: Float, timeMs: Long): Boolean {
        when (state) {
            State.IDLE -> return false
            State.DRAGGING -> {
                endDrag(lastX, lastY) // pas la position de l'UP : voir la documentation de la classe
                return false
            }
            State.PENDING -> {
                if (x.isFinite() && y.isFinite() && !withinSlop(x, y) && dragListener != null) {
                    // Le doigt a sauté sans événement de déplacement : appui au départ, relâchement à l'arrivée.
                    startDrag(x, y)
                    endDrag(x, y)
                    return false
                }
                state = State.IDLE // avant le rappel : un second onUp ne peut jamais émettre deux fois
                val duration = timeMs - downTimeMs
                if (duration < 0 || duration > maxTapMs || !withinSlop(x, y)) return false
                onTap(downX, downY)
                return true
            }
        }
    }

    /** Geste annulé (`ACTION_CANCEL`, perte du focus...) : jamais de tap ; un glissement en cours est relâché. */
    fun onCancel() {
        endDragIfAny()
        state = State.IDLE
    }

    private fun startDrag(x: Float, y: Float) {
        val listener = dragListener
        if (listener == null) {
            state = State.IDLE // sans écouteur : un mouvement annule simplement le tap
            return
        }
        state = State.DRAGGING
        lastX = x
        lastY = y
        listener.onDragStart(downX, downY)
        listener.onDragMove(x, y)
    }

    private fun moveDrag(x: Float, y: Float) {
        lastX = x
        lastY = y
        dragListener?.onDragMove(x, y)
    }

    private fun endDrag(x: Float, y: Float) {
        state = State.IDLE // avant le rappel : jamais deux onDragEnd pour un même glissement
        dragListener?.onDragEnd(x, y)
    }

    private fun endDragIfAny() {
        if (state == State.DRAGGING) endDrag(lastX, lastY)
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
