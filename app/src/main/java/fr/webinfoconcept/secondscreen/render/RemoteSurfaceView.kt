package fr.webinfoconcept.secondscreen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import fr.webinfoconcept.secondscreen.rfb.framebuffer.Framebuffer

/**
 * Vue qui affiche le [Framebuffer] de l'écran distant (SS-030).
 *
 * **Rendu** : le framebuffer (`IntArray` ARGB) est copié dans un [Bitmap] mutable de même taille, alloué
 * une seule fois puis réutilisé, que l'on dessine sur le `Canvas` de la surface. Cela suit la conception
 * d'ARCHITECTURE.md (stockage principal + Bitmap de rendu) : SS-031 n'aura qu'à ne copier que les zones
 * modifiées.
 *
 * **Fidélité des pixels** (exigence de SS-032, aucun scaling sur résolution native) :
 * - l'image est dessinée en (0, 0), sans mise à l'échelle ; le filtrage est désactivé ;
 * - le Bitmap est en [Bitmap.DENSITY_NONE] : sans cela Android peut redimensionner le dessin selon la densité
 *   de l'écran ;
 * - la surface est en `RGBX_8888` (32 bits) : le tampon par défaut d'un `SurfaceView` sur les Android anciens
 *   est en 16 bits et dégraderait les couleurs.
 * Ce que la vue affiche hors du framebuffer (si la surface est plus grande) est noir. Le centrage et la mise à
 * l'échelle pour un serveur qui n'est pas en 1280×800 relèvent de SS-033.
 *
 * **Écran allumé** : c'est un moniteur ; tant que la vue est affichée l'écran ne s'éteint pas
 * ([setKeepScreenOn]), ce qui évite aussi que la radio Wi-Fi s'endorme avec l'écran.
 *
 * **Threads** : SS-030 dessine depuis le thread UI (callbacks de surface et [setFramebuffer]). Le rendu
 * depuis le thread I/O et la synchronisation avec le décodage relèvent de SS-031.
 */
class RemoteSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var framebuffer: Framebuffer? = null
    private var bitmap: Bitmap? = null
    private var surfaceReady = false

    private val paint = Paint().apply {
        isFilterBitmap = false // jamais d'interpolation : un pixel source = un pixel écran
        isDither = false
        isAntiAlias = false
    }

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
        keepScreenOn = true
    }

    /**
     * Définit le framebuffer à afficher (ou `null` pour n'afficher que du noir) et le dessine si la surface
     * est prête. Le Bitmap de rendu est recréé si la taille change.
     */
    fun setFramebuffer(source: Framebuffer?) {
        framebuffer = source
        releaseBitmap() // recréé à la bonne taille au prochain rendu
        render()
    }

    /**
     * Copie le framebuffer dans le Bitmap et le dessine sur la surface. Sans effet si la surface n'est pas
     * prête. Copie l'écran entier : SS-031 restreindra à la zone modifiée.
     */
    fun render() {
        if (!surfaceReady) return
        val fb = framebuffer

        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            if (fb != null) {
                val bmp = bitmapFor(fb)
                bmp.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
                canvas.drawBitmap(bmp, 0f, 0f, paint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        render()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        render()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseBitmap()
    }

    /** Le Bitmap de rendu de la taille de [fb], créé au premier besoin puis réutilisé (aucune allocation par rendu). */
    private fun bitmapFor(fb: Framebuffer): Bitmap {
        bitmap?.let { if (it.width == fb.width && it.height == fb.height) return it }
        releaseBitmap()
        return Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888).also {
            it.density = Bitmap.DENSITY_NONE // aucune mise à l'échelle liée à la densité de l'écran
            it.setHasAlpha(false)
            bitmap = it
        }
    }

    private fun releaseBitmap() {
        bitmap?.recycle()
        bitmap = null
    }
}
