package fr.webinfoconcept.secondscreen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import fr.webinfoconcept.secondscreen.perf.PerfStats
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer
import fr.webinfoconcept.secondscreen.rfb.protocol.RectangleListener

/**
 * Vue qui affiche le [Framebuffer] de l'écran distant (SS-030, SS-031, SS-032).
 *
 * ## Pipeline (SS-031)
 *
 * ```text
 * thread I/O : décodeur écrit les pixels -> [rectangleListener] (zone modifiée) -> [onFramebufferUpdated]
 *                                                                                     |
 *   copie de la SEULE zone modifiée dans le Bitmap, puis dessin de cette zone sur la surface
 * ```
 *
 * - le [rectangleListener] retient la boîte englobante des rectangles décodés ([DirtyRegion]) ;
 * - [onFramebufferUpdated], appelé **une fois par `FramebufferUpdate`** (pas par rectangle), copie cette zone du
 *   framebuffer (`IntArray`) dans le [Bitmap] de rendu avec `setPixels(offset, stride)` puis la dessine avec
 *   `lockCanvas(dirty)`, dont la doc garantit que les pixels hors de la zone sont conservés ;
 * - le Bitmap, le [Rect] de zone et le [Paint] sont alloués **une seule fois** : aucune allocation par image ;
 * - **seuls les rectangles modifiés sont copiés** dans le Bitmap (SS-062, voir [DirtyRegion]), mais c'est leur boîte
 *   englobante qui est redessinée sur la surface : `lockCanvas` exige de tout redessiner dans la zone qu'on lui donne ;
 * - le dessin se fait sur le thread appelant (le thread I/O), sans thread de rendu supplémentaire
 *   (ARCHITECTURE.md : mesurer avant d'en ajouter un).
 *
 * **Threads** : aucun verrou sur les pixels. Le décodeur écrit ses pixels *puis* signale la zone, le rendu prend la
 * zone *puis* lit les pixels : le verrou du [DirtyRegion] garantit la visibilité. Un rectangle en cours de
 * décodage qui recouvre une zone déjà signalée peut donc être visible à moitié pendant une image ; il est
 * corrigé au rendu suivant, quand il est terminé et signalé. Le verrou [renderLock] sérialise le dessin avec
 * [surfaceDestroyed] : après le retour de ce callback plus aucun dessin ne peut être en cours (contrat de
 * `SurfaceView`).
 *
 * ## Fidélité des pixels (SS-032, aucun scaling sur résolution native)
 * - l'image est dessinée en (0, 0), sans mise à l'échelle ; le filtrage est désactivé ;
 * - le Bitmap est en [Bitmap.DENSITY_NONE] : sans cela Android peut redimensionner selon la densité de l'écran ;
 * - la surface est en `RGBX_8888` (32 bits) : le tampon par défaut d'un `SurfaceView` sur les Android anciens
 *   est en 16 bits et dégraderait les couleurs.
 * [geometry] dit si le rendu est natif (1:1 exact), tronqué ou entouré de noir. Le centrage et la mise à l'échelle
 * pour un serveur qui n'est pas en 1280×800 relèvent de SS-033.
 *
 * ## Mise à l'échelle avec bandes noires (SS-033)
 * Quand l'écran distant n'est pas en 1280×800 (ou que [fitToScreen] est demandé), l'image est **ajustée avec son ratio
 * conservé**, centrée, avec des bandes noires ([RenderGeometry]). Le rendu 1:1 ci-dessus reste inchangé pour la résolution
 * cible : la mise à l'échelle ne s'applique que dans les cas ci-dessus.
 * - le Bitmap contient toujours **le framebuffer entier à jour** ; seule la zone modifiée y est recopiée, comme en 1:1 ;
 * - une mise à jour partielle ne redessine que **la zone modifiée à l'écran** (la boîte englobante, élargie d'un pixel du
 *   framebuffer pour le filtrage, puis arrondie vers l'extérieur) : le Bitmap entier est dessiné avec la même
 *   `Matrix` **découpé** à cette zone, donc chaque pixel est calculé exactement comme lors d'un rendu complet, sans
 *   couture entre la zone redessinée et le reste ;
 * - le filtrage bilinéaire n'est activé que pour ce chemin ; le chemin 1:1 n'interpole jamais.
 *
 * **Écran allumé** : c'est un moniteur ; tant que la vue est affichée l'écran ne s'éteint pas ([setKeepScreenOn]).
 */
class RemoteSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, RenderTarget {

    private val renderLock = Any()

    /** Compteurs de performance (SS-060) ; `null` ou désactivés : un rendu ne coûte qu'une lecture de booléen de plus. */
    @Volatile
    var perfStats: PerfStats? = null

    // Tout ce qui suit est protégé par renderLock.
    private var framebuffer: Framebuffer? = null
    private var dirty: DirtyRegion? = null
    private var bitmap: Bitmap? = null
    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var needsFullRedraw = true
    @Volatile private var currentGeometry: RenderGeometry? = null // écrit sous renderLock, lu sans verrou (chemin tactile)
    private var fitRequested = false

    // Réutilisés à chaque image : aucune allocation par rendu.
    private val takenBounds = IntArray(4)
    private val copyRects = IntArray(4 * DirtyRegion.MAX_RECTS) // rectangles à copier dans le Bitmap (SS-062)
    private var copyRectCount = 0
    private val lockRect = Rect()
    private val imageRect = Rect()
    private val matrix = Matrix()
    private val paint = Paint().apply {
        isFilterBitmap = false // jamais d'interpolation : un pixel source = un pixel écran
        isDither = false
        isAntiAlias = false
    }

    // Filtrage bilinéaire pour l'image mise à l'échelle uniquement.
    private val scaledPaint = Paint().apply {
        isFilterBitmap = true
        isDither = false
        isAntiAlias = false
    }

    /**
     * À passer au décodeur (`ServerMessageReader`) : signale chaque rectangle décodé. Appelé sur le thread I/O.
     * Sans allocation ; ne dessine pas (le dessin se fait dans [onFramebufferUpdated]).
     */
    override val rectangleListener: RectangleListener = object : RectangleListener {
        override fun onRectangle(x: Int, y: Int, w: Int, h: Int) {
            dirty?.add(x, y, w, h)
        }
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
        keepScreenOn = true
    }

    /**
     * Comment le framebuffer tient dans la surface actuelle (natif, tronqué, entouré de noir), ou `null` tant
     * qu'il n'y a ni framebuffer ni surface.
     */
    val geometry: RenderGeometry?
        get() = currentGeometry

    /**
     * Ajuster à l'écran, avec bandes noires et ratio conservé, **même** un écran distant en 1280×800 (SS-033). Faux par
     * défaut : un 1280×800 est alors dessiné pixel pour pixel, rogné si la surface est plus petite. Un écran distant qui
     * n'est pas en 1280×800 est toujours ajusté.
     */
    var fitToScreen: Boolean
        get() = synchronized(renderLock) { fitRequested }
        set(value) {
            synchronized(renderLock) {
                if (fitRequested == value) return
                fitRequested = value
                needsFullRedraw = true
                updateGeometry()
                renderLocked()
            }
        }

    /**
     * Définit le framebuffer à afficher (ou `null` pour n'afficher que du noir) et le redessine en entier si la
     * surface est prête. Le Bitmap de rendu est recréé si la taille change.
     */
    fun setFramebuffer(source: Framebuffer?) {
        synchronized(renderLock) {
            framebuffer = source
            dirty = source?.let { DirtyRegion(it.width, it.height) }
            releaseBitmap()
            needsFullRedraw = true
            updateGeometry()
            renderLocked()
        }
    }

    /**
     * À appeler **une fois par `FramebufferUpdate`** décodé, une fois tous ses rectangles écrits et signalés par
     * [rectangleListener]. Dessine la zone modifiée depuis le dernier appel. Sans effet s'il n'y a rien à dessiner
     * ou si la surface n'est pas prête. Peut être appelé depuis n'importe quel thread, typiquement le thread I/O.
     */
    override fun onFramebufferUpdated() {
        synchronized(renderLock) { renderLocked() }
    }

    /** Redessine l'écran entier (reprise après une pause, changement de taille...). */
    fun redrawAll() {
        synchronized(renderLock) {
            needsFullRedraw = true
            renderLocked()
        }
    }

    /** Dessine ce qui doit l'être. Appelé avec [renderLock] tenu. */
    private fun renderLocked() {
        if (!surfaceReady || surfaceWidth <= 0 || surfaceHeight <= 0) return // taille inconnue avant surfaceChanged
        val fb = framebuffer
        val region = dirty

        // Un Bitmap neuf est vide : il faut alors y recopier tout le framebuffer. À décider AVANT de choisir la zone.
        if (fb != null) ensureBitmap(fb)

        val geo = currentGeometry
        if (fb != null && region != null && geo != null && geo.isScaled) {
            renderScaledLocked(fb, region, geo)
            return
        }

        // Zone à dessiner : tout l'écran, ou la zone modifiée.
        if (fb == null || region == null) {
            if (!needsFullRedraw) return
            lockRect.set(0, 0, surfaceWidth, surfaceHeight)
        } else if (needsFullRedraw) {
            region.clear() // le rendu complet couvre déjà tout ce qui était en attente
            copyRectCount = 0
            lockRect.set(0, 0, surfaceWidth, surfaceHeight)
        } else {
            copyRectCount = region.take(takenBounds, copyRects)
            if (copyRectCount == 0) return
            // Un serveur plus grand que l'écran peut modifier des pixels hors de la surface : on les ignore.
            if (!lockRect.setIntersect(takenBounds, surfaceWidth, surfaceHeight)) return
        }

        val stats = perfStats?.takeIf { it.enabled }
        val startNs = if (stats != null) System.nanoTime() else 0L
        val wasFull = needsFullRedraw
        var copied = 0L
        var copyNanos = 0L
        val canvas = holder.lockCanvas(lockRect) ?: return
        try {
            // lockCanvas peut avoir agrandi lockRect : c'est la zone que l'on doit réellement peindre.
            if (needsFullRedraw) canvas.drawColor(Color.BLACK)
            val bmp = bitmap
            if (fb != null && bmp != null) {
                val left = maxOf(0, lockRect.left)
                val top = maxOf(0, lockRect.top)
                val right = minOf(fb.width, lockRect.right)
                val bottom = minOf(fb.height, lockRect.bottom)
                if (right > left && bottom > top) {
                    // Seule la zone à dessiner est copiée : offset = premier pixel, stride = largeur du framebuffer.
                    val copyStart = if (stats != null) System.nanoTime() else 0L
                    copied = copyIntoBitmap(bmp, fb, left, top, right, bottom, wasFull)
                    if (stats != null) copyNanos = System.nanoTime() - copyStart
                    lockRect.set(left, top, right, bottom)
                    canvas.drawBitmap(bmp, lockRect, lockRect, paint)
                }
            }
            needsFullRedraw = false
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        if (stats != null) {
            val total = System.nanoTime() - startNs
            stats.onRender(wasFull, copied, copyNanos, total - copyNanos, (fb?.width ?: 0) * (fb?.height ?: 0))
        }
    }

    /**
     * Copie du framebuffer dans le Bitmap (SS-062). [all] (rendu complet) : toute la zone (left, top, right, bottom) ; sinon
     * **seulement les rectangles réellement modifiés** ([copyRects]), rognés à cette zone : le Bitmap contient déjà le reste de
     * l'image à jour, donc deux petites zones éloignées ne font plus copier la boîte qui les contient.
     * @return le nombre de pixels copiés.
     */
    private fun copyIntoBitmap(bmp: Bitmap, fb: Framebuffer, left: Int, top: Int, right: Int, bottom: Int, all: Boolean): Long {
        if (all || copyRectCount == 0) {
            bmp.setPixels(fb.pixels, top * fb.width + left, fb.width, left, top, right - left, bottom - top)
            return (right - left).toLong() * (bottom - top)
        }
        var copied = 0L
        for (i in 0 until copyRectCount) {
            val o = 4 * i
            val l = maxOf(left, copyRects[o])
            val t = maxOf(top, copyRects[o + 1])
            val r = minOf(right, copyRects[o + 2])
            val b = minOf(bottom, copyRects[o + 3])
            if (r <= l || b <= t) continue
            bmp.setPixels(fb.pixels, t * fb.width + l, fb.width, l, t, r - l, b - t)
            copied += (r - l).toLong() * (b - t)
        }
        return copied
    }

    /**
     * Rendu mis à l'échelle (SS-033) : voir la description de la classe. Appelé avec [renderLock] tenu, [ensureBitmap] déjà fait.
     */
    private fun renderScaledLocked(fb: Framebuffer, region: DirtyRegion, geo: RenderGeometry) {
        val bmp = bitmap ?: return
        val stats = perfStats?.takeIf { it.enabled }
        val full = needsFullRedraw
        var copied = 0L
        var copyNanos = 0L
        if (full) {
            region.clear() // le rendu complet couvre déjà tout ce qui était en attente
            lockRect.set(0, 0, surfaceWidth, surfaceHeight)
            val copyStart = if (stats != null) System.nanoTime() else 0L
            bmp.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
            if (stats != null) { copyNanos = System.nanoTime() - copyStart; copied = fb.width.toLong() * fb.height }
        } else {
            copyRectCount = region.take(takenBounds, copyRects)
            if (copyRectCount == 0) return
            val l = maxOf(0, takenBounds[0])
            val t = maxOf(0, takenBounds[1])
            val r = minOf(fb.width, takenBounds[2])
            val b = minOf(fb.height, takenBounds[3])
            if (r <= l || b <= t) return
            val copyStart = if (stats != null) System.nanoTime() else 0L
            copied = copyIntoBitmap(bmp, fb, l, t, r, b, false)
            if (stats != null) copyNanos = System.nanoTime() - copyStart
            // Zone de l'écran touchée : la boîte élargie d'un pixel du framebuffer (le filtre bilinéaire lit les voisins),
            // arrondie vers l'extérieur, limitée à l'image.
            val s = geo.scale
            val left = Math.floor((geo.destLeft + (l - 1) * s).toDouble()).toInt()
            val top = Math.floor((geo.destTop + (t - 1) * s).toDouble()).toInt()
            val right = Math.ceil((geo.destLeft + (r + 1) * s).toDouble()).toInt()
            val bottom = Math.ceil((geo.destTop + (b + 1) * s).toDouble()).toInt()
            imageRect.set(geo.destLeft, geo.destTop, geo.destLeft + geo.destWidth, geo.destTop + geo.destHeight)
            lockRect.set(maxOf(left, imageRect.left), maxOf(top, imageRect.top), minOf(right, imageRect.right), minOf(bottom, imageRect.bottom))
            if (lockRect.isEmpty) return
        }

        val drawStart = if (stats != null) System.nanoTime() else 0L
        val canvas = holder.lockCanvas(lockRect) ?: return
        try {
            // lockCanvas peut avoir agrandi lockRect : c'est la zone que l'on doit réellement repeindre. Le canvas est déjà
            // découpé à cette zone : dessiner le Bitmap entier ne calcule que les pixels qu'elle contient.
            imageRect.set(geo.destLeft, geo.destTop, geo.destLeft + geo.destWidth, geo.destTop + geo.destHeight)
            if (full || !imageRect.contains(lockRect)) canvas.drawColor(Color.BLACK) // bandes noires
            matrix.setScale(geo.scale, geo.scale)
            matrix.postTranslate(geo.destLeft.toFloat(), geo.destTop.toFloat())
            canvas.drawBitmap(bmp, matrix, scaledPaint)
            needsFullRedraw = false
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
        stats?.onRender(full, copied, copyNanos, System.nanoTime() - drawStart, fb.width * fb.height)
    }

    /** Intersection de la boîte [bounds] (gauche, haut, droite, bas) avec la surface ; `false` si elle est vide. */
    private fun Rect.setIntersect(bounds: IntArray, surfaceW: Int, surfaceH: Int): Boolean {
        val l = maxOf(0, bounds[0])
        val t = maxOf(0, bounds[1])
        val r = minOf(surfaceW, bounds[2])
        val b = minOf(surfaceH, bounds[3])
        if (r <= l || b <= t) return false
        set(l, t, r, b)
        return true
    }

    /** Requis par l'accessibilité : `TouchInput` l'appelle quand un tap est reconnu (aucun écouteur de clic ici). */
    override fun performClick(): Boolean = super.performClick()

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(renderLock) {
            surfaceReady = true
            needsFullRedraw = true
            renderLocked()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(renderLock) {
            surfaceWidth = width
            surfaceHeight = height
            needsFullRedraw = true
            updateGeometry()
            renderLocked()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Pris sous renderLock : si un dessin est en cours sur le thread I/O, on l'attend ; ensuite plus aucun ne démarre.
        synchronized(renderLock) { surfaceReady = false }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        synchronized(renderLock) { releaseBitmap() }
    }

    private fun updateGeometry() {
        val fb = framebuffer
        currentGeometry = if (fb == null) null else RenderGeometry(fb.width, fb.height, surfaceWidth, surfaceHeight, fitRequested)
    }

    /**
     * S'assure qu'un Bitmap de la taille de [fb] existe (créé au premier besoin, puis réutilisé). Un Bitmap neuf
     * est vide : il impose un rendu complet. Appelé avec [renderLock] tenu.
     */
    private fun ensureBitmap(fb: Framebuffer) {
        val current = bitmap
        if (current != null && current.width == fb.width && current.height == fb.height) return
        releaseBitmap()
        bitmap = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888).also {
            it.density = Bitmap.DENSITY_NONE // aucune mise à l'échelle liée à la densité de l'écran
            it.setHasAlpha(false)
        }
        needsFullRedraw = true
    }

    private fun releaseBitmap() {
        bitmap?.recycle()
        bitmap = null
    }
}
