package fr.webinfoconcept.secondscreen.rfb.testutil

/**
 * Octets alloués jusqu'ici par le thread d'identifiant [threadId], ou `null` si la JVM ne sait pas les mesurer.
 * Par réflexion : les tests unitaires AGP compilent contre android.jar, qui ne contient pas
 * `java.lang.management`, alors que la JVM qui les exécute l'a.
 */
fun allocatedBytesOfThread(threadId: Long): Long? = try {
    val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    val sunBean = Class.forName("com.sun.management.ThreadMXBean")
    if (!sunBean.isInstance(bean) || !(sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)) {
        null
    } else {
        sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType).invoke(bean, threadId) as Long
    }
} catch (e: ReflectiveOperationException) {
    null
}

/** Octets alloués jusqu'ici par le thread courant, ou `null` si la mesure est indisponible. */
fun allocatedBytesOfCurrentThread(): Long? = allocatedBytesOfThread(Thread.currentThread().id)
