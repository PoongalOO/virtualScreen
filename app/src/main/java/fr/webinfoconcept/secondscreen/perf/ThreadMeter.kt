package fr.webinfoconcept.secondscreen.perf

/**
 * Ce que l'on sait mesurer **du thread courant** : temps processeur et allocations (SS-060, SS-061). Interface plutôt que
 * lambdas : une lambda `() -> Long` **boxe** son résultat (`java.lang.Long`), donc *allouerait* à chaque lecture, ce qui fausserait
 * précisément la mesure des allocations du thread de session.
 */
interface ThreadMeter {
    /** Temps processeur consommé par le thread courant, en ns. */
    fun cpuNanos(): Long

    /** Objets alloués par le thread courant depuis le début du comptage. */
    fun allocatedObjects(): Long

    /** Octets alloués par le thread courant depuis le début du comptage. */
    fun allocatedBytes(): Long

    /** Meter qui ne mesure rien (tests, JVM). */
    object None : ThreadMeter {
        override fun cpuNanos() = 0L
        override fun allocatedObjects() = 0L
        override fun allocatedBytes() = 0L
    }
}

/**
 * Lecture, à un instant, de la mémoire de **tout le processus** (cumuls depuis le début du comptage). Faite par le thread UI
 * environ une fois par seconde ; l'échantillonneur en calcule des débits par différence.
 */
data class MemoryReading(
    /** Tas Java utilisé, en octets. */
    val heapUsedBytes: Long = 0,
    /** Tas natif alloué (JNI, tampons), en octets. */
    val nativeHeapBytes: Long = 0,
    /** Objets alloués par tous les threads du processus (cumul). */
    val allocObjects: Long = 0,
    /** Octets alloués par tous les threads du processus (cumul). */
    val allocBytes: Long = 0,
    /** Objets alloués par le seul thread UI (cumul) : ce que coûte l'affichage des mesures elles-mêmes. */
    val uiAllocObjects: Long = 0
) {
    companion object {
        val NONE = MemoryReading()
    }
}
