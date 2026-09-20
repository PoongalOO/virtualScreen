package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewConfiguration
import fr.webinfoconcept.secondscreen.input.PointerActions
import fr.webinfoconcept.secondscreen.input.PointerMapper
import fr.webinfoconcept.secondscreen.input.PointerSender
import fr.webinfoconcept.secondscreen.input.TouchInput
import fr.webinfoconcept.secondscreen.render.RemoteSurfaceView
import fr.webinfoconcept.secondscreen.render.RenderTestDriver
import fr.webinfoconcept.secondscreen.render.RenderTestPattern
import fr.webinfoconcept.secondscreen.ui.ImmersiveController
import fr.webinfoconcept.secondscreen.ui.ViewSystemUiHost
import java.util.concurrent.atomic.AtomicInteger

/**
 * Écran distant : héberge le [RemoteSurfaceView] en plein écran (SS-030, SS-031, SS-032, SS-034) et transmet les taps
 * comme clics gauche et les glissements comme déplacements bouton enfoncé (SS-040, SS-041, SS-042).
 *
 * **Provisoire** : en attendant la connexion réelle (SS-054), affiche un motif de test ([RenderTestPattern]) et, si
 * [EXTRA_ANIMATE] est demandé, y déplace un carré avec [RenderTestDriver], qui emprunte le même chemin que le
 * décodeur (thread d'arrière-plan -> zone modifiée -> rendu). Le contrôleur de connexion remplacera tout cela par le
 * framebuffer alimenté par le serveur. De même les messages de pointeur ne partent pas encore vers un serveur : ils
 * suivent le chemin réel (tap -> pixel du framebuffer -> `PointerEvent` -> file -> thread d'envoi) mais l'écriture
 * finale ne fait que les compter. SS-054 y branchera `PointerSender.forSocket`.
 *
 * - **Paysage** (manifeste) et **mode immersif** ([ImmersiveController]) : la surface fait alors exactement la taille de
 *   l'écran, condition du rendu 1:1 sans rien rogner.
 * - Le mode immersif est (ré)activé quand la fenêtre prend le focus : le système efface les drapeaux quand elle le perd.
 */
class RemoteActivity : Activity() {

    private lateinit var surface: RemoteSurfaceView
    private lateinit var immersive: ImmersiveController
    private var driver: RenderTestDriver? = null
    private var pointerSender: PointerSender? = null
    private var touchInput: TouchInput? = null
    private val messagesSent = AtomicInteger()

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

        // Provisoire : l'écriture finale compte les messages au lieu de les envoyer (voir la doc de la classe).
        val sender = PointerSender { messagesSent.incrementAndGet() }
        pointerSender = sender
        val actions = PointerActions(PointerMapper(framebuffer.width, framebuffer.height), sender)
        val input = TouchInput(actions, ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        touchInput = input
        surface.setOnTouchListener(input)

        immersive = ImmersiveController(ViewSystemUiHost(surface))
        surface.setOnSystemUiVisibilityChangeListener { immersive.onSystemUiVisibilityChange(it) }
    }

    override fun onStart() {
        super.onStart()
        pointerSender?.start()
        driver?.start()
    }

    override fun onPause() {
        // Un glissement en cours est relâché tant que l'envoyeur tourne encore (il s'arrête dans onStop).
        touchInput?.cancelGesture()
        super.onPause()
    }

    override fun onStop() {
        driver?.let {
            it.stop()
            // Une seule ligne, à l'arrêt : cadence atteinte (mesure de SS-031). Aucune donnée sensible.
            Log.i(TAG, "rendu de test : ${it.frames} images en ${it.elapsedMs} ms")
        }
        pointerSender?.let {
            it.stop()
            Log.i(TAG, "entrées de test : ${messagesSent.get()} message(s) traité(s), ${it.droppedCount} perdu(s)")
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive.enable() else immersive.disable()
        if (!hasFocus) touchInput?.cancelGesture() // le système n'envoie pas toujours ACTION_CANCEL
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
