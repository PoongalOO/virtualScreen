package fr.webinfoconcept.secondscreen.diagnostic

import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale

/**
 * Informations de diagnostic matériel (SS-003).
 *
 * Volontairement limité à des données non sensibles : version Android, API,
 * résolution d'écran, mémoire applicative/système, ABI CPU. Aucune donnée
 * d'identification de l'appareil (IMEI, numéro de série, adresse MAC/IP...)
 * n'est collectée ni affichée (SECURITY.md).
 */
data class DiagnosticInfo(
    val androidRelease: String,
    val apiLevel: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val appMaxHeapBytes: Long,
    val abis: List<String>
) {
    companion object {
        fun collect(context: Context): DiagnosticInfo {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // getRealMetrics() est disponible depuis API 17 (JELLY_BEAN_MR1) et
            // renvoie la résolution physique de l'écran, contrairement à
            // getMetrics() qui exclut la barre système.
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            return DiagnosticInfo(
                androidRelease = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                screenWidthPx = metrics.widthPixels,
                screenHeightPx = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                totalMemoryBytes = memoryInfo.totalMem,
                availableMemoryBytes = memoryInfo.availMem,
                appMaxHeapBytes = Runtime.getRuntime().maxMemory(),
                abis = chooseAbis(
                    sdkInt = Build.VERSION.SDK_INT,
                    modernAbis = ::supportedAbisApi21,
                    legacyAbis = {
                        @Suppress("DEPRECATION")
                        listOfNotNull(
                            Build.CPU_ABI,
                            Build.CPU_ABI2.takeIf { it.isNotBlank() && it != "unknown" }
                        )
                    }
                )
            )
        }
    }
}

/** Uniquement appelée par [chooseAbis] quand `sdkInt >= 21` (garde de compatibilité). */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
private fun supportedAbisApi21(): List<String> = Build.SUPPORTED_ABIS.toList()

/**
 * Choisit la source d'ABI selon l'API disponible : `Build.SUPPORTED_ABIS`
 * n'existe qu'à partir d'API 21, en dessous seuls `CPU_ABI`/`CPU_ABI2`
 * existent. Isolée en fonction pure (branchement injecté) pour rester
 * testable en JVM sans device — voir DiagnosticInfoTest.
 */
internal fun chooseAbis(
    sdkInt: Int,
    modernAbis: () -> List<String>,
    legacyAbis: () -> List<String>
): List<String> = if (sdkInt >= Build.VERSION_CODES.LOLLIPOP) modernAbis() else legacyAbis()

/** Formate une taille en mébioctets avec une décimale, ex. "76.3 MiB". */
internal fun formatMebibytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
