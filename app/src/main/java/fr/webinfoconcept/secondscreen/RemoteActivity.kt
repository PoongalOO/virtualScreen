package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.os.Bundle
import android.util.Log
import fr.webinfoconcept.secondscreen.render.RemoteSurfaceView
import fr.webinfoconcept.secondscreen.render.RenderTestDriver
import fr.webinfoconcept.secondscreen.render.RenderTestPattern
import fr.webinfoconcept.secondscreen.ui.ImmersiveController
import fr.webinfoconcept.secondscreen.ui.ViewSystemUiHost

/**
 * Écran distant : héberge le [RemoteSurfaceView] en plein écran (SS-030, SS-031, SS-032, SS-034).
 *
 * **Provisoire** : en attendant la connexion réelle (SS-054), affiche un motif de test ([RenderTestPattern]) et, si
 * [EXTRA_ANIMATE] est demandé, y déplace un carré avec [RenderTestDriver], qui emprunte le même chemin que le
 * décodeur (thread d'arrière-plan -> zone modifiée -> rendu). Le contrôleur de connexion remplacera tout cela par le
 * framebuffer alimenté par le serveur.
 *
 * - **Paysage** (manifeste) et **mode immersif** ([ImmersiveController]) : la surface fait alors exactement la taille de
 *   l'écran, condition du rendu 1:1 sans rien rogner.
 * - Le mode immersif est (ré)activé quand la fenêtre prend le focus : le système efface les drapeaux quand elle le perd.
 */
class RemoteActivity : Activity() {

    private lateinit var surface: RemoteSurfaceView
    private lateinit var immersive: ImmersiveController
    private var driver: RenderTestDriver? = null

    // setOnSystemUiVisibilityChangeListener est déprécié depuis l'API 30 mais reste le seul moyen sur Android 4.2.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = RemoteSurfaceView(this)
        setContentView(surface)

        val framebuffer = RenderTestPattern.create()
        surface.setFramebuffer(framebuffer)
        if (intent.getBooleanExtra(EXTRA_ANIMATE, false)) {
            driver = RenderTestDriver(framebuffer, surface, maxFrames = intent.getIntExtra(EXTRA_FRAMES, 0))
        }

        immersive = ImmersiveController(ViewSystemUiHost(surface))
        surface.setOnSystemUiVisibilityChangeListener { immersive.onSystemUiVisibilityChange(it) }
    }

    override fun onStart() {
        super.onStart()
        driver?.start()
    }

    override fun onStop() {
        driver?.let {
            it.stop()
            // Une seule ligne, à l'arrêt : cadence atteinte (mesure de SS-031). Aucune donnée sensible.
            Log.i(TAG, "rendu de test : ${it.frames} images en ${it.elapsedMs} ms")
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive.enable() else immersive.disable()
    }

    override fun onDestroy() {
        immersive.disable()
        super.onDestroy()
    }

    companion object {
        /** Anime un carré sur le motif de test (chemin de mise à jour identique à celui du décodeur). */
        const val EXTRA_ANIMATE = "fr.webinfoconcept.secondscreen.ANIMATE"

        /** Nombre d'images après lequel l'animation s'arrête d'elle-même (0 = sans fin). */
        const val EXTRA_FRAMES = "fr.webinfoconcept.secondscreen.FRAMES"

        private const val TAG = "SecondScreen"
    }
}
