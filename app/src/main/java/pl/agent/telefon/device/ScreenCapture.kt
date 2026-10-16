package pl.agent.telefon.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.WindowManager
import kotlinx.coroutines.delay
import pl.agent.telefon.core.AgentRuntime
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Przechwytywanie ekranu przez oficjalne API MediaProjection (zgoda użytkownika wymagana przy każdej sesji).
 * Ostatnia klatka jest trzymana w pamięci, bo przy statycznym ekranie system nie dostarcza nowych klatek.
 */
object ScreenCapture {
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastImage: Image? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 0

    val active: Boolean get() = projection != null

    fun start(ctx: Context, resultCode: Int, data: Intent): Boolean {
        stop()
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(resultCode, data)
            // Android 14+: callback musi być zarejestrowany przed utworzeniem VirtualDisplay
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === p) {
                        release()
                        AgentRuntime.onCaptureLost("Przechwytywanie ekranu zostało przerwane przez system lub użytkownika.")
                    }
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            dpi = ctx.resources.displayMetrics.densityDpi
            val t = HandlerThread("agent-capture")
            t.start()
            thread = t
            handler = Handler(t.looper)
            val (w, h) = realSize(ctx)
            val scale = min(1f, 1280f / max(w, h).toFloat())
            capW = max(2, (w * scale).roundToInt())
            capH = max(2, (h * scale).roundToInt())
            val r = newReader(capW, capH)
            reader = r
            display = p.createVirtualDisplay(
                "AgentCapture", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            AgentRuntime.update { it.copy(captureActive = true) }
            true
        } catch (e: Throwable) {
            release()
            false
        }
    }

    fun stop() {
        release()
        AgentRuntime.update { it.copy(captureActive = false) }
    }

    private fun release() {
        val p = projection
        projection = null
        synchronized(lock) {
            lastImage?.close()
            lastImage = null
        }
        try { display?.release() } catch (e: Throwable) { }
        try { reader?.close() } catch (e: Throwable) { }
        try { p?.stop() } catch (e: Throwable) { }
        display = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun realSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Throwable) { null }
            if (img != null) {
                synchronized(lock) {
                    lastImage?.close()
                    lastImage = img
                }
            }
        }, handler)
        return r
    }

    /** Po obrocie ekranu zmieniamy rozmiar istniejącego VirtualDisplay (nie wolno tworzyć drugiego). */
    private fun ensureOrientation(ctx: Context) {
        val d = display ?: return
        val (w, h) = realSize(ctx)
        if ((w > h) == (capW > capH)) return
        try {
            val scale = min(1f, 1280f / max(w, h).toFloat())
            val nw = max(2, (w * scale).roundToInt())
            val nh = max(2, (h * scale).roundToInt())
            val nr = newReader(nw, nh)
            d.resize(nw, nh, dpi)
            d.surface = nr.surface
            synchronized(lock) {
                lastImage?.close()
                lastImage = null
            }
            try { reader?.close() } catch (e: Throwable) { }
            reader = nr
            capW = nw
            capH = nh
        } catch (e: Throwable) {
            // zostaje poprzednia konfiguracja
        }
    }

    suspend fun latestBitmap(ctx: Context): Bitmap? {
        if (!active) return null
        ensureOrientation(ctx)
        var tries = 0
        while (tries < 15) {
            val bmp = synchronized(lock) {
                try { lastImage?.let { toBitmap(it) } } catch (e: Throwable) { null }
            }
            if (bmp != null) return bmp
            delay(100)
            tries++
        }
        return null
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val buf = plane.buffer
        buf.rewind()
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return if (rowPadding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }
}

/** Pobiera zrzut (MediaProjection, a gdy go brak — usługa dostępności), skaluje i koduje do JPEG. */
object Capture {
    suspend fun grabJpeg(svc: AgentAccessibilityService, maxSide: Int): ByteArray? {
        var bmp: Bitmap? = ScreenCapture.latestBitmap(svc)
        if (bmp == null) bmp = svc.takeScreenshotFallback()
        if (bmp == null) return null
        val side = maxSide.coerceIn(256, 1600)
        val m = max(bmp.width, bmp.height)
        val scaled = if (m > side) {
            val s = side.toFloat() / m
            Bitmap.createScaledBitmap(
                bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true
            )
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }
}
