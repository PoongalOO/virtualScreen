package fr.webinfoconcept.secondscreen.session

/**
 * Où en est la reconnexion **automatique** (SS-055), pour l'écran : « nouvelle tentative dans 4 s (3/8) ».
 *
 * @property attempt numéro (à partir de 1) de la tentative à venir ou en cours.
 * @property maxAttempts nombre maximal de tentatives avant d'abandonner : la reconnexion est **bornée**.
 * @property delayMs attente prévue avant cette tentative.
 * @property startedAtNs instant (`System.nanoTime()`) où l'attente a commencé.
 * @property waiting `true` pendant l'attente, `false` pendant la tentative elle-même.
 */
class ReconnectStatus(
    val attempt: Int,
    val maxAttempts: Int,
    val delayMs: Long,
    val startedAtNs: Long,
    val waiting: Boolean
) {
    /** Attente restante en ms (0 pendant une tentative). */
    fun remainingMs(nowNs: Long = System.nanoTime()): Long =
        if (!waiting) 0L else maxOf(0L, delayMs - (nowNs - startedAtNs) / 1_000_000L)

    override fun toString(): String = "ReconnectStatus($attempt/$maxAttempts, waiting=$waiting)"
}
