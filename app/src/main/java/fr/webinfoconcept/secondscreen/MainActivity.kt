package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import fr.webinfoconcept.secondscreen.profile.ConnectionProfile
import fr.webinfoconcept.secondscreen.profile.PreferencesStore
import fr.webinfoconcept.secondscreen.profile.ProfileStore

/**
 * Écran d'accueil (SS-050, SS-051) : la liste des connexions enregistrées, un bouton Ajouter et l'accès au diagnostic.
 *
 * - toucher une connexion ouvre l'écran de connexion **prérempli** (le mot de passe n'est jamais enregistré : il se
 *   saisit à chaque fois) ;
 * - le dernier profil utilisé est proposé en tête (« Reconnecter : ... », F08) ;
 * - un appui long propose de supprimer une connexion.
 *
 * Hérite de [Activity] (framework, pas AppCompat) : aucune dépendance AndroidX.
 */
class MainActivity : Activity() {

    private lateinit var profiles: ProfileStore
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var last: Button
    private var shown: List<ConnectionProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        profiles = ProfileStore(PreferencesStore(this))

        findViewById<TextView>(R.id.text_detail).text =
            getString(R.string.main_status_detail, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        list = findViewById(R.id.list_profiles)
        empty = findViewById(R.id.text_empty)
        last = findViewById(R.id.btn_last)

        findViewById<Button>(R.id.btn_add).setOnClickListener { openConnect(null) }
        findViewById<Button>(R.id.btn_diagnostic).setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
        list.setOnItemClickListener { _, _, position, _ -> shown.getOrNull(position)?.let { openConnect(it.id) } }
        list.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { confirmDelete(it) }
            true
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        shown = profiles.list()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.label() })
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE

        val lastUsed = profiles.lastUsed()
        if (lastUsed == null) {
            last.visibility = View.GONE
        } else {
            last.visibility = View.VISIBLE
            last.text = getString(R.string.main_last_connection, lastUsed.name)
            last.setOnClickListener { openConnect(lastUsed.id) }
        }
    }

    private fun openConnect(profileId: Long?) {
        val intent = Intent(this, ConnectActivity::class.java)
        if (profileId != null) intent.putExtra(ConnectActivity.EXTRA_PROFILE_ID, profileId)
        startActivity(intent)
    }

    private fun confirmDelete(profile: ConnectionProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_delete_title)
            .setMessage(getString(R.string.main_delete_message, profile.name))
            .setPositiveButton(R.string.main_delete_confirm) { _, _ ->
                profiles.delete(profile.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
