package fr.webinfoconcept.secondscreen.render

import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer

/**
 * Source de mises à jour **provisoire** pour valider le rendu sur l'appareil (SS-031), en attendant la connexion
 * réelle (SS-054) : un carré de 64×64 se déplace sur le motif de test. Elle suit exactement le chemin du
 * décodeur réel, sur un thread d'arrière-plan : écrire les pixels dans le framebuffer, signaler chaque rectangle,
 * puis demander le rendu une fois par « mise à jour ».
 *
 * Chaque image modifie deux rectangles : l'ancienne position du carré, restaurée à partir du motif, et la nouvelle.
 * Position et couleur sont fonction du seul numéro d'image : le contenu final après N images est déterministe et donc
 * vérifiable pixel par pixel. Aucune allocation par image (buffers créés une fois).
 *
 * @param maxFrames nombre d'images après lequel le pilote s'arrête de lui-même (0 = jusqu'à [stop]).
 * @param targetFps cadence visée (0 = aussi vite que possible, pour mesurer la capacité du rendu).
 */
class RenderTestDriver(
    private val framebuffer: Framebuffer,
    private val target: RenderTarget,
    private val maxFrames: Int = 0,
    private val targetFps: Int = 0
) {
    private val restoreRow = IntArray(SIZE)

    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false

    /** Images produites jusqu'ici. */
    @Volatile var frames: Int = 0
        private set

    /** `true` quand [maxFrames] images ont été produites. */
    @Volatile var finished: Boolean = false
        private set

    /** Durée de production des images, en ms (la dernière si le pilote est arrêté). */
    @Volatile var elapsedMs: Long = 0
        private set

    val isRunning: Boolean get() = thread != null

    /** Démarre le thread de production. Sans effet s'il tourne déjà. */
    @Synchronized
    fun start() {
        if (thread != null) return
        stopRequested = false
        finished = false
        val worker = Thread({ run() }, "secondscreen-render-test")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /** Arrête le thread et l'attend brièvement. Idempotent. */
    fun stop() {
        val worker: Thread?
        synchronized(this) {
            worker = thread
            stopRequested = true
            thread = null
        }
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun run() {
        val started = System.nanoTime()
        val frameNanos = if (targetFps > 0) 1_000_000_000L / targetFps else 0L
        var frame = 0
        while (!stopRequested && (maxFrames == 0 || frame < maxFrames)) {
            val frameStart = System.nanoTime()
            drawFrame(frame)
            frame++
            frames = frame
            elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (frameNanos > 0) {
                val remainingMs = (frameNanos - (System.nanoTime() - frameStart)) / 1_000_000
                if (remainingMs > 0) try { Thread.sleep(remainingMs) } catch (e: InterruptedException) { return }
            }
        }
        if (!stopRequested) finished = true
    }

    private fun drawFrame(frame: Int) {
        val w = framebuffer.width
        val h = framebuffer.height
        if (frame > 0) {
            val ox = squareX(frame - 1, w)
            val oy = squareY(frame - 1, h)
            restorePattern(ox, oy, w, h)
            target.rectangleListener.onRectangle(ox, oy, SIZE, SIZE)
        }
        val x = squareX(frame, w)
        val y = squareY(frame, h)
        framebuffer.fillRect(x, y, SIZE, SIZE, squareColor(frame))
        target.rectangleListener.onRectangle(x, y, SIZE, SIZE)
        target.onFramebufferUpdated()
    }

    /** Remet le motif de test à la place du carré précédent. */
    private fun restorePattern(x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) restoreRow[col] = RenderTestPattern.pixel(x + col, y + row, w, h)
            framebuffer.writeRect(x, y + row, SIZE, 1, restoreRow)
        }
    }

    companion object {
        const val SIZE = 64

        /** Coin haut-gauche du carré à l'image [frame], dans un écran de [width] × [height]. */
        fun squareX(frame: Int, width: Int): Int = (frame * 7) % (width - SIZE)
        fun squareY(frame: Int, height: Int): Int = (frame * 5) % (height - SIZE)

        /** Couleur du carré à l'image [frame] (opaque). */
        fun squareColor(frame: Int): Int =
            0xFF000000.toInt() or (((frame * 37) and 0xFF) shl 16) or (((frame * 91) and 0xFF) shl 8) or ((frame * 53) and 0xFF)
    }
}
