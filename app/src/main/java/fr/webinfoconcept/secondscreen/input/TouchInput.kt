package fr.webinfoconcept.secondscreen.input

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration

/** Adaptateur de [DelayScheduler] sur une [View] : les tâches s'exécutent sur le thread UI. */
class ViewDelayScheduler(private val view: View) : DelayScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) {
        view.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) {
        view.removeCallbacks(task)
    }
}

/**
 * Relie les événements tactiles de la vue distante au serveur (SS-041 à SS-044) : un tap devient un clic gauche,
 * un glissement un déplacement avec le bouton gauche maintenu, un appui long un clic droit, un défilement à deux
 * doigts des crans de molette.
 *
 * @param view la vue qui reçoit les événements : sert de minuteur pour l'appui long et de support au retour haptique.
 * @param onToggleBar appelé sur un tap à trois doigts (SS-052) : affiche ou masque la barre de commandes.
 * @param scrollStepPx chemin du centre des deux doigts pour un cran de molette ; @param naturalScrolling le contenu
 *   suit les doigts (défaut) ou sens d'une molette de souris (SS-044).
 * @param longPressMs durée d'appui qui déclenche le clic droit ; par défaut celle de la plateforme
 *   (`ViewConfiguration.getLongPressTimeout()`, 500 ms sauf réglage d'accessibilité de l'utilisateur).
 *
 * Adaptateur minimal de `MotionEvent` vers [TouchGestureDetector] : toute la logique est dans ce dernier, testé sur la JVM.
 * Seules des API disponibles dès l'API 1 sont utilisées (`getActionMasked`, API 8).
 *
 * - `ACTION_DOWN` : premier doigt ; `ACTION_POINTER_DOWN` : doigt supplémentaire (annule le tap ; au deuxième doigt
 *   exactement, ouvre un défilement) ; `ACTION_POINTER_UP` : le nombre de doigts change, fin du défilement ;
 * - `ACTION_UP` : le tap est émis ici, et seulement ici ; `ACTION_CANCEL` : jamais de tap ;
 * - l'appui long est déclenché par un minuteur de la vue, doigt encore posé ;
 * - `ACTION_MOVE` : n'utilise que le doigt d'indice 0, suffisant puisque tout second doigt a déjà annulé le geste.
 *
 * Un tap reconnu appelle aussi `View.performClick()` (événement d'accessibilité « clic » ; sans écouteur de clic
 * sur la vue, cela n'a aucun autre effet, ni son ni double envoi).
 *
 * L'événement est consommé (`true`) : la vue n'en fait rien d'autre, donc aucun second traitement possible.
 *
 * **Limite Android 4.2** (ARCHITECTURE.md, « Mode immersif ») : quand la barre système est masquée, le toucher qui la
 * fait réapparaître n'est pas transmis à l'application ; ce premier toucher est donc perdu.
 *
 * **Correctif GT-P5110** (SS-088) : toute position est relue via [point], qui annule le défaut de rotation mesuré sur
 * cet appareil ([TouchRotationQuirk]) avant que quoi que ce soit d'autre ne la voie.
 */
class TouchInput(
    private val actions: PointerActions,
    private val touchpadActions: TouchpadActions,
    slopPx: Float,
    private val view: View,
    longPressMs: Long = ViewConfiguration.getLongPressTimeout().toLong(),
    scrollStepPx: Float = Scroll.DEFAULT_STEP_PX,
    naturalScrolling: Boolean = true,
    private val onToggleBar: (() -> Unit)? = null
) : View.OnTouchListener {

    private val longPress = LongPress(longPressMs, ViewDelayScheduler(view)) { x, y ->
        if (actions.rightClick(x, y)) {
            // Confirme le déclenchement au doigt. Sans effet (et sans erreur) sur un appareil sans vibreur, comme
            // la GT-P5110 ; aucune permission n'est requise pour cette API (API 3).
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val threeFingerTap = ThreeFingerTap(maxDriftPx = slopPx * 4f)

    private val direct = TouchGestureDetector(
        slopPx,
        dragListener = actions,
        longPress = longPress,
        scroll = Scroll(actions, scrollStepPx, naturalScrolling)
    ) { x, y -> actions.tap(x, y) }

    // Touchpad : le clic droit d'un appui long agit là où est le pointeur, pas sous le doigt.
    private val touchpadLongPress = LongPress(longPressMs, ViewDelayScheduler(view)) { _, _ ->
        touchpadActions.onRightClick()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private val touchpad = TouchpadDetector(
        slopPx,
        touchpadActions,
        longPress = touchpadLongPress,
        scroll = Scroll(touchpadActions, scrollStepPx, naturalScrolling)
    )

    /** Interpréteur en service : le mode direct (défaut) ou le mode touchpad. */
    private var detector: TouchGestures = direct

    /** `true` en mode touchpad (SS-045). */
    val isTouchpad: Boolean
        get() = detector === touchpad

    /**
     * Bascule entre le mode direct et le mode touchpad. Le geste en cours est annulé d'abord (un bouton enfoncé est
     * relâché), pour qu'aucun état ne passe d'un mode à l'autre.
     */
    fun setTouchpad(enabled: Boolean) {
        if (enabled == isTouchpad) return
        detector.onCancel()
        detector = if (enabled) touchpad else direct
    }

    /**
     * Abandonne le geste en cours : un glissement est relâché côté serveur. À appeler quand la vue cesse de recevoir
     * les événements (perte du focus, mise en pause) : Android n'envoie pas toujours `ACTION_CANCEL` alors.
     */
    fun cancelGesture() {
        threeFingerTap.cancel()
        detector.onCancel()
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = point(event, 0)
                detector.onDown(x, y, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (threeFingerTap.isTracking && event.pointerCount == 3) {
                    val (cx, cy) = centerOf(event, 3)
                    threeFingerTap.onMove(cx, cy)
                }
                if (detector.isScrolling && event.pointerCount >= 2) {
                    val (cx, cy) = centerOf(event, 2)
                    detector.onTwoFingersMove(cx, cy)
                } else {
                    val (x, y) = point(event, 0)
                    detector.onMove(x, y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Exactement deux doigts : un défilement peut commencer ; trois ou plus : geste abandonné.
                if (event.pointerCount == 2) {
                    val (cx, cy) = centerOf(event, 2)
                    detector.onTwoFingersDown(cx, cy)
                } else {
                    detector.onSecondFingerDown()
                }
                if (event.pointerCount == 3) {
                    val (cx, cy) = centerOf(event, 3)
                    threeFingerTap.onThreeFingersDown(cx, cy, event.eventTime)
                } else {
                    threeFingerTap.cancel() // un quatrième doigt n'est pas un tap à trois doigts
                }
            }
            // Un doigt se lève alors qu'il en reste : le nombre de doigts change, le geste (défilement) se termine.
            MotionEvent.ACTION_POINTER_UP -> {
                detector.onCancel()
                if (threeFingerTap.onFingerUp(event.eventTime)) onToggleBar?.invoke()
            }
            MotionEvent.ACTION_UP -> {
                threeFingerTap.cancel()
                val (x, y) = point(event, 0)
                if (detector.onUp(x, y, event.eventTime)) view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                threeFingerTap.cancel()
                detector.onCancel()
            }
        }
        return true
    }

    /**
     * Position du doigt [i] de [event], **corrigée du défaut de rotation de la GT-P5110** (SS-088) : tout le reste du
     * code ne doit jamais lire `event.getX/getY` directement. Relit l'orientation à chaque appel (`View.getDisplay()`,
     * API 17) : bien moins fréquent qu'un rendu, le coût est négligeable, et une vue non encore attachée à une fenêtre
     * (`display == null`) est traitée comme non affectée par le défaut.
     */
    private fun point(event: MotionEvent, i: Int): Pair<Float, Float> {
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        return TouchRotationQuirk.correct(event.getX(i), event.getY(i), view.width, view.height, rotation)
    }

    /** Centre des [count] premiers doigts, chacun corrigé par [point]. */
    private fun centerOf(event: MotionEvent, count: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until count) {
            val (px, py) = point(event, i)
            x += px
            y += py
        }
        return Pair(x / count, y / count)
    }
}
