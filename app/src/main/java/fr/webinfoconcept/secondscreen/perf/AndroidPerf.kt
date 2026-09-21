package fr.webinfoconcept.secondscreen.perf

import android.os.Debug

/**
 * Mesures Android (SS-060, SS-061) : uniquement des API présentes dès l'API 1 (`android.os.Debug`, comptage d'allocations de
 * Dalvik). Pas de compteur de ramasse-miettes : `getGlobalGcInvocationCount()` rend toujours 0 sur la GT-P5110 (mesuré : 0 sur 1 774
 * secondes où le journal Dalvik montre 22 ramasse-miettes) ; ils se lisent dans le journal (`dalvikvm`), voir scripts/analyze_session.py. Sur un système plus récent où elles seraient sans effet, les lectures rendent 0 plutôt que de planter.
 *
 * Les compteurs `getThreadAlloc*` et `getGlobalAlloc*` sont des `int` qui repartent de 0 après ~2,1 milliards : on les lit
 * comme non signés (0 à 4,29 milliards), largement de quoi tenir une session de 2 h.
 */
object AndroidThreadMeter : ThreadMeter {
    override fun cpuNanos(): Long = Debug.threadCpuTimeNanos()
    override fun allocatedObjects(): Long = Debug.getThreadAllocCount().toLong() and UNSIGNED_INT
    override fun allocatedBytes(): Long = Debug.getThreadAllocSize().toLong() and UNSIGNED_INT
}

object AndroidMemoryProbe {

    /**
     * Démarre le comptage d'allocations de la VM. **À n'appeler que quand les mesures sont actives** : Dalvik compte alors chaque
     * allocation, ce qui a un léger coût.
     */
    fun startCounting() {
        try {
            @Suppress("DEPRECATION")
            Debug.startAllocCounting()
        } catch (e: RuntimeException) {
            // VM sans comptage : les lectures rendront 0.
        } catch (e: LinkageError) {
        }
    }

    fun stopCounting() {
        try {
            @Suppress("DEPRECATION")
            Debug.stopAllocCounting()
        } catch (e: RuntimeException) {
        } catch (e: LinkageError) {
        }
    }

    /** À appeler depuis le thread UI (pour [MemoryReading.uiAllocObjects]). */
    fun read(): MemoryReading {
        val runtime = Runtime.getRuntime()
        return MemoryReading(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            allocObjects = Debug.getGlobalAllocCount().toLong() and UNSIGNED_INT,
            allocBytes = Debug.getGlobalAllocSize().toLong() and UNSIGNED_INT,
            uiAllocObjects = Debug.getThreadAllocCount().toLong() and UNSIGNED_INT
        )
    }
}

private const val UNSIGNED_INT = 0xFFFFFFFFL
