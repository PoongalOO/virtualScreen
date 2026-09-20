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
 * Reçoit le défilement à deux doigts reconnu par [TouchGestureDetector] (SS-044). Les crans sont **signés** :
 * `clicksY > 0` = molette vers le bas (le contenu défile vers le bas de la page), `clicksY < 0` = vers le haut,
 * `clicksX > 0` = vers la droite, `clicksX < 0` = vers la gauche.
 */
interface ScrollListener {
    /** Deux doigts sont posés, leur centre est en ([x], [y]) (pixels de la vue) : le défilement commence là. */
    fun onScrollStart(x: Float, y: Float)

    /** Le centre des doigts a parcouru assez de chemin pour [clicksX] et [clicksY] crans (un seul des deux est non nul). */
    fun onScroll(clicksX: Int, clicksY: Int)
}

/**
 * Réglage du **défilement à deux doigts** (SS-044).
 *
 * @param stepPx chemin du centre des deux doigts pour un cran de molette, en pixels de la vue, de [MIN_STEP_PX] à
 *   [MAX_STEP_PX]. Plus il est petit, plus le défilement est rapide. [DEFAULT_STEP_PX] est un premier réglage **non
 *   ajusté sur une vraie application distante** ; voir ARCHITECTURE.md.
 * @param natural `true` (défaut) : le contenu **suit les doigts**, comme sur un téléphone (doigts vers le haut =
 *   molette vers le bas). `false` : sens d'une molette de souris (doigts vers le haut = molette vers le haut).
 */
class Scroll(
    val listener: ScrollListener,
    val stepPx: Float = DEFAULT_STEP_PX,
    val natural: Boolean = true
) {
    init {
        require(stepPx >= MIN_STEP_PX && stepPx <= MAX_STEP_PX) {
            "pas de défilement hors de $MIN_STEP_PX..$MAX_STEP_PX px : $stepPx"
        }
    }

    companion object {
        const val DEFAULT_STEP_PX = 40f
        const val MIN_STEP_PX = 8f
        const val MAX_STEP_PX = 400f

        /** Crans maximum pour un seul événement tactile : un saut du capteur ne doit pas inonder le serveur. */
        const val MAX_CLICKS_PER_EVENT = 8
    }
}

/** Programme une action différée sur le thread qui traite les événements tactiles (le thread UI). */
interface DelayScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun cancel(task: Runnable)
}

/**
 * Réglage de l'**appui long** (SS-043) : un doigt qui reste posé sans bouger pendant [thresholdMs] déclenche
 * [onLongPress], qui envoie un clic droit.
 *
 * @param thresholdMs durée d'appui, de [MIN_THRESHOLD_MS] à [MAX_THRESHOLD_MS] ; par défaut le délai d'appui long
 *   d'Android ([DEFAULT_THRESHOLD_MS]), que l'appelant remplace par celui de la plateforme
 *   (`ViewConfiguration.getLongPressTimeout()`) ou par un réglage de l'utilisateur.
 * @param scheduler si présent, l'appui long se déclenche **dès que le seuil est atteint, doigt encore posé** (comme
 *   partout dans Android). Sans lui, il se déclenche au relâchement d'un contact qui a duré au moins [thresholdMs].
 *   Dans les deux cas la décision repose sur l'horodatage des événements, pas sur la précision d'un minuteur.
 * @param onLongPress reçoit la position du toucher initial, en pixels de la vue.
 */
class LongPress(
    val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    val scheduler: DelayScheduler? = null,
    val onLongPress: (x: Float, y: Float) -> Unit
) {
    init {
        require(thresholdMs in MIN_THRESHOLD_MS..MAX_THRESHOLD_MS) {
            "seuil d'appui long hors de $MIN_THRESHOLD_MS..$MAX_THRESHOLD_MS ms : $thresholdMs"
        }
    }

    companion object {
        /** Délai d'appui long d'Android (`ViewConfiguration.getLongPressTimeout()` par défaut). */
        const val DEFAULT_THRESHOLD_MS = 500L

        /** En dessous, un tap un peu lent deviendrait un clic droit. */
        const val MIN_THRESHOLD_MS = 200L

        /** Au-dessus, l'appui long est pratiquement inatteignable. */
        const val MAX_THRESHOLD_MS = 3_000L
    }
}

/**
 * Reconnaît un **tap**, un **appui long**, un **glissement** ou un **défilement à deux doigts** dans un flux d'événements tactiles (SS-041, SS-042). Machine à états pure,
 * sans dépendance Android : testable sur la JVM. L'adaptation depuis `MotionEvent` est dans [TouchInput].
 *
 * ## Tap (SS-041)
 * Un tap déclenche [onTap] **une seule fois, au relâchement** (`onUp`), à la position où le doigt a touché. Le clic
 * n'est donc jamais émis à l'appui : un appui qui devient un geste ne produit jamais de clic gauche parasite. Pas
 * de tap :
 * - si le doigt **bouge** de plus de [slopPx] pixels (c'est alors un glissement) ;
 * - si le contact dure trop longtemps : c'est un appui long avec un [longPress], sinon plus de [maxTapMs] ;
 * - si un **deuxième doigt** se pose (défilement à deux doigts : SS-044) ;
 * - si le système **annule** le geste ([onCancel]).
 *
 * ## Appui long (SS-043)
 * Avec un [longPress], un doigt qui reste dans [slopPx] pendant [LongPress.thresholdMs] déclenche `onLongPress`
 * **une seule fois** (clic droit), puis **le reste du geste est ignoré** : ni tap ni glissement, donc **aucun clic
 * gauche parasite** au relâchement, même si le doigt bouge ensuite. Bouger au-delà du seuil de mouvement avant le
 * délai annule l'appui long (c'est un glissement) ; un deuxième doigt ou une annulation aussi. Un contact de
 * durée `>= thresholdMs` est un appui long, de durée `< thresholdMs` un tap : **aucune zone morte** entre les deux,
 * quel que soit le seuil configuré (`maxTapMs` ne s'applique alors plus).
 *
 * ## Défilement à deux doigts (SS-044)
 * Avec un [scroll], un deuxième doigt posé pendant que le premier n'a ni bougé de plus de [slopPx], ni déclenché
 * l'appui long, démarre un défilement ([onTwoFingersDown]). Le **centre des deux doigts** ([onTwoFingersMove]) est
 * converti en crans de molette : un cran par [Scroll.stepPx] pixels parcourus, le reste étant conservé pour le
 * déplacement suivant (rien n'est perdu, rien n'est arrondi à l'excès). **Un seul axe à la fois** : à chaque
 * déplacement on ne garde que l'axe où le chemin cumulé est le plus grand et on oublie l'autre, ainsi un léger
 * dérapage latéral pendant un défilement vertical ne produit jamais de cran horizontal. Les crans d'un événement
 * sont bornés à [Scroll.MAX_CLICKS_PER_EVENT].
 * Un défilement n'émet **jamais** de clic ni de tap : lever les doigts ne clique pas. Il se termine dès que le nombre de
 * doigts change (un doigt levé ou un troisième posé, via [onCancel] ou [onSecondFingerDown]) et ne reprend pas
 * avant le prochain `onDown`. Un deuxième doigt posé pendant un glissement le relâche et n'ouvre pas de défilement ;
 * après un appui long, il est ignoré.
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
 * @param maxTapMs durée maximale d'un tap, en ms, **quand il n'y a pas d'appui long** (un contact plus long est alors
 *   ignoré). Avec un [longPress], c'est son seuil qui sépare le tap de l'appui long. Ne limite pas le début d'un
 *   glissement.
 * @param dragListener reçoit les glissements ; `null` pour ne reconnaître que les taps.
 * @param longPress réglage de l'appui long ; `null` pour ne pas le reconnaître.
 * @param scroll réglage du défilement à deux doigts ; `null` pour ne pas le reconnaître (un deuxième doigt annule
 *   alors simplement le geste).
 * @param onTap reçoit la position du toucher initial, en pixels de la vue.
 */
class TouchGestureDetector(
    private val slopPx: Float,
    private val maxTapMs: Long = DEFAULT_MAX_TAP_MS,
    private val dragListener: DragListener? = null,
    private val longPress: LongPress? = null,
    private val scroll: Scroll? = null,
    private val onTap: (x: Float, y: Float) -> Unit
) {
    init {
        require(slopPx >= 0f && !slopPx.isNaN()) { "slop invalide : $slopPx" }
        require(maxTapMs > 0) { "maxTapMs doit être > 0 : $maxTapMs" }
    }

    private enum class State { IDLE, PENDING, DRAGGING, LONG_PRESSED, SCROLLING }

    // Alloué une fois : programmer l'appui long à chaque toucher ne crée aucun objet.
    private val longPressTask = Runnable { onLongPressTimeout() }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f

    // Défilement : dernier centre des deux doigts et chemin cumulé pas encore converti en crans.
    private var scrollX = 0f
    private var scrollY = 0f
    private var accumX = 0f
    private var accumY = 0f

    /** `true` pendant un défilement à deux doigts. */
    val isScrolling: Boolean
        get() = state == State.SCROLLING

    /** `true` tant qu'un geste est un tap possible (doigt posé, pas encore de mouvement significatif). */
    val isTracking: Boolean
        get() = state == State.PENDING

    /** `true` une fois l'appui long déclenché, jusqu'à la fin du geste. */
    val isLongPressed: Boolean
        get() = state == State.LONG_PRESSED

    /** `true` entre [DragListener.onDragStart] et [DragListener.onDragEnd]. */
    val isDragging: Boolean
        get() = state == State.DRAGGING

    /** Premier doigt posé en ([x], [y]) à l'instant [timeMs] (horloge monotone, ms). */
    fun onDown(x: Float, y: Float, timeMs: Long) {
        endDragIfAny() // un relâchement perdu ne doit pas laisser le bouton enfoncé
        cancelLongPressTimer()
        state = State.PENDING
        downX = x
        downY = y
        downTimeMs = timeMs
        lastX = x
        lastY = y
        longPress?.scheduler?.postDelayed(longPressTask, longPress.thresholdMs)
    }

    /** Le doigt bouge : au-delà du seuil ce n'est plus un tap, c'est un glissement (s'il y a un [dragListener]). */
    fun onMove(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        when (state) {
            State.PENDING -> if (!withinSlop(x, y)) startDrag(x, y)
            State.DRAGGING -> moveDrag(x, y)
            // après un appui long le reste du geste est ignoré ; pendant un défilement, seul le centre compte
            State.IDLE, State.LONG_PRESSED, State.SCROLLING -> Unit
        }
    }

    /**
     * Le deuxième doigt vient d'être posé et le **centre des deux doigts** est en ([centerX], [centerY]) : commence un
     * défilement si le geste s'y prête (premier doigt posé sans mouvement significatif et sans appui long, avec un
     * [scroll]). Sinon se comporte comme [onSecondFingerDown] : le geste est abandonné et un glissement en cours est
     * relâché.
     */
    fun onTwoFingersDown(centerX: Float, centerY: Float) {
        val config = scroll
        if (config == null || state != State.PENDING || !centerX.isFinite() || !centerY.isFinite()) {
            onSecondFingerDown()
            return
        }
        cancelLongPressTimer()
        state = State.SCROLLING
        scrollX = centerX
        scrollY = centerY
        accumX = 0f
        accumY = 0f
        config.listener.onScrollStart(centerX, centerY)
    }

    /** Le centre des deux doigts se déplace en ([centerX], [centerY]) : émet les crans de molette correspondants. */
    fun onTwoFingersMove(centerX: Float, centerY: Float) {
        val config = scroll ?: return
        if (state != State.SCROLLING || !centerX.isFinite() || !centerY.isFinite()) return
        accumX += centerX - scrollX
        accumY += centerY - scrollY
        scrollX = centerX
        scrollY = centerY

        // Un seul axe : celui du plus grand chemin cumulé ; l'autre est oublié (pas de dérapage latéral).
        if (Math.abs(accumY) >= Math.abs(accumX)) accumX = 0f else accumY = 0f
        val stepsX = (accumX / config.stepPx).toInt() // vers zéro
        val stepsY = (accumY / config.stepPx).toInt()
        if (stepsX == 0 && stepsY == 0) return
        val limit = Scroll.MAX_CLICKS_PER_EVENT
        // Un saut énorme (capteur, doigt qui glisse hors de la dalle) : on plafonne et on oublie le reste.
        accumX = if (Math.abs(stepsX) > limit) 0f else accumX - stepsX * config.stepPx
        accumY = if (Math.abs(stepsY) > limit) 0f else accumY - stepsY * config.stepPx

        // Sens : « naturel » = le contenu suit les doigts, donc doigts vers le haut => molette vers le bas.
        val sign = if (config.natural) -1 else 1
        config.listener.onScroll(
            (sign * stepsX).coerceIn(-limit, limit),
            (sign * stepsY).coerceIn(-limit, limit)
        )
    }

    /**
     * Un doigt supplémentaire est posé sans qu'un défilement puisse commencer (troisième doigt, défilement non
     * reconnu) : ce n'est ni un tap ni un glissement ; un glissement ou un défilement en cours se termine.
     */
    fun onSecondFingerDown() {
        cancelLongPressTimer()
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
            State.LONG_PRESSED, State.SCROLLING -> {
                state = State.IDLE // l'appui long ou le défilement a tout dit : pas de tap au relâchement
                return false
            }
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
                cancelLongPressTimer()
                state = State.IDLE // avant le rappel : un second onUp ne peut jamais émettre deux fois
                val duration = timeMs - downTimeMs
                if (duration < 0 || !withinSlop(x, y)) return false
                if (longPress != null) {
                    // Décision sur l'horodatage : un minuteur en retard ne transforme pas un appui long en rien.
                    if (duration >= longPress.thresholdMs) {
                        longPress.onLongPress(downX, downY)
                        return false
                    }
                } else if (duration > maxTapMs) {
                    return false
                }
                onTap(downX, downY)
                return true
            }
        }
    }

    /** Geste annulé (`ACTION_CANCEL`, perte du focus...) : jamais de tap ; un glissement en cours est relâché. */
    fun onCancel() {
        cancelLongPressTimer()
        endDragIfAny()
        state = State.IDLE
    }

    /** Le minuteur d'appui long a expiré, doigt encore posé et immobile : c'est un appui long. */
    private fun onLongPressTimeout() {
        val config = longPress ?: return
        if (state != State.PENDING) return
        state = State.LONG_PRESSED // avant le rappel : le relâchement qui suit ne clique pas
        config.onLongPress(downX, downY)
    }

    private fun cancelLongPressTimer() {
        longPress?.scheduler?.cancel(longPressTask)
    }

    private fun startDrag(x: Float, y: Float) {
        cancelLongPressTimer()
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
        /** Durée maximale d'un tap sans appui long : le délai d'appui long d'Android. */
        const val DEFAULT_MAX_TAP_MS = 500L
    }
}
