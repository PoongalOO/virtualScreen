package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.TextView
import fr.webinfoconcept.secondscreen.input.PointerActions
import fr.webinfoconcept.secondscreen.input.PointerMapper
import fr.webinfoconcept.secondscreen.input.TouchInput
import fr.webinfoconcept.secondscreen.profile.PreferencesStore
import fr.webinfoconcept.secondscreen.render.RemoteSurfaceView
import fr.webinfoconcept.secondscreen.session.ConnectionController
import fr.webinfoconcept.secondscreen.session.ConnectionFailure
import fr.webinfoconcept.secondscreen.session.ConnectionState
import fr.webinfoconcept.secondscreen.settings.DisplaySettings
import fr.webinfoconcept.secondscreen.ui.FailureMessages
import fr.webinfoconcept.secondscreen.ui.ImmersiveController
import fr.webinfoconcept.secondscreen.ui.ViewSystemUiHost

/**
 * Écran distant (SS-030 à SS-034, SS-040 à SS-044, SS-052 à SS-054) : la surface plein écran, les entrées tactiles, la
 * barre de commandes et le panneau d'état de la connexion.
 *
 * **La session appartient à [SessionManager]**, pas à cette activité : elle affiche l'écran de la session courante et lui
 * envoie les entrées. Une rotation (paysage/paysage inversé, `configChanges`) ne la recrée pas.
 *
 * ## Vie de la session
 * Elle dure **tant que cet écran est visible**. Quand il ne l'est plus (Accueil, autre application, écran éteint), la
 * session est fermée : pas de service, donc aucun trafic ni thread en arrière-plan. Au retour, le panneau d'état propose
 * **Reconnecter** (SS-054), avec une boîte de saisie du mot de passe si le serveur en exigeait un (il n'est pas
 * conservé, SECURITY.md). L'ouverture du diagnostic depuis la barre ne ferme pas la session.
 *
 * ## Panneau d'état (SS-053)
 * Visible tant que la session n'est pas établie : progression pendant la connexion, message compréhensible et boutons
 * Reconnecter / Fermer après une erreur ou une déconnexion. Le dernier écran reçu reste affiché derrière.
 *
 * ## Plein écran
 * Explicite et mémorisé ([DisplaySettings]) : voir [toggleFullscreen]. Par défaut la barre système reste visible.
 *
 * ## Barre de commandes (SS-052)
 * Clavier, Pointeur (désactivés : SS-046/047 et SS-045), Diagnostic, Plein écran, Déconnexion. Elle s'affiche par la touche **Retour**
 * ou un **tap à trois doigts**. Retour quand elle est visible quitte l'écran : la sortie reste toujours à deux gestes.
 */
class RemoteActivity : Activity(), ConnectionController.Listener {

    private val controller get() = SessionManager.controller

    private lateinit var surface: RemoteSurfaceView
    private lateinit var actions: PointerActions
    private lateinit var touchInput: TouchInput
    private lateinit var immersive: ImmersiveController
    private lateinit var bar: View
    private lateinit var overlay: View
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var reconnect: Button
    private lateinit var fullscreenButton: Button
    private lateinit var settings: DisplaySettings

    /** Vrai quand on quitte cet écran pour le diagnostic : la session doit alors survivre à `onStop`. */
    private var keepSessionOnStop = false

    // Le mode immersif n'est actif que fenêtre au premier plan, session affichée ET plein écran choisi (voir applyImmersive).
    private var windowFocused = false
    private var sessionShown = false
    private var fullscreen = false

    // setOnSystemUiVisibilityChangeListener est déprécié depuis l'API 30 mais reste le seul moyen sur Android 4.2.
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        surface = findViewById(R.id.remote_surface)
        bar = findViewById(R.id.remote_bar)
        overlay = findViewById(R.id.remote_overlay)
        status = findViewById(R.id.remote_status)
        progress = findViewById(R.id.remote_progress)
        reconnect = findViewById(R.id.remote_reconnect)

        // Le mappeur est remplacé à l'établissement de chaque session, selon la taille de l'écran distant.
        actions = PointerActions(PointerMapper(FALLBACK_WIDTH, FALLBACK_HEIGHT), controller.input)
        touchInput = TouchInput(
            actions,
            ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            surface,
            onToggleBar = { toggleBar() }
        )
        surface.setOnTouchListener(touchInput)

        settings = DisplaySettings(PreferencesStore(this, DisplaySettings.FILE_NAME))
        fullscreen = settings.fullscreen
        fullscreenButton = findViewById(R.id.bar_fullscreen)
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        updateFullscreenButton()

        reconnect.setOnClickListener { askReconnect() }
        findViewById<Button>(R.id.remote_close).setOnClickListener { leave() }
        findViewById<Button>(R.id.bar_disconnect).setOnClickListener { leave() }
        findViewById<Button>(R.id.bar_diagnostic).setOnClickListener {
            keepSessionOnStop = true
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }

        immersive = ImmersiveController(ViewSystemUiHost(surface))
        surface.setOnSystemUiVisibilityChangeListener { immersive.onSystemUiVisibilityChange(it) }
    }

    override fun onStart() {
        super.onStart()
        keepSessionOnStop = false
        controller.addListener(this)
        controller.setRenderTarget(surface)
        render(controller.state, controller.failure)
    }

    override fun onStop() {
        touchInput.cancelGesture()
        controller.removeListener(this)
        controller.setRenderTarget(null)
        // Plus visible : la session est fermée (pas de service). Sauf départ vers le diagnostic.
        if (!keepSessionOnStop) controller.disconnect()
        super.onStop()
    }

    override fun onPause() {
        // Un glissement en cours est relâché tant que l'envoyeur tourne encore.
        touchInput.cancelGesture()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        applyImmersive()
        if (!hasFocus) touchInput.cancelGesture() // le système n'envoie pas toujours ACTION_CANCEL
    }

    /**
     * Mode immersif seulement pendant la session. Sur Android 4.2, quand la barre système est masquée, chaque toucher
     * qui la fait réapparaître est annulé (`ACTION_CANCEL`) au lieu d'être livré : **tout toucher après quelques
     * secondes d'inactivité est perdu**. C'est supportable devant l'écran distant (ARCHITECTURE.md, « Mode immersif »),
     * pas sur le panneau d'état ni sur la barre de commandes, dont les boutons doivent répondre du premier coup : on y
     * laisse donc la barre système visible. Le plein écran est en outre un **choix explicite** de l'utilisateur
     * ([toggleFullscreen]) : par défaut la barre système reste visible pendant la session aussi.
     */
    private fun applyImmersive() {
        val wanted = windowFocused && sessionShown && fullscreen && bar.visibility != View.VISIBLE
        if (wanted) immersive.enable() else immersive.disable()
    }

    /**
     * Bascule le mode **plein écran**, mémorisé pour les prochaines sessions. Hors plein écran (défaut) la barre système
     * reste visible et tous les touchers comptent, mais l'écran distant est rogné des 48 lignes du bas. En plein écran il
     * est affiché en entier, mais le premier toucher après quelques secondes d'inactivité est perdu (limite d'Android
     * 4.2) : on le dit à l'utilisateur au moment où il le choisit.
     */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        settings.fullscreen = fullscreen
        updateFullscreenButton()
        if (fullscreen) {
            bar.visibility = View.GONE // le plein écran ne s'applique que barre de commandes masquée
            Toast.makeText(this, R.string.fullscreen_hint, Toast.LENGTH_LONG).show()
        }
        applyImmersive()
    }

    private fun updateFullscreenButton() {
        fullscreenButton.setText(if (fullscreen) R.string.bar_fullscreen_exit else R.string.bar_fullscreen)
    }

    override fun onDestroy() {
        immersive.disable()
        super.onDestroy()
    }

    /** Retour : affiche la barre ; si elle est déjà visible (ou hors session), quitte l'écran. */
    override fun onBackPressed() {
        if (controller.state == ConnectionState.CONNECTED && bar.visibility != View.VISIBLE) {
            toggleBar()
        } else {
            leave()
        }
    }

    // ------------------------------------------------------------------ état de la connexion

    override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) {
        runOnUiThread { if (!isFinishing) render(state, failure) }
    }

    private fun render(state: ConnectionState, failure: ConnectionFailure?) {
        sessionShown = state == ConnectionState.CONNECTED
        applyImmersive()
        if (state == ConnectionState.CONNECTED) {
            attachSession()
            overlay.visibility = View.GONE
            return
        }
        val busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING ||
            state == ConnectionState.NEGOTIATING
        overlay.visibility = View.VISIBLE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        reconnect.visibility = if (busy) View.GONE else View.VISIBLE
        reconnect.isEnabled = controller.lastConnection != null
        bar.visibility = View.GONE
        applyImmersive()

        status.text = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.state_connecting, controller.lastConnection.toString())
            ConnectionState.RECONNECTING -> getString(R.string.state_reconnecting, controller.lastConnection.toString())
            ConnectionState.NEGOTIATING -> getString(R.string.state_negotiating)
            ConnectionState.ERROR ->
                if (failure != null) FailureMessages.text(this, failure) else getString(R.string.state_disconnected)
            else -> getString(if (controller.lastConnection != null) R.string.state_disconnected else R.string.state_no_connection)
        }
    }

    /** Affiche l'écran de la session établie et cale la conversion des touchers sur sa taille. */
    private fun attachSession() {
        val info = controller.session ?: return
        actions.mapper = PointerMapper(info.framebuffer.width, info.framebuffer.height)
        surface.setFramebuffer(info.framebuffer)
    }

    // ------------------------------------------------------------------ actions

    private fun toggleBar() {
        if (controller.state != ConnectionState.CONNECTED) return
        bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        applyImmersive()
    }

    /** Ferme la session et revient à la liste des connexions. */
    private fun leave() {
        controller.disconnect()
        finish()
    }

    /** Relance la connexion ; demande d'abord le mot de passe si le serveur en exigeait un (il n'est pas conservé). */
    private fun askReconnect() {
        if (!controller.reconnectNeedsPassword) {
            controller.reconnect()
            return
        }
        val field = EditText(this)
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        field.isSaveEnabled = false
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_password_title)
            .setMessage(R.string.remote_password_message)
            .setView(field)
            .setPositiveButton(R.string.remote_password_ok) { _, _ ->
                val editable = field.text
                val chars = CharArray(editable.length)
                editable.getChars(0, chars.size, chars, 0)
                editable.clear()
                controller.reconnect(if (chars.isEmpty()) null else chars) // efface `chars`
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        // Taille avant la première session : celle de la tablette cible (nominale, AGENTS.md).
        const val FALLBACK_WIDTH = 1280
        const val FALLBACK_HEIGHT = 800
    }
}
