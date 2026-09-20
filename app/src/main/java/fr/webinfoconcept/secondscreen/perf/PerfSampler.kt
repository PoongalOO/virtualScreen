package fr.webinfoconcept.secondscreen.perf

/**
 * Mesures d'un intervalle (typiquement une seconde) : ce que l'écran affiche. Toutes les moyennes valent 0 quand il n'y a
 * eu aucun événement de leur sorte (pas de division par zéro).
 */
data class PerfSnapshot(
    /** `FramebufferUpdate` reçus par seconde. */
    val updatesPerSecond: Float,
    /** Rendus (images dessinées) par seconde : les images par seconde **effectivement affichées**. */
    val rendersPerSecond: Float,
    /** Pixels décodés par seconde, en millions. */
    val megapixelsPerSecond: Float,
    /** Octets reçus par seconde. */
    val bytesReceivedPerSecond: Long,
    /** Octets envoyés par seconde. */
    val bytesSentPerSecond: Long,
    /** Durée moyenne d'un message de mise à jour (réception + décodage), en ms. */
    val decodeAvgMs: Float,
    val decodeMaxMs: Float,
    /** Durée moyenne d'un rendu (copie + dessin), en ms. */
    val renderAvgMs: Float,
    /** Dont la copie framebuffer -> Bitmap, en moyenne par rendu. */
    val copyAvgMs: Float,
    /** Dont le dessin et la publication sur la surface, en moyenne par rendu. */
    val drawAvgMs: Float,
    val renderMaxMs: Float,
    /** Pixels copiés en moyenne par rendu. */
    val copiedPixelsPerRender: Long,
    /** Rendus qui ont copié (presque) tout le framebuffer sans y être obligés, sur l'intervalle. */
    val fullScreenCopies: Long,
    /** Rendus complets (surface créée ou redimensionnée), sur l'intervalle. */
    val fullRedraws: Long,
    /** Part de processeur du thread de session (décodage + rendu), en pourcentage d'un cœur. */
    val cpuPercent: Int,
    /** Tas Java utilisé, en Kio. */
    val heapUsedKb: Long
) {
    companion object {
        val EMPTY = PerfSnapshot(0f, 0f, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0, 0, 0, 0)

        /**
         * Calcule les mesures de l'intervalle entre [before] et [after], durée [elapsedNs].
         * Un intervalle nul ou négatif donne [EMPTY] ; un compteur qui a reculé (remise à zéro entre-temps) est traité comme 0.
         */
        fun between(
            before: PerfCounters,
            after: PerfCounters,
            elapsedNs: Long,
            decodeMaxNs: Long,
            renderMaxNs: Long,
            heapUsedBytes: Long
        ): PerfSnapshot {
            if (elapsedNs <= 0) return EMPTY
            val seconds = elapsedNs / 1_000_000_000.0
            fun delta(a: Long, b: Long) = maxOf(0L, b - a)
            fun perSecond(n: Long) = (n / seconds).toFloat()
            fun ms(ns: Long) = (ns / 1_000_000.0).toFloat()

            val updates = delta(before.updates, after.updates)
            val renders = delta(before.renders, after.renders)
            val decodeNs = delta(before.decodeNs, after.decodeNs)
            val copyNs = delta(before.copyNs, after.copyNs)
            val drawNs = delta(before.drawNs, after.drawNs)
            val copied = delta(before.copiedPixels, after.copiedPixels)
            val cpuNs = delta(before.sessionCpuNs, after.sessionCpuNs)
            return PerfSnapshot(
                updatesPerSecond = perSecond(updates),
                rendersPerSecond = perSecond(renders),
                megapixelsPerSecond = (delta(before.decodedPixels, after.decodedPixels) / seconds / 1_000_000.0).toFloat(),
                bytesReceivedPerSecond = (delta(before.bytesReceived, after.bytesReceived) / seconds).toLong(),
                bytesSentPerSecond = (delta(before.bytesSent, after.bytesSent) / seconds).toLong(),
                decodeAvgMs = if (updates > 0) ms(decodeNs) / updates else 0f,
                decodeMaxMs = ms(decodeMaxNs),
                renderAvgMs = if (renders > 0) ms(copyNs + drawNs) / renders else 0f,
                copyAvgMs = if (renders > 0) ms(copyNs) / renders else 0f,
                drawAvgMs = if (renders > 0) ms(drawNs) / renders else 0f,
                renderMaxMs = ms(renderMaxNs),
                copiedPixelsPerRender = if (renders > 0) copied / renders else 0,
                fullScreenCopies = delta(before.fullScreenCopies, after.fullScreenCopies),
                fullRedraws = delta(before.fullRedraws, after.fullRedraws),
                cpuPercent = Math.round(100.0 * cpuNs / elapsedNs).toInt().coerceIn(0, 100),
                heapUsedKb = heapUsedBytes / 1024
            )
        }
    }
}

/**
 * Calcule un [PerfSnapshot] à chaque appel de [sample], à partir de ce qui s'est passé depuis l'appel précédent. Appelé par le
 * thread UI environ une fois par seconde. Le premier appel après [PerfStats.enabled] ou [reset] pose la référence et rend
 * [PerfSnapshot.EMPTY].
 */
class PerfSampler(private val stats: PerfStats) {
    private var previous: PerfCounters? = null
    private var previousNs = 0L

    /** @param nowNs horloge monotone en ns ; @param heapUsedBytes tas Java utilisé. */
    fun sample(nowNs: Long, heapUsedBytes: Long): PerfSnapshot {
        val current = stats.counters()
        val before = previous
        val elapsed = nowNs - previousNs
        previous = current
        previousNs = nowNs
        if (before == null) {
            stats.takeDecodeMaxNs()
            stats.takeRenderMaxNs()
            return PerfSnapshot.EMPTY
        }
        return PerfSnapshot.between(before, current, elapsed, stats.takeDecodeMaxNs(), stats.takeRenderMaxNs(), heapUsedBytes)
    }

    /** Oublie la référence : le prochain [sample] repart de zéro (mesures réactivées, nouvelle session). */
    fun reset() {
        previous = null
    }
}
