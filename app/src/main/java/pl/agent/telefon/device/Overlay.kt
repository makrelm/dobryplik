package pl.agent.telefon.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.AgentStatus
import pl.agent.telefon.core.Prefs
import pl.agent.telefon.core.RuntimeState

/**
 * Mały, przesuwalny panel statusu nad używaną aplikacją (okno TYPE_ACCESSIBILITY_OVERLAY tworzone przez
 * usługę dostępności — nie wymaga osobnego uprawnienia „wyświetlanie nad innymi aplikacjami”).
 * Przyciski: Pauza/Wznów, Stop, (przy zatwierdzeniu) Tak/Nie oraz ✕ (wyłączenie nakładki).
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var scope: CoroutineScope? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var body: TextView? = null
    private var bPause: TextView? = null
    private var bYes: TextView? = null
    private var bNo: TextView? = null

    fun attach(s: AccessibilityService) {
        detach()
        svc = s
        wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch { AgentRuntime.state.collect { render(it) } }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        removeView()
        svc = null
        wm = null
    }

    /** Na czas gestu agenta nakładka przepuszcza dotyk, żeby go nie przechwyciła. */
    fun setPassThrough(on: Boolean) {
        main.post {
            val p = lp
            val r = root
            if (p != null && r != null) {
                p.flags = if (on) {
                    p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                try { wm?.updateViewLayout(r, p) } catch (e: Exception) { }
            }
        }
    }

    private fun render(st: RuntimeState) {
        val show = st.status.hasTask && Prefs.overlayEnabled()
        if (!show) {
            removeView()
            return
        }
        ensureView()
        val approval = st.approval
        title?.text = "Agent: ${st.status.label}"
        body?.text = when {
            approval != null -> approval.details.lineSequence().firstOrNull().orEmpty()
            st.question != null -> "Pytanie w aplikacji: ${st.question}"
            else -> st.stepText.ifBlank { st.message }
        }
        bPause?.text = if (st.status == AgentStatus.PAUSED) "Wznów" else "Pauza"
        val v = if (approval != null) View.VISIBLE else View.GONE
        bYes?.visibility = v
        bNo?.visibility = v
    }

    private fun ensureView() {
        if (root != null) return
        val s = svc ?: return
        val w = wm ?: return
        val d = s.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun button(label: String, bg: Int, click: () -> Unit): TextView = TextView(s).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { click() }
        }

        val container = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE6121A22.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        val header = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = TextView(s).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(s).apply {
            text = "✕"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                Prefs.setOverlayEnabled(false)
                removeView()
            }
        }
        header.addView(t)
        header.addView(close)

        val b = TextView(s).apply {
            setTextColor(0xFFCFD8DC.toInt())
            textSize = 12f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(230)
        }
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val gap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) } }
        val pause = button("Pauza", 0xFF37474F.toInt()) {
            if (AgentRuntime.state.value.status == AgentStatus.PAUSED) AgentRuntime.resume() else AgentRuntime.pause()
        }
        val stop = button("Stop", 0xFFB71C1C.toInt()) { AgentRuntime.stop("Zatrzymano przyciskiem w nakładce.") }
        val yes = button("Tak", 0xFF2E7D32.toInt()) { AgentRuntime.approve(true) }
        val no = button("Nie", 0xFF6D4C41.toInt()) { AgentRuntime.approve(false) }
        yes.visibility = View.GONE
        no.visibility = View.GONE
        row.addView(pause, gap())
        row.addView(stop, gap())
        row.addView(yes, gap())
        row.addView(no, gap())

        container.addView(header)
        container.addView(b)
        container.addView(row)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (Prefs.keepScreenOnEnabled()) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(120)
        }

        // przeciąganie panelu za nagłówek
        t.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0
            var iy = 0
            var tx = 0f
            var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = lp ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = params.x
                        iy = params.y
                        tx = e.rawX
                        ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = ix + (e.rawX - tx).toInt()
                        params.y = iy + (e.rawY - ty).toInt()
                        try { wm?.updateViewLayout(root, params) } catch (ex: Exception) { }
                    }
                }
                return true
            }
        })

        try {
            w.addView(container, p)
        } catch (e: Exception) {
            return
        }
        root = container
        lp = p
        title = t
        body = b
        bPause = pause
        bYes = yes
        bNo = no
    }

    private fun removeView() {
        val r = root
        if (r != null) {
            try { wm?.removeView(r) } catch (e: Exception) { }
        }
        root = null
        lp = null
        title = null
        body = null
        bPause = null
        bYes = null
        bNo = null
    }
}
