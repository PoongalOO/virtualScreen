package fr.webinfoconcept.secondscreen.input

import android.view.MotionEvent
import android.view.View

/**
 * Relie les événements tactiles de la vue distante au serveur (SS-041, SS-042) : un tap devient un clic gauche, un
 * glissement un déplacement avec le bouton gauche maintenu.
 *
 * Adaptateur minimal de `MotionEvent` vers [TouchGestureDetector] : toute la logique est dans ce dernier, testé sur la JVM.
 * Seules des API disponibles dès l'API 1 sont utilisées (`getActionMasked`, API 8).
 *
 * - `ACTION_DOWN` : premier doigt ; `ACTION_POINTER_DOWN` : doigt supplémentaire (annule le tap) ;
 * - `ACTION_UP` : le tap est émis ici, et seulement ici ; `ACTION_CANCEL` : jamais de tap ;
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
class TouchInput(private val actions: PointerActions, slopPx: Float) : View.OnTouchListener {

    private val detector = TouchGestureDetector(slopPx, dragListener = actions) { x, y -> actions.tap(x, y) }

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
