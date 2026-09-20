package fr.webinfoconcept.secondscreen.input

/**
 * Reçoit ce que le mode touchpad reconnaît (SS-045). Les déplacements sont en **pixels de la vue, avant sensibilité** : c'est
 * [TouchpadActions] qui les met à l'échelle. Un glissement ([onDragStart] .. [onDragEnd]) est toujours fermé : après un
 * [onDragStart], [onDragEnd] est appelé **exactement une fois**, même si le geste est annulé.
 */
interface TouchpadListener {
    /** Le pointeur distant se déplace de ([dx], [dy]) (pixels de la vue, avant sensibilité), bouton comme il est. */
    fun onPointerMove(dx: Float, dy: Float)

    /** Clic gauche à la position **actuelle du pointeur distant** (pas sous le doigt). */
    fun onClick()

    /** Clic droit à la position actuelle du pointeur distant. */
    fun onRightClick()

    /** Le bouton gauche est enfoncé à la position actuelle du pointeur ; les déplacements suivants le déplacent bouton enfoncé. */
    fun onDragStart()

    /** Le bouton gauche est relâché. */
    fun onDragEnd()
}

/**
 * Interprète les doigts comme un **pavé tactile** (SS-045) : le doigt ne désigne pas un point de l'écran distant, il
 * *déplace* le pointeur, comme sur un ordinateur portable. Machine à états pure, sans Android.
 *
 * | Geste | Effet |
 * |---|---|
 * | glisser un doigt | déplace le pointeur (relatif), aucun bouton |
 * | toucher et lever vite, sans bouger | clic gauche **où est le pointeur** |
 * | deux touchers rapides de suite | double clic |
 * | toucher, lever, **retoucher et glisser** | glissement : bouton gauche enfoncé pendant le déplacement |
 * | doigt posé sans bouger jusqu'au délai d'appui long | clic droit (le reste du geste est ignoré) |
 * | deux doigts | défilement (molette), comme en mode direct |
 *
 * **Pas de saut au démarrage** : tant que le doigt n'a pas dépassé [slopPx], le pointeur ne bouge pas (un tap ne doit pas
 * faire dériver le pointeur avant de cliquer) ; dès que le seuil est franchi, le déplacement **déjà parcouru est
 * appliqué en entier**, puis les suivants au fil de l'eau : aucun mouvement n'est perdu.
 *
 * **Un bouton n'est jamais laissé enfoncé** : annulation, deuxième doigt et nouveau toucher pendant un glissement
 * appellent tous [TouchpadListener.onDragEnd].
 *
 * @param slopPx déplacement toléré pour un tap, en pixels de la vue (typiquement `scaledTouchSlop`).
 * @param maxTapMs durée maximale d'un tap quand il n'y a pas d'appui long, et d'un second tap.
 * @param doubleTapMs délai maximal entre le relâchement d'un tap et le toucher suivant pour que celui-ci soit un second tap
 *   ou le début d'un glissement.
 * @param longPress réglage de l'appui long (clic droit) ; `null` pour ne pas le reconnaître.
 * @param scroll réglage du défilement à deux doigts ; `null` pour ne pas le reconnaître.
 */
class TouchpadDetector(
    private val slopPx: Float,
    private val listener: TouchpadListener,
    private val maxTapMs: Long = TouchGestureDetector.DEFAULT_MAX_TAP_MS,
    private val doubleTapMs: Long = DEFAULT_DOUBLE_TAP_MS,
    private val longPress: LongPress? = null,
    scroll: Scroll? = null
) : TouchGestures {
    init {
        require(slopPx >= 0f && !slopPx.isNaN()) { "slop invalide : $slopPx" }
        require(maxTapMs > 0 && doubleTapMs > 0) { "délais > 0 requis" }
    }

    private enum class State { IDLE, PENDING, MOVING, DRAGGING, LONG_PRESSED, SCROLLING }

    private val longPressTask = Runnable { onLongPressTimeout() }
    private val scrollTracker = scroll?.let { ScrollTracker(it) }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** Ce toucher suit un tap de près : c'est un second tap ou le début d'un glissement. */
    private var followsTap = false
    private var lastTapUpMs = NO_TAP

    /** `true` entre [TouchpadListener.onDragStart] et [TouchpadListener.onDragEnd]. */
    val isDragging: Boolean
        get() = state == State.DRAGGING

    /** `true` une fois l'appui long déclenché, jusqu'à la fin du geste. */
    val isLongPressed: Boolean
        get() = state == State.LONG_PRESSED

    /** `true` tant que le pointeur peut encore ne pas avoir bougé (tap possible). */
    val isTracking: Boolean
        get() = state == State.PENDING

    override val isScrolling: Boolean
        get() = state == State.SCROLLING

    override fun onDown(x: Float, y: Float, timeMs: Long) {
        endDragIfAny() // un relâchement perdu ne doit pas laisser le bouton enfoncé
        cancelLongPressTimer()
        followsTap = lastTapUpMs != NO_TAP && timeMs - lastTapUpMs in 0..doubleTapMs
        lastTapUpMs = NO_TAP
        state = State.PENDING
        downX = x
        downY = y
        downTimeMs = timeMs
        lastX = x
        lastY = y
        // Un toucher qui suit un tap n'est pas un appui long : il est le second tap ou le début d'un glissement.
        if (!followsTap) longPress?.scheduler?.postDelayed(longPressTask, longPress.thresholdMs)
    }

    override fun onMove(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        when (state) {
            State.PENDING -> if (!withinSlop(x, y)) {
                cancelLongPressTimer()
                if (followsTap) {
                    state = State.DRAGGING
                    listener.onDragStart() // avant tout déplacement : le bouton est enfoncé là où est le pointeur
                } else {
                    state = State.MOVING
                }
                listener.onPointerMove(x - downX, y - downY) // le chemin déjà parcouru, en entier
                lastX = x
                lastY = y
            }
            State.MOVING, State.DRAGGING -> {
                listener.onPointerMove(x - lastX, y - lastY)
                lastX = x
                lastY = y
            }
            State.IDLE, State.LONG_PRESSED, State.SCROLLING -> Unit
        }
    }

    override fun onUp(x: Float, y: Float, timeMs: Long): Boolean {
        when (state) {
            State.IDLE -> return false
            State.LONG_PRESSED, State.SCROLLING, State.MOVING -> {
                state = State.IDLE
                return false
            }
            State.DRAGGING -> {
                endDrag()
                return false
            }
            State.PENDING -> {
                cancelLongPressTimer()
                if (x.isFinite() && y.isFinite() && !withinSlop(x, y)) {
                    // Le doigt a sauté sans événement de déplacement : c'est un déplacement, pas un tap.
                    onMove(x, y)
                    return onUp(x, y, timeMs)
                }
                state = State.IDLE // avant les rappels : un second onUp ne peut jamais émettre deux fois
                val duration = timeMs - downTimeMs
                if (duration < 0) return false
                if (!followsTap && longPress != null && duration >= longPress.thresholdMs) {
                    listener.onRightClick() // le minuteur n'a pas eu le temps de tourner : l'horodatage décide
                    return false
                }
                if (duration > maxTapMs && (followsTap || longPress == null)) return false
                if (!followsTap) lastTapUpMs = timeMs // un troisième toucher rapide pourra être un glissement
                listener.onClick()
                return true
            }
        }
    }

    override fun onSecondFingerDown() {
        cancelLongPressTimer()
        endDragIfAny()
        state = State.IDLE
        lastTapUpMs = NO_TAP
    }

    override fun onTwoFingersDown(centerX: Float, centerY: Float) {
        val tracker = scrollTracker
        if (tracker == null || (state != State.PENDING && state != State.MOVING) ||
            !centerX.isFinite() || !centerY.isFinite()
        ) {
            onSecondFingerDown()
            return
        }
        cancelLongPressTimer()
        state = State.SCROLLING
        lastTapUpMs = NO_TAP
        tracker.start(centerX, centerY)
    }

    override fun onTwoFingersMove(centerX: Float, centerY: Float) {
        if (state != State.SCROLLING) return
        scrollTracker?.move(centerX, centerY)
    }

    override fun onCancel() {
        cancelLongPressTimer()
        endDragIfAny()
        state = State.IDLE
        lastTapUpMs = NO_TAP
    }

    private fun onLongPressTimeout() {
        if (longPress == null || state != State.PENDING || followsTap) return
        state = State.LONG_PRESSED // avant le rappel : le relâchement qui suit ne clique pas
        listener.onRightClick()
    }

    private fun endDrag() {
        state = State.IDLE // avant le rappel : jamais deux onDragEnd pour un même glissement
        listener.onDragEnd()
    }

    private fun endDragIfAny() {
        if (state == State.DRAGGING) endDrag()
    }

    private fun cancelLongPressTimer() {
        longPress?.scheduler?.cancel(longPressTask)
    }

    private fun withinSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy <= slopPx * slopPx // NaN échoue
    }

    companion object {
        /** Délai entre un tap et le toucher suivant pour former un double clic ou un glissement (celui d'Android : 300 ms). */
        const val DEFAULT_DOUBLE_TAP_MS = 300L
        private const val NO_TAP = Long.MIN_VALUE
    }
}
