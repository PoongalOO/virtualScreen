package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.os.Bundle
import fr.webinfoconcept.secondscreen.render.RemoteSurfaceView
import fr.webinfoconcept.secondscreen.render.RenderTestPattern

/**
 * Écran distant : héberge le [RemoteSurfaceView] en plein écran (SS-030).
 *
 * **Provisoire** : en attendant la connexion réelle (SS-031, SS-050+), affiche un motif de test statique
 * ([RenderTestPattern]) pour valider le rendu sur l'appareil. Le contrôleur de connexion remplacera ce
 * motif par le framebuffer alimenté par le serveur.
 */
class RemoteActivity : Activity() {

    private lateinit var surface: RemoteSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = RemoteSurfaceView(this)
        setContentView(surface)
        surface.setFramebuffer(RenderTestPattern.create())
    }
}
