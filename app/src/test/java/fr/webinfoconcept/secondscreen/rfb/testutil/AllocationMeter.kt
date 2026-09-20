package fr.webinfoconcept.secondscreen.rfb.testutil

/**
 * Octets alloués jusqu'ici par le thread courant, ou `null` si la JVM ne sait pas les mesurer.
 * Par réflexion : les tests unitaires AGP compilent contre android.jar, qui ne contient pas
 * `java.lang.management`, alors que la JVM qui les exécute l'a.
 */
fun allocatedBytesOfCurrentThread(): Long? = try {
    val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    val sunBean = Class.forName("com.sun.management.ThreadMXBean")
    if (!sunBean.isInstance(bean) || !(sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)) {
        null
    } else {
        sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            .invoke(bean, Thread.currentThread().id) as Long
    }
} catch (e: ReflectiveOperationException) {
    null
}
