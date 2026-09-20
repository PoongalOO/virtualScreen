package fr.webinfoconcept.secondscreen.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * [KeyValueStore] sur `SharedPreferences` (SS-051). Toutes les valeurs sont des chaînes. Le fichier est privé à
 * l'application ; `android:allowBackup="false"` (manifeste) l'exclut des sauvegardes. Il ne contient ni mot de passe ni
 * aucune donnée reçue du serveur.
 */
class PreferencesStore(context: Context, name: String = FILE_NAME) : KeyValueStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()

    override fun apply(changes: Map<String, String?>) {
        val editor = prefs.edit()
        for ((key, value) in changes) if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    companion object {
        const val FILE_NAME = "connection_profiles"
    }
}
