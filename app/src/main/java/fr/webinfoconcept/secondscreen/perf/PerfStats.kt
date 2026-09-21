package fr.webinfoconcept.secondscreen.perf

import java.util.concurrent.atomic.AtomicLong

/**
 * Compteurs de performance de la session (SS-060) : images par seconde, débit réseau, temps de décodage et de rendu, surface
 * copiée, temps processeur. **Désactivé par défaut** ([enabled] faux) et **peu coûteux** :
 *
 * - désactivé, chaque point de mesure ne fait qu'**une lecture d'un booléen** : aucun `System.nanoTime()`, aucune
 *   addition, aucune allocation ;
 * - activé, un point de mesure fait deux lectures de l'horloge et quelques additions atomiques **sans allocation**
 *   (`AtomicLong` préexistants) ; rien n'est journalisé ni dessiné ici ;
 * - les débits et moyennes ne sont calculés que par [PerfSampler], une fois par seconde, par le thread qui affiche.
 *
 * **Threads** : les compteurs sont des `AtomicLong` : les points de mesure sont appelés par le thread de session (décodage et
 * rendu) et parfois par le thread UI (rendu complet), l'échantillonneur par le thread UI.
 *
 * **Aucune donnée sensible** : uniquement des nombres (comptes, durées, octets), jamais de contenu de l'écran ni de saisie.
 *
 * @param threadMeter temps processeur et allocations du thread **courant** ([AndroidThreadMeter] sur Android). Injecté pour que
 *   la classe reste testable sans Android ; une interface et non des lambdas, qui boxeraient chaque lecture (voir [ThreadMeter]).
 */
class PerfStats(private val threadMeter: ThreadMeter = ThreadMeter.None) {

    /** Pour les tests : seulement une horloge processeur. Boxe à chaque lecture : ne pas utiliser en production. */
    constructor(threadCpuClock: () -> Long) : this(object : ThreadMeter {
        override fun cpuNanos() = threadCpuClock()
        override fun allocatedObjects() = 0L
        override fun allocatedBytes() = 0L
    })

    /** Mesures actives. Faux par défaut ; les points de mesure vérifient ce drapeau avant tout calcul. */
    @Volatile
    var enabled: Boolean = false

    /** Octets échangés avec le serveur, alimentés par la socket. */
    val traffic = TrafficCounter(this)

    private val updates = AtomicLong()
    private val rects = AtomicLong()
    private val decodedPixels = AtomicLong()
    private val decodeNs = AtomicLong()
    private val decodeMaxNs = AtomicLong()

    private val renders = AtomicLong()
    private val redraws = AtomicLong()
    private val copyNs = AtomicLong()
    private val drawNs = AtomicLong()
    private val renderMaxNs = AtomicLong()
    private val copiedPixels = AtomicLong()
    private val fullScreenCopies = AtomicLong()
    private val cpuNs = AtomicLong()
    private val sessionAllocObjects = AtomicLong()
    private val sessionAllocBytes = AtomicLong()

    /**
     * Un `FramebufferUpdate` a été décodé : [rectangles] rectangles couvrant [pixels] pixels, en [nanos] ns (temps du message,
     * **attente des octets du réseau comprise**).
     */
    fun onUpdate(rectangles: Int, pixels: Long, nanos: Long) {
        if (!enabled) return
        updates.incrementAndGet()
        rects.addAndGet(rectangles.toLong())
        decodedPixels.addAndGet(pixels)
        decodeNs.addAndGet(nanos)
        raiseMax(decodeMaxNs, nanos)
    }

    /**
     * Un rendu a eu lieu : [full] s'il redessine tout l'écran (surface créée ou redimensionnée), [copied] pixels copiés du
     * framebuffer dans le Bitmap en [copyNanos] ns, dessin et publication sur la surface en [drawNanos] ns.
     * @param framePixels surface du framebuffer, pour reconnaître une **copie plein écran**.
     */
    fun onRender(full: Boolean, copied: Long, copyNanos: Long, drawNanos: Long, framePixels: Int) {
        if (!enabled) return
        renders.incrementAndGet()
        if (full) redraws.incrementAndGet()
        copiedPixels.addAndGet(copied)
        copyNs.addAndGet(copyNanos)
        drawNs.addAndGet(drawNanos)
        raiseMax(renderMaxNs, copyNanos + drawNanos)
        // Une copie plein écran hors rendu complet est ce que SS-062 cherche à éviter.
        if (!full && framePixels > 0 && copied * 100 >= framePixels.toLong() * FULL_COPY_PERCENT) fullScreenCopies.incrementAndGet()
    }

    /**
     * À appeler **depuis le thread de session** (typiquement après chaque message) : retient son temps processeur et ses
     * allocations cumulés (SS-061). Sans effet si les mesures sont désactivées ; **n'alloue pas** (trois lectures, trois écritures
     * sur des `AtomicLong` existants).
     */
    fun recordSessionThread() {
        if (!enabled) return
        cpuNs.set(threadMeter.cpuNanos())
        sessionAllocObjects.set(threadMeter.allocatedObjects())
        sessionAllocBytes.set(threadMeter.allocatedBytes())
    }

    /** Lit tous les compteurs (non atomique d'un champ à l'autre : suffisant pour des moyennes sur une seconde). */
    fun counters(): PerfCounters = PerfCounters(
        updates = updates.get(),
        rectangles = rects.get(),
        decodedPixels = decodedPixels.get(),
        decodeNs = decodeNs.get(),
        renders = renders.get(),
        fullRedraws = redraws.get(),
        copyNs = copyNs.get(),
        drawNs = drawNs.get(),
        copiedPixels = copiedPixels.get(),
        fullScreenCopies = fullScreenCopies.get(),
        bytesReceived = traffic.received,
        bytesSent = traffic.sent,
        sessionCpuNs = cpuNs.get(),
        sessionAllocObjects = sessionAllocObjects.get(),
        sessionAllocBytes = sessionAllocBytes.get()
    )

    /** Plus long décodage depuis le dernier appel, en ns ; remis à zéro. */
    fun takeDecodeMaxNs(): Long = decodeMaxNs.getAndSet(0)

    /** Plus long rendu depuis le dernier appel, en ns ; remis à zéro. */
    fun takeRenderMaxNs(): Long = renderMaxNs.getAndSet(0)

    /** Remet tous les compteurs à zéro (début d'une mesure). */
    fun reset() {
        for (c in listOf(updates, rects, decodedPixels, decodeNs, decodeMaxNs, renders, redraws, copyNs, drawNs, renderMaxNs,
            copiedPixels, fullScreenCopies, cpuNs, sessionAllocObjects, sessionAllocBytes)) c.set(0)
        traffic.reset()
    }

    private fun raiseMax(max: AtomicLong, value: Long) {
        while (true) {
            val current = max.get()
            if (value <= current || max.compareAndSet(current, value)) return
        }
    }

    companion object {
        /** Une copie couvrant au moins ce pourcentage du framebuffer est une « copie plein écran ». */
        const val FULL_COPY_PERCENT = 90
    }
}

/**
 * Octets reçus et envoyés, comptés par la socket **seulement quand les mesures sont actives** (une lecture de booléen sinon).
 * `received` n'est écrit que par le thread de lecture ; `sent` peut l'être par plusieurs threads (battement, entrées, session).
 */
class TrafficCounter internal constructor(private val stats: PerfStats) {
    @Volatile
    var received: Long = 0L
        private set
    private val sentTotal = AtomicLong()

    /** Octets envoyés depuis la dernière remise à zéro. */
    val sent: Long
        get() = sentTotal.get()

    /** [n] octets viennent d'être lus sur la socket. Thread de lecture uniquement. */
    fun onReceived(n: Int) {
        if (stats.enabled && n > 0) received += n // un seul écrivain : pas besoin d'addition atomique
    }

    /** [n] octets viennent d'être écrits sur la socket. */
    fun onSent(n: Int) {
        if (stats.enabled && n > 0) sentTotal.addAndGet(n.toLong())
    }

    internal fun reset() {
        received = 0L
        sentTotal.set(0)
    }
}

/** Valeurs brutes des compteurs à un instant (cumulées depuis la dernière remise à zéro). */
data class PerfCounters(
    val updates: Long = 0,
    val rectangles: Long = 0,
    val decodedPixels: Long = 0,
    val decodeNs: Long = 0,
    val renders: Long = 0,
    val fullRedraws: Long = 0,
    val copyNs: Long = 0,
    val drawNs: Long = 0,
    val copiedPixels: Long = 0,
    val fullScreenCopies: Long = 0,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val sessionCpuNs: Long = 0,
    /** Objets alloués par le thread de session (cumul de ce thread ; repart de 0 avec chaque nouvelle session). */
    val sessionAllocObjects: Long = 0,
    val sessionAllocBytes: Long = 0
)
