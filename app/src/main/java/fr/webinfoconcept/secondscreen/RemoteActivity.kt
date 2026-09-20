package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import android.widget.ToggleButton
import android.widget.TextView
import fr.webinfoconcept.secondscreen.input.KeyForwarder
import fr.webinfoconcept.secondscreen.input.KeyboardInput
import fr.webinfoconcept.secondscreen.input.KeyboardInputView
import fr.webinfoconcept.secondscreen.input.Keysyms
import fr.webinfoconcept.secondscreen.input.PointerActions
import fr.webinfoconcept.secondscreen.input.PointerMapper
import fr.webinfoconcept.secondscreen.input.PointerPosition
import fr.webinfoconcept.secondscreen.input.TouchpadActions
import fr.webinfoconcept.secondscreen.input.TouchInput
import fr.webinfoconcept.secondscreen.profile.PreferencesStore
import fr.webinfoconcept.secondscreen.render.RemoteSurfaceView
import fr.webinfoconcept.secondscreen.session.ConnectionController
import fr.webinfoconcept.secondscreen.session.ConnectionFailure
import fr.webinfoconcept.secondscreen.session.ConnectionState
import fr.webinfoconcept.secondscreen.session.ReconnectStatus
import fr.webinfoconcept.secondscreen.settings.ConnectionSettings
import fr.webinfoconcept.secondscreen.settings.DisplaySettings
import fr.webinfoconcept.secondscreen.settings.InputSettings
import fr.webinfoconcept.secondscreen.session.SessionInfo
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
 * ## Pointeur (SS-045)
 * Le bouton Pointeur de la barre bascule entre le **mode direct** (le doigt désigne un point de l'écran distant) et le
 * **mode touchpad** (le doigt déplace le pointeur, avec une sensibilité réglable). Le choix et la sensibilité sont mémorisés
 * ([InputSettings]).
 *
 * ## Clavier (SS-046, SS-047)
 * Le bouton Clavier de la barre active le **mode clavier** : clavier virtuel Android ([KeyboardInputView]) et rangée de
 * touches spéciales (Échap, Tab, Ctrl, Alt, Maj, Suppr, Effacer, Entrée, flèches). Un clavier physique fonctionne sans ce
 * mode. Retour ferme d'abord le clavier virtuel, puis le mode clavier.
 *
 * ## Barre de commandes (SS-052)
 * Clavier (SS-046/047), Pointeur (direct / touchpad, SS-045), Diagnostic, Plein écran, Déconnexion. Elle s'affiche par la touche **Retour**
 * ou un **tap à trois doigts**. Retour quand elle est visible quitte l'écran : la sortie reste toujours à deux gestes.
 */
class RemoteActivity : Activity(), ConnectionController.Listener {

    private val controller get() = SessionManager.controller

    private lateinit var surface: RemoteSurfaceView
    private lateinit var actions: PointerActions
    private lateinit var touchpadActions: TouchpadActions
    private val pointerPosition = PointerPosition()
    private lateinit var inputSettings: InputSettings
    private lateinit var pointerButton: Button
    private lateinit var sensitivityRow: View
    private lateinit var sensitivitySeek: SeekBar
    private lateinit var sensitivityValue: TextView

    /** Sensibilité courante du touchpad, lue à chaque déplacement (pas de lecture du stockage sur le chemin tactile). */
    @Volatile private var touchpadSensitivity = TouchpadActions.DEFAULT_SENSITIVITY

    /** La session dont l'écran est affiché : un changement de session remet la position du pointeur à zéro. */
    private var attachedSession: SessionInfo? = null
    private lateinit var touchInput: TouchInput
    private lateinit var immersive: ImmersiveController
    private lateinit var bar: View
    private lateinit var overlay: View
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var reconnect: Button
    private lateinit var fullscreenButton: Button
    private lateinit var scaleButton: Button
    private lateinit var stopAuto: Button
    private lateinit var connectionSettings: ConnectionSettings
    private var fitToScreen = false
    private lateinit var keys: View
    private lateinit var keyboardView: KeyboardInputView
    private lateinit var keyboard: KeyboardInput
    private lateinit var forwarder: KeyForwarder
    private lateinit var ctrlButton: ToggleButton
    private lateinit var altButton: ToggleButton
    private lateinit var shiftButton: ToggleButton
    private lateinit var settings: DisplaySettings

    /** Vrai quand on quitte cet écran pour le diagnostic : la session doit alors survivre à `onStop`. */
    private var keepSessionOnStop = false

    // Le mode immersif n'est actif que fenêtre au premier plan, session affichée ET plein écran choisi (voir applyImmersive).
    private var windowFocused = false
    private var sessionShown = false
    private var fullscreen = false

    /** Mode clavier : la rangée de touches spéciales est affichée et le clavier virtuel est demandé. */
    private var keyboardMode = false

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

        inputSettings = InputSettings(PreferencesStore(this, InputSettings.FILE_NAME))
        touchpadSensitivity = inputSettings.touchpadSensitivity

        // Les mappeurs sont remplacés à l'établissement de chaque session, selon la taille de l'écran distant.
        val fallback = PointerMapper(FALLBACK_WIDTH, FALLBACK_HEIGHT) { surface.geometry }
        actions = PointerActions(fallback, controller.input, pointerPosition)
        touchpadActions = TouchpadActions(fallback, controller.input, pointerPosition) { touchpadSensitivity }
        touchInput = TouchInput(
            actions,
            touchpadActions,
            ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            surface,
            onToggleBar = { toggleBar() }
        )
        touchInput.setTouchpad(inputSettings.touchpad)
        surface.setOnTouchListener(touchInput)

        settings = DisplaySettings(PreferencesStore(this, DisplaySettings.FILE_NAME))
        fullscreen = settings.fullscreen
        fitToScreen = settings.fitToScreen
        surface.fitToScreen = fitToScreen
        scaleButton = findViewById(R.id.bar_scale)
        scaleButton.setOnClickListener { toggleScale() }
        updateScaleButton()
        fullscreenButton = findViewById(R.id.bar_fullscreen)
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        updateFullscreenButton()

        setUpPointerMode()
        setUpKeyboard()

        connectionSettings = ConnectionSettings(PreferencesStore(this, ConnectionSettings.FILE_NAME))
        stopAuto = findViewById(R.id.remote_stop_auto)
        stopAuto.setOnClickListener { controller.stopAutoReconnect() }
        reconnect.setOnClickListener {
            // Pendant l'attente d'une reconnexion automatique le bouton dit « Réessayer maintenant » ; sinon c'est la reconnexion manuelle.
            if (controller.reconnectStatus?.waiting == true) controller.retryNow() else askReconnect()
        }
        findViewById<Button>(R.id.remote_close).setOnClickListener { leave() }
        findViewById<Button>(R.id.bar_disconnect).setOnClickListener { leave() }
        findViewById<Button>(R.id.bar_diagnostic).setOnClickListener {
            keepSessionOnStop = true
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }

        immersive = ImmersiveController(ViewSystemUiHost(surface))
        surface.setOnSystemUiVisibilityChangeListener { immersive.onSystemUiVisibilityChange(it) }
    }

    /** Bouton Pointeur (mode direct / touchpad) et curseur de sensibilité (SS-045). */
    private fun setUpPointerMode() {
        pointerButton = findViewById(R.id.bar_pointer)
        sensitivityRow = findViewById(R.id.remote_sensitivity)
        sensitivitySeek = findViewById(R.id.sensitivity_seek)
        sensitivityValue = findViewById(R.id.sensitivity_value)

        sensitivitySeek.progress = progressOf(touchpadSensitivity)
        updateSensitivityValue()
        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                touchpadSensitivity = sensitivityOf(progress)
                updateSensitivityValue()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            // Mémorisé au relâchement : une seule écriture par réglage, pas une par pixel du curseur.
            override fun onStopTrackingTouch(bar: SeekBar) {
                inputSettings.touchpadSensitivity = touchpadSensitivity
            }
        })
        pointerButton.setOnClickListener { toggleTouchpad() }
        updatePointerButton()
    }

    private fun toggleTouchpad() {
        val enabled = !touchInput.isTouchpad
        touchInput.setTouchpad(enabled) // annule le geste en cours : aucun bouton ne reste enfoncé
        inputSettings.touchpad = enabled
        updatePointerButton()
        updateSensitivityRow()
        Toast.makeText(this, if (enabled) R.string.touchpad_hint else R.string.direct_hint, Toast.LENGTH_LONG).show()
    }

    private fun updatePointerButton() {
        pointerButton.setText(if (touchInput.isTouchpad) R.string.bar_pointer_touchpad else R.string.bar_pointer_direct)
    }

    /** Le curseur de sensibilité n'a de sens que pour le touchpad, et n'est montré qu'avec la barre de commandes. */
    private fun updateSensitivityRow() {
        sensitivityRow.visibility = if (bar.visibility == View.VISIBLE && touchInput.isTouchpad) View.VISIBLE else View.GONE
    }

    private fun updateSensitivityValue() {
        sensitivityValue.text = getString(R.string.sensitivity_value, String.format(java.util.Locale.getDefault(), "%.1f", touchpadSensitivity))
    }

    private fun progressOf(sensitivity: Float): Int {
        val range = TouchpadActions.MAX_SENSITIVITY - TouchpadActions.MIN_SENSITIVITY
        return Math.round((sensitivity - TouchpadActions.MIN_SENSITIVITY) / range * SEEK_MAX)
    }

    private fun sensitivityOf(progress: Int): Float {
        val range = TouchpadActions.MAX_SENSITIVITY - TouchpadActions.MIN_SENSITIVITY
        return TouchpadActions.MIN_SENSITIVITY + progress.coerceIn(0, SEEK_MAX) * range / SEEK_MAX
    }

    /** Clavier virtuel (SS-046), touches physiques et rangée de touches spéciales (SS-047). */
    private fun setUpKeyboard() {
        keys = findViewById(R.id.remote_keys)
        keyboardView = findViewById(R.id.remote_keyboard)
        keyboard = KeyboardInput(controller.input)
        forwarder = KeyForwarder(keyboard)
        keyboardView.keyboard = keyboard
        keyboardView.forwarder = forwarder

        ctrlButton = findViewById(R.id.key_ctrl)
        altButton = findViewById(R.id.key_alt)
        shiftButton = findViewById(R.id.key_shift)
        // Les boutons suivent l'état réel des modificateurs (un modificateur relâché après une frappe les décoche).
        keyboard.listener = object : KeyboardInput.Listener {
            override fun onModifiersChanged(ctrl: Boolean, alt: Boolean, shift: Boolean) {
                ctrlButton.isChecked = ctrl
                altButton.isChecked = alt
                shiftButton.isChecked = shift
            }
        }
        for ((button, modifier) in listOf(
            ctrlButton to KeyboardInput.Modifier.CTRL,
            altButton to KeyboardInput.Modifier.ALT,
            shiftButton to KeyboardInput.Modifier.SHIFT
        )) {
            // Un clic bascule le bouton lui-même ; l'état affiché est ensuite remis à celui du modificateur (si le message
            // n'est pas parti, le bouton ne reste pas coché à tort).
            button.setOnClickListener { button.isChecked = keyboard.toggleModifier(modifier) }
        }
        for ((id, keysym) in listOf(
            R.id.key_escape to Keysyms.ESCAPE,
            R.id.key_tab to Keysyms.TAB,
            R.id.key_delete to Keysyms.DELETE,
            R.id.key_backspace to Keysyms.BACKSPACE,
            R.id.key_enter to Keysyms.RETURN,
            R.id.key_left to Keysyms.LEFT,
            R.id.key_up to Keysyms.UP,
            R.id.key_down to Keysyms.DOWN,
            R.id.key_right to Keysyms.RIGHT
        )) {
            findViewById<Button>(id).setOnClickListener { keyboard.pressKey(keysym) }
        }
        findViewById<Button>(R.id.key_hide).setOnClickListener { exitKeyboardMode() }
        findViewById<Button>(R.id.bar_keyboard).setOnClickListener {
            if (keyboardMode) exitKeyboardMode() else enterKeyboardMode()
        }
    }

    private fun enterKeyboardMode() {
        keyboardMode = true
        showBar(false) // la rangée de touches prend la place en haut
        keys.visibility = View.VISIBLE
        applyImmersive()
        if (!keyboardView.showKeyboard()) Toast.makeText(this, R.string.keyboard_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun exitKeyboardMode() {
        if (!keyboardMode) return
        keyboardMode = false
        keys.visibility = View.GONE
        keyboard.releaseModifiers() // jamais de Ctrl ou Alt laissé enfoncé côté serveur
        keyboardView.hideKeyboard()
        applyImmersive()
    }

    /**
     * Clavier physique (USB, Bluetooth) : les touches vont au serveur tant que la session est établie. Les touches
     * système (Retour, Accueil, volume...) restent à Android.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controller.state == ConnectionState.CONNECTED && forwarder.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        keepSessionOnStop = false
        controller.addListener(this)
        controller.setRenderTarget(surface)
        render(controller.state, controller.failure)
    }

    override fun onStop() {
        overlay.removeCallbacks(countdown)
        touchInput.cancelGesture()
        controller.removeListener(this)
        controller.setRenderTarget(null)
        // Plus visible : la session est fermée (pas de service). Sauf départ vers le diagnostic.
        if (!keepSessionOnStop) controller.disconnect()
        super.onStop()
    }

    override fun onPause() {
        // Un glissement en cours et les modificateurs sont relâchés tant que l'envoyeur tourne encore.
        touchInput.cancelGesture()
        keyboard.releaseModifiers()
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
        val wanted = windowFocused && sessionShown && fullscreen && bar.visibility != View.VISIBLE && !keyboardMode
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
            showBar(false) // le plein écran ne s'applique que barre de commandes masquée
            Toast.makeText(this, R.string.fullscreen_hint, Toast.LENGTH_LONG).show()
        }
        applyImmersive()
    }

    /**
     * Bascule l'ajustement à l'écran (SS-033), mémorisé. Un écran distant qui n'est pas en 1280×800 est toujours ajusté :
     * le réglage ne change alors rien et le bouton l'indique.
     */
    private fun toggleScale() {
        val nominal = surface.geometry?.isNominalFrame ?: true
        if (!nominal) return
        fitToScreen = !fitToScreen
        settings.fitToScreen = fitToScreen
        surface.fitToScreen = fitToScreen
        updateScaleButton()
        Toast.makeText(this, if (fitToScreen) R.string.scale_hint_fit else R.string.scale_hint_native, Toast.LENGTH_LONG).show()
    }

    private fun updateScaleButton() {
        val nominal = surface.geometry?.isNominalFrame ?: true
        scaleButton.isEnabled = nominal
        scaleButton.setText(
            when {
                !nominal -> R.string.bar_scale_auto
                fitToScreen -> R.string.bar_scale_fit
                else -> R.string.bar_scale_native
            }
        )
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
        if (keyboardMode) {
            exitKeyboardMode() // le clavier virtuel, lui, a déjà pris le premier Retour pour se masquer
        } else if (controller.state == ConnectionState.CONNECTED && bar.visibility != View.VISIBLE) {
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
        if (!sessionShown) {
            // Session perdue : le serveur a déjà relâché ce qui l'était ; on oublie sans rien envoyer et on ferme le clavier.
            keyboard.reset()
            exitKeyboardMode()
        }
        applyImmersive()
        if (state == ConnectionState.CONNECTED) {
            attachSession()
            overlay.visibility = View.GONE
            return
        }
        val auto = if (state == ConnectionState.RECONNECTING) controller.reconnectStatus else null
        val busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING ||
            state == ConnectionState.NEGOTIATING
        overlay.visibility = View.VISIBLE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        // Reconnexion automatique : « Réessayer maintenant » et « Arrêter » ; sinon Reconnecter seulement hors connexion en cours.
        reconnect.visibility = if (busy && auto == null) View.GONE else View.VISIBLE
        reconnect.setText(if (auto != null) R.string.reconnect_now else R.string.remote_reconnect)
        reconnect.isEnabled = auto != null || controller.lastConnection != null
        stopAuto.visibility = if (auto != null) View.VISIBLE else View.GONE
        showBar(false)
        applyImmersive()

        status.text = statusText(state, failure, auto)
        overlay.removeCallbacks(countdown)
        if (auto?.waiting == true) overlay.postDelayed(countdown, COUNTDOWN_TICK_MS) // le compte à rebours se met à jour seul
    }

    /** Met à jour le compte à rebours de la reconnexion automatique, une fois par seconde. */
    private val countdown = object : Runnable {
        override fun run() {
            val auto = controller.reconnectStatus
            if (controller.state != ConnectionState.RECONNECTING || auto == null || !auto.waiting) return
            status.text = statusText(ConnectionState.RECONNECTING, controller.failure, auto)
            overlay.postDelayed(this, COUNTDOWN_TICK_MS)
        }
    }

    private fun statusText(state: ConnectionState, failure: ConnectionFailure?, auto: ReconnectStatus?): String {
        if (auto != null) {
            val cause = if (failure != null) FailureMessages.text(this, failure) + "\n" else ""
            return cause + if (auto.waiting) {
                val seconds = ((auto.remainingMs() + 999) / 1_000).toInt() // arrondi vers le haut : jamais « 0 s » avant d'essayer
                getString(R.string.reconnect_waiting, seconds, auto.attempt, auto.maxAttempts)
            } else {
                getString(R.string.reconnect_attempting, auto.attempt, auto.maxAttempts)
            }
        }
        return when (state) {
            ConnectionState.CONNECTING -> getString(R.string.state_connecting, controller.lastConnection.toString())
            ConnectionState.RECONNECTING -> getString(R.string.state_reconnecting, controller.lastConnection.toString())
            ConnectionState.NEGOTIATING -> getString(R.string.state_negotiating)
            ConnectionState.ERROR -> {
                val text = if (failure != null) FailureMessages.text(this, failure) else getString(R.string.state_disconnected)
                if (controller.gaveUpAfter > 0) text + "\n" + resources.getQuantityString(R.plurals.reconnect_gave_up, controller.gaveUpAfter, controller.gaveUpAfter) else text
            }
            else -> getString(if (controller.lastConnection != null) R.string.state_disconnected else R.string.state_no_connection)
        }
    }

    /** Affiche l'écran de la session établie et cale la conversion des touchers sur sa taille. */
    private fun attachSession() {
        val info = controller.session ?: return
        if (info !== attachedSession) {
            attachedSession = info
            pointerPosition.reset() // nouvelle session : le serveur ne dit pas où est son pointeur
        }
        // La géométrie est relue à chaque toucher : une nouvelle taille de surface ou un changement d'échelle suffisent.
        val mapper = PointerMapper(info.framebuffer.width, info.framebuffer.height) { surface.geometry }
        actions.mapper = mapper
        touchpadActions.mapper = mapper
        surface.setFramebuffer(info.framebuffer)
        updateScaleButton() // dépend de la taille de l'écran distant
    }

    // ------------------------------------------------------------------ actions

    private fun toggleBar() {
        if (controller.state != ConnectionState.CONNECTED) return
        showBar(bar.visibility != View.VISIBLE)
        applyImmersive()
    }

    private fun showBar(visible: Boolean) {
        bar.visibility = if (visible) View.VISIBLE else View.GONE
        updateSensitivityRow()
    }

    /** Ferme la session et revient à la liste des connexions. */
    private fun leave() {
        controller.disconnect()
        finish()
    }

    /** Relance la connexion ; demande d'abord le mot de passe si le serveur en exigeait un (il n'est pas conservé). */
    private fun askReconnect() {
        if (!controller.reconnectNeedsPassword) {
            controller.reconnect(null, connectionSettings.autoReconnect)
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
                controller.reconnect(if (chars.isEmpty()) null else chars, connectionSettings.autoReconnect) // efface `chars`
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val SEEK_MAX = 100
        const val COUNTDOWN_TICK_MS = 1_000L

        // Taille avant la première session : celle de la tablette cible (nominale, AGENTS.md).
        const val FALLBACK_WIDTH = 1280
        const val FALLBACK_HEIGHT = 800
    }
}
