package fr.webinfoconcept.secondscreen

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import fr.webinfoconcept.secondscreen.diagnostic.DiagnosticInfo
import fr.webinfoconcept.secondscreen.diagnostic.formatMebibytes

/**
 * Écran de diagnostic matériel (SS-003).
 *
 * N'affiche que des informations non sensibles : version Android, API,
 * résolution, mémoire, ABI. Aucun identifiant d'appareil (IMEI, numéro de
 * série, adresse MAC/IP...) n'est lu ni affiché (SECURITY.md).
 */
class DiagnosticActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        val info = DiagnosticInfo.collect(this)

        findViewById<TextView>(R.id.value_android_version).text = getString(
            R.string.diagnostic_android_version_value,
            info.androidRelease,
            info.apiLevel
        )

        findViewById<TextView>(R.id.value_resolution).text = getString(
            R.string.diagnostic_resolution_value,
            info.screenWidthPx,
            info.screenHeightPx,
            info.densityDpi
        )

        findViewById<TextView>(R.id.value_memory).text = getString(
            R.string.diagnostic_memory_value,
            formatMebibytes(info.availableMemoryBytes),
            formatMebibytes(info.totalMemoryBytes),
            formatMebibytes(info.appMaxHeapBytes)
        )

        findViewById<TextView>(R.id.value_abi).text = info.abis.joinToString(", ")
    }
}
