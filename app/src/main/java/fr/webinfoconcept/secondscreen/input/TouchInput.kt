package fr.webinfoconcept.secondscreen.input

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
 * Relie les événements tactiles de la vue distante au serveur (SS-041, SS-042, SS-043) : un tap devient un clic gauche,
 * un glissement un déplacement avec le bouton gauche maintenu, un appui long un clic droit.
 *
 * @param view la vue qui reçoit les événements : sert de minuteur pour l'appui long et de support au retour haptique.
 * @param longPressMs durée d'appui qui déclenche le clic droit ; par défaut celle de la plateforme
 *   (`ViewConfiguration.getLongPressTimeout()`, 500 ms sauf réglage d'accessibilité de l'utilisateur).
 *
 * Adaptateur minimal de `MotionEvent` vers [TouchGestureDetector] : toute la logique est dans ce dernier, testé sur la JVM.
 * Seules des API disponibles dès l'API 1 sont utilisées (`getActionMasked`, API 8).
 *
 * - `ACTION_DOWN` : premier doigt ; `ACTION_POINTER_DOWN` : doigt supplémentaire (annule le tap) ;
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
 */
class TouchInput(
    private val actions: PointerActions,
    slopPx: Float,
    private val view: View,
    longPressMs: Long = ViewConfiguration.getLongPressTimeout().toLong()
) : View.OnTouchListener {

    private val longPress = LongPress(longPressMs, ViewDelayScheduler(view)) { x, y ->
        if (actions.rightClick(x, y)) {
            // Confirme le déclenchement au doigt. Sans effet (et sans erreur) sur un appareil sans vibreur, comme
            // la GT-P5110 ; aucune permission n'est requise pour cette API (API 3).
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val detector = TouchGestureDetector(slopPx, dragListener = actions, longPress = longPress) { x, y ->
        actions.tap(x, y)
    }

    /**
     * Abandonne le geste en cours : un glissement est relâché côté serveur. À appeler quand la vue cesse de recevoir
     * les événements (perte du focus, mise en pause) : Android n'envoie pas toujours `ACTION_CANCEL` alors.
     */
    fun cancelGesture() = detector.onCancel()

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> detector.onDown(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_MOVE -> detector.onMove(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN -> detector.onSecondFingerDown()
            MotionEvent.ACTION_UP -> if (detector.onUp(event.x, event.y, event.eventTime)) view.performClick()
            MotionEvent.ACTION_CANCEL -> detector.onCancel()
        }
        return true
    }
}
