package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Écran d'accueil minimal (SS-001).
 *
 * Ce point d'entrée sert uniquement à valider que le projet Kotlin
 * `minSdk=17` compile, s'installe et se lance sur la GT-P5110 (smoke test
 * obligatoire décrit dans DEVELOPMENT.md). Les écrans fonctionnels (liste
 * des connexions, écran distant, diagnostic matériel) sont traités par les
 * issues suivantes : SS-050, SS-030+, SS-003.
 *
 * Hérite de [Activity] (framework, pas AppCompat) : aucune dépendance
 * AndroidX n'est nécessaire pour ce squelette.
 */
class MainActivity : Activity() {

    private companion object {
        /** Images de l'animation de test : assez pour mesurer la cadence, et une position finale déterministe. */
        const val ANIMATION_FRAMES = 600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.text_detail).text = getString(
            R.string.main_status_detail,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        )

        findViewById<Button>(R.id.btn_diagnostic).setOnClickListener {
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }

        findViewById<Button>(R.id.btn_render_test).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }

        findViewById<Button>(R.id.btn_render_animated).setOnClickListener {
            startActivity(
                Intent(this, RemoteActivity::class.java)
                    .putExtra(RemoteActivity.EXTRA_ANIMATE, true)
                    .putExtra(RemoteActivity.EXTRA_FRAMES, ANIMATION_FRAMES)
            )
        }
    }
}
