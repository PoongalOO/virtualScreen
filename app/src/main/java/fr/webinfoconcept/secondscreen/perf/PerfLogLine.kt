package fr.webinfoconcept.secondscreen.perf

import fr.webinfoconcept.secondscreen.rfb.protocol.EncodingMode
import java.util.Locale

/**
 * L'unique ligne de journal de l'application (SS-071) : **des nombres et la clé d'un encodage, rien d'autre**. La ligne est
 * fabriquée ici, par une fonction pure, pour qu'un test puisse **prouver** qu'elle ne peut pas contenir de texte libre :
 * ses seuls arguments sont un [PerfSnapshot] (des nombres) et un [EncodingMode] (une énumération, donc quatre mots au plus).
 * Aucun mot de passe, aucun contenu d'écran, aucune saisie, aucune adresse, aucun nom.
 */
object PerfLogLine {
    /** Étiquette du journal (logcat). Lue par `scripts/analyze_session.py` et `analyze_benchmark.py`. */
    const val TAG = "SecondScreenPerf"

    fun format(p: PerfSnapshot, encoding: EncodingMode): String =
        ("maj/s=%.1f rendus/s=%.1f mpx/s=%.2f rx_ko/s=%d tx_ko/s=%d decod_ms=%.1f(max %.1f) rendu_ms=%.1f copie_ms=%.1f " +
            "dessin_ms=%.1f(max %.1f) kpx_copies=%d plein_ecran=%d rendus_complets=%d cpu=%d tas_ko=%d natif_ko=%d alloc/s=%d " +
            "alloc_ko/s=%d sess_alloc/s=%d sess_alloc_o/s=%d sess_alloc/maj=%.1f ui_alloc/s=%d enc=%s").format(
            Locale.US, p.updatesPerSecond, p.rendersPerSecond, p.megapixelsPerSecond, p.bytesReceivedPerSecond / 1024,
            p.bytesSentPerSecond / 1024, p.decodeAvgMs, p.decodeMaxMs, p.renderAvgMs, p.copyAvgMs, p.drawAvgMs, p.renderMaxMs,
            p.copiedPixelsPerRender / 1000, p.fullScreenCopies, p.fullRedraws, p.cpuPercent, p.heapUsedKb, p.nativeHeapKb,
            p.allocsPerSecond, p.allocBytesPerSecond / 1024, p.sessionAllocsPerSecond, p.sessionAllocBytesPerSecond,
            p.sessionAllocsPerUpdate, p.uiAllocsPerSecond, encoding.key
        )
}
