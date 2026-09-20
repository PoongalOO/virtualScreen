package fr.webinfoconcept.secondscreen

import fr.webinfoconcept.secondscreen.session.ConnectionController

/**
 * Le [ConnectionController] unique de l'application (SS-054). Il vit autant que le processus : l'écran de connexion
 * lance la session, l'écran distant l'affiche, et une rotation ou un changement d'écran ne la coupe pas. Il n'y a **pas
 * de service** : quand l'écran distant n'est plus visible, la session est fermée (voir `RemoteActivity`), donc aucun
 * trafic ni thread ne subsiste en arrière-plan.
 */
object SessionManager {
    val controller: ConnectionController by lazy { ConnectionController() }
}
