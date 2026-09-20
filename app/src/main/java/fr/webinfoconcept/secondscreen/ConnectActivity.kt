package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import fr.webinfoconcept.secondscreen.profile.PreferencesStore
import fr.webinfoconcept.secondscreen.profile.ProfileForm
import fr.webinfoconcept.secondscreen.profile.ProfileStore
import fr.webinfoconcept.secondscreen.profile.TooManyProfilesException
import fr.webinfoconcept.secondscreen.session.ConnectionController
import fr.webinfoconcept.secondscreen.session.ConnectionFailure
import fr.webinfoconcept.secondscreen.session.ConnectionParams
import fr.webinfoconcept.secondscreen.session.ConnectionState
import fr.webinfoconcept.secondscreen.ui.FailureMessages

/**
 * Écran de connexion (SS-050) : nom, adresse, port, mot de passe, et bouton Connexion. Affiche la progression et les
 * erreurs compréhensibles (SS-053) ; en cas d'échec le formulaire reste rempli pour corriger et réessayer (SS-054).
 *
 * **Le mot de passe** : lu dans un `CharArray`, le champ est vidé aussitôt, le tableau est confié au
 * [ConnectionController] qui l'efface. Il n'est ni enregistré dans le profil, ni conservé dans l'état d'instance
 * (`saveEnabled=false`), ni mis dans un `Intent`, ni journalisé.
 *
 * La session est portée par [SessionManager], pas par cette activité : dès qu'elle est établie, l'écran distant s'ouvre.
 */
class ConnectActivity : Activity(), ConnectionController.Listener {

    private lateinit var profiles: ProfileStore
    private val controller get() = SessionManager.controller

    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var password: EditText
    private lateinit var save: CheckBox
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var connect: Button
    private lateinit var cancel: Button

    /** Profil en cours de modification ; `null` pour une nouvelle connexion. */
    private var profileId: Long? = null
    private var launchedRemote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        profiles = ProfileStore(PreferencesStore(this))

        name = findViewById(R.id.connect_name)
        host = findViewById(R.id.connect_host)
        port = findViewById(R.id.connect_port)
        password = findViewById(R.id.connect_password)
        save = findViewById(R.id.connect_save)
        status = findViewById(R.id.connect_status)
        progress = findViewById(R.id.connect_progress)
        connect = findViewById(R.id.connect_button)
        cancel = findViewById(R.id.connect_cancel)

        val id = intent.getLongExtra(EXTRA_PROFILE_ID, NO_PROFILE)
        val existing = if (id != NO_PROFILE) profiles.get(id) else null
        profileId = existing?.id
        val title = findViewById<TextView>(R.id.connect_title)
        if (existing != null) {
            title.text = getString(R.string.connect_title_edit, existing.name)
            if (savedInstanceState == null) {
                name.setText(existing.name)
                host.setText(existing.host)
                port.setText(existing.port.toString())
            }
            password.requestFocus() // l'essentiel d'une reconnexion : saisir le mot de passe
        } else {
            title.setText(R.string.connect_title_new)
        }

        // Une erreur d'une tentative précédente ne doit pas s'afficher sur un formulaire neuf (pas après une rotation).
        if (savedInstanceState == null && controller.state == ConnectionState.ERROR) controller.disconnect()

        connect.setOnClickListener { startConnection() }
        cancel.setOnClickListener { controller.disconnect() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && connect.isEnabled) {
                startConnection()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(this)
        render(controller.state, controller.failure)
    }

    override fun onStop() {
        controller.removeListener(this)
        super.onStop()
    }

    override fun onBackPressed() {
        // Abandonner une tentative en cours, puis quitter l'écran.
        if (controller.state.isActive && !launchedRemote && controller.state != ConnectionState.CONNECTED) controller.disconnect()
        super.onBackPressed()
    }

    private fun startConnection() {
        clearFieldErrors()
        val form = ProfileForm.validate(name.text.toString(), host.text.toString(), port.text.toString())
        if (!form.isValid) {
            form.nameError?.let { name.error = getString(FailureMessages.fieldErrorRes(it)) }
            form.hostError?.let { host.error = getString(FailureMessages.fieldErrorRes(it)) }
            form.portError?.let { port.error = getString(FailureMessages.fieldErrorRes(it)) }
            (if (form.hostError != null) host else if (form.portError != null) port else name).requestFocus()
            return
        }

        // Le profil n'est enregistré qu'une fois la connexion établie (voir openRemote) : une faute de frappe qui échoue
        // ne doit pas écraser un profil qui marchait.
        if (save.isChecked && profileId == null && profiles.list().size >= ProfileStore.MAX_PROFILES &&
            profiles.list().none { it.name.equals(form.name, ignoreCase = true) }
        ) {
            showStatus(getString(R.string.connect_too_many), error = true)
            return
        }

        // Le mot de passe quitte le champ avant tout le reste : copié, puis le champ est vidé.
        val secret = takePassword()
        val params = ConnectionParams(form.host!!, form.port!!)
        if (!controller.connect(params, secret)) { // a effacé `secret`
            showStatus(getString(R.string.connect_busy), error = true)
        }
    }

    /** Enregistre la connexion qui vient de réussir (si demandé) et en fait le dernier profil utilisé. */
    private fun rememberSuccessfulConnection() {
        if (!save.isChecked) return
        val form = ProfileForm.validate(name.text.toString(), host.text.toString(), port.text.toString())
        if (!form.isValid) return
        try {
            val saved = profiles.save(form, profileId)
            profileId = saved.id
            profiles.markUsed(saved.id)
        } catch (e: TooManyProfilesException) {
            // La connexion est établie : on ne la gâche pas pour une liste pleine.
        }
    }

    /** Copie le mot de passe saisi dans un tableau et vide le champ ; `null` s'il est vide. */
    private fun takePassword(): CharArray? {
        val editable = password.text
        val chars = CharArray(editable.length)
        editable.getChars(0, chars.size, chars, 0)
        editable.clear()
        return if (chars.isEmpty()) null else chars
    }

    override fun onStateChanged(state: ConnectionState, failure: ConnectionFailure?) {
        runOnUiThread { if (!isFinishing) render(state, failure) }
    }

    private fun render(state: ConnectionState, failure: ConnectionFailure?) {
        val busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING ||
            state == ConnectionState.NEGOTIATING
        setFormEnabled(!busy)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        connect.visibility = if (busy) View.GONE else View.VISIBLE

        when (state) {
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING ->
                showStatus(getString(R.string.state_connecting, controller.lastConnection.toString()), error = false)
            ConnectionState.NEGOTIATING -> showStatus(getString(R.string.state_negotiating), error = false)
            ConnectionState.ERROR -> if (failure != null) {
                showStatus(FailureMessages.text(this, failure), error = true)
                if (failure.isPasswordProblem) password.requestFocus()
            }
            ConnectionState.CONNECTED -> openRemote()
            ConnectionState.DISCONNECTED -> status.visibility = View.GONE
        }
    }

    private fun openRemote() {
        if (launchedRemote) return
        launchedRemote = true
        rememberSuccessfulConnection()
        startActivity(Intent(this, RemoteActivity::class.java))
        finish() // Retour depuis l'écran distant ramène à la liste, pas à ce formulaire
    }

    private fun setFormEnabled(enabled: Boolean) {
        for (view in listOf<View>(name, host, port, password, save)) view.isEnabled = enabled
    }

    private fun showStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(resources.getColor(if (error) R.color.error else R.color.text_primary))
        status.visibility = View.VISIBLE
    }

    private fun clearFieldErrors() {
        name.error = null
        host.error = null
        port.error = null
    }

    companion object {
        /** Identifiant du profil à préremplir (absent : nouvelle connexion). */
        const val EXTRA_PROFILE_ID = "fr.webinfoconcept.secondscreen.PROFILE_ID"
        private const val NO_PROFILE = -1L
    }
}
