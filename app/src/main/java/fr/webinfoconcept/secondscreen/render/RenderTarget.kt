package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.protocol.RectangleListener

/**
 * Ce que le décodeur (ou un pilote de test) utilise pour faire afficher ce qu'il vient d'écrire dans le
 * framebuffer (SS-031). Implémenté par `RemoteSurfaceView` ; abstrait pour tester ce qui l'alimente sans Android.
 *
 * Protocole, sur le thread qui écrit dans le framebuffer :
 * 1. écrire les pixels d'un rectangle, puis le signaler à [rectangleListener] (**dans cet ordre** : c'est ce qui
 *    garantit que le rendu voit des pixels terminés) ;
 * 2. répéter pour chaque rectangle du `FramebufferUpdate` ;
 * 3. appeler [onFramebufferUpdated] **une fois**, quand tout le message est appliqué.
 */
interface RenderTarget {
    /** Signale chaque rectangle décodé. Sans allocation, ne dessine pas. */
    val rectangleListener: RectangleListener

    /** Fin d'un `FramebufferUpdate` : dessine la zone modifiée. */
    fun onFramebufferUpdated()
}
