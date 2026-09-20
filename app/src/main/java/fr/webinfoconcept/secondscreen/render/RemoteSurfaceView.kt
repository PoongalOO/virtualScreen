package fr.webinfoconcept.secondscreen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
 * **Écran allumé** : c'est un moniteur ; tant que la vue est affichée l'écran ne s'éteint pas ([setKeepScreenOn]).
 */
class RemoteSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, RenderTarget {

    private val renderLock = Any()

    // Tout ce qui suit est protégé par renderLock.
    private var framebuffer: Framebuffer? = null
    private var dirty: DirtyRegion? = null
    private var bitmap: Bitmap? = null
    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var needsFullRedraw = true
    private var currentGeometry: RenderGeometry? = null

    // Réutilisés à chaque image : aucune allocation par rendu.
    private val takenBounds = IntArray(4)
    private val lockRect = Rect()
    private val paint = Paint().apply {
        isFilterBitmap = false // jamais d'interpolation : un pixel source = un pixel écran
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
        get() = synchronized(renderLock) { currentGeometry }

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

        // Zone à dessiner : tout l'écran, ou la zone modifiée.
        if (fb == null || region == null) {
            if (!needsFullRedraw) return
            lockRect.set(0, 0, surfaceWidth, surfaceHeight)
        } else if (needsFullRedraw) {
            region.clear() // le rendu complet couvre déjà tout ce qui était en attente
            lockRect.set(0, 0, surfaceWidth, surfaceHeight)
        } else {
            if (!region.take(takenBounds)) return
            // Un serveur plus grand que l'écran peut modifier des pixels hors de la surface : on les ignore.
            if (!lockRect.setIntersect(takenBounds, surfaceWidth, surfaceHeight)) return
        }

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
                    bmp.setPixels(fb.pixels, top * fb.width + left, fb.width, left, top, right - left, bottom - top)
                    lockRect.set(left, top, right, bottom)
                    canvas.drawBitmap(bmp, lockRect, lockRect, paint)
                }
            }
            needsFullRedraw = false
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
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
        currentGeometry = if (fb == null) null else RenderGeometry(fb.width, fb.height, surfaceWidth, surfaceHeight)
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
