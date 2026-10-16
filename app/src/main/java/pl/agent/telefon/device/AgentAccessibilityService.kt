package pl.agent.telefon.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.SparseArray
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.agent.telefon.core.ActionOutcome
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.Safety
import pl.agent.telefon.core.UiNode
import pl.agent.telefon.core.UiSnapshot
import kotlin.coroutines.resume

/**
 * Jedyny kanał sterowania telefonem: odczyt struktury UI, kliknięcia, wpisywanie, gesty, nawigacja.
 * Nie używa ADB ani roota. Działa tylko wtedy, gdy użytkownik sam włączy usługę w ustawieniach Androida.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null

        @Volatile
        var lastEventAt: Long = 0L
    }

    /** Węzły z ostatniego odczytu: id z listy elementów -> węzeł (do wykonania akcji). */
    private val nodes = SparseArray<AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastEventAt = SystemClock.uptimeMillis()
        AgentRuntime.update { it.copy(a11yConnected = true) }
        Overlay.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) instance = null
        Overlay.detach()
        AgentRuntime.onA11yLost()
    }

    // ---------------- odczyt ekranu ----------------

    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun emptySnapshot(): UiSnapshot {
        val (w, h) = screenSize()
        return UiSnapshot(currentPackage().orEmpty(), w, h, emptyList(), false)
    }

    /** Lista widocznych, „ciekawych” elementów aktywnego okna albo null, gdy okno jest niedostępne. */
    fun readSnapshot(maxNodes: Int = 120): UiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val (w, h) = screenSize()
        val list = ArrayList<UiNode>()
        var truncated = false
        val rect = Rect()
        nodes.clear()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (list.size >= maxNodes) {
                truncated = true
                return
            }
            if (depth > 40 || !n.isVisibleToUser) return
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isLongClickable ||
                n.isEditable || n.isScrollable || n.isCheckable
            if (interesting) {
                n.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val id = list.size
                    nodes.put(id, n)
                    list.add(
                        UiNode(
                            id = id,
                            cls = (n.className?.toString() ?: "View").substringAfterLast('.'),
                            text = text,
                            desc = desc,
                            rid = (n.viewIdResourceName ?: "").substringAfterLast('/'),
                            l = rect.left, t = rect.top, r = rect.right, b = rect.bottom,
                            clickable = n.isClickable,
                            longClickable = n.isLongClickable,
                            editable = n.isEditable,
                            scrollable = n.isScrollable,
                            checkable = n.isCheckable,
                            checked = n.isChecked,
                            enabled = n.isEnabled,
                            focused = n.isFocused,
                            password = n.isPassword
                        )
                    )
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return UiSnapshot(root.packageName?.toString().orEmpty(), w, h, list, truncated)
    }

    /** Czeka, aż przestaną napływać zdarzenia interfejsu (ekran „ustabilizowany”). */
    suspend fun waitForIdle(quietMs: Long = 450, maxMs: Long = 3500) {
        delay(250)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(100)
        }
    }

    // ---------------- czynności ----------------

    suspend fun tapNode(id: Int, long: Boolean): ActionOutcome {
        val n = nodes.get(id) ?: return ActionOutcome(false, "Brak elementu #$id (ekran się zmienił — odczytaj go ponownie)")
        if (!n.refresh()) return ActionOutcome(false, "Element #$id zniknął z ekranu")
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var t: AccessibilityNodeInfo? = n
        var depth = 0
        while (t != null && depth < 5) {
            val can = if (long) t.isLongClickable else t.isClickable
            if (can && t.isEnabled) {
                if (t.performAction(action)) {
                    return ActionOutcome(true, if (long) "Długie kliknięcie (akcja węzła)" else "Kliknięto (akcja węzła)")
                }
                break
            }
            t = t.parent
            depth++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        val ok = tapXY(r.centerX(), r.centerY(), long)
        return ActionOutcome(ok, if (ok) "Gest dotknięcia w środku elementu" else "Gest dotknięcia nie powiódł się")
    }

    suspend fun tapXY(x: Int, y: Int, long: Boolean): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(p, if (long) 900L else 60L)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(p, durationMs.coerceIn(80L, 3000L))
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean {
        Overlay.setPassThrough(true) // nakładka nie może przechwycić gestu agenta
        delay(60)
        try {
            return suspendCancellableCoroutine<Boolean> { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                    .build()
                val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } finally {
            Overlay.setPassThrough(false)
        }
    }

    fun setText(id: Int?, text: String, replace: Boolean): ActionOutcome {
        val n: AccessibilityNodeInfo? =
            if (id != null) nodes.get(id) else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null) return ActionOutcome(false, "Brak pola tekstowego (podaj id albo najpierw kliknij pole)")
        if (!n.refresh()) return ActionOutcome(false, "Pole zniknęło z ekranu")
        if (!n.isEditable) return ActionOutcome(false, "Ten element nie jest polem edycyjnym")
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val current = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val newText = if (replace) text else current + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionOutcome(false, "System odrzucił wpisanie tekstu")
        }
        n.refresh()
        val now = n.text?.toString().orEmpty()
        val verified = n.isPassword || now == newText || (text.isNotEmpty() && now.contains(text))
        return ActionOutcome(verified, if (verified) "Wpisano tekst (zweryfikowano)" else "Wpisano, ale pole zawiera inny tekst")
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun openApp(query: String, blocklist: Set<String>): ActionOutcome {
        val q = query.trim()
        if (q.isEmpty()) return ActionOutcome(false, "Pusta nazwa aplikacji")
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        var found: String? = apps.firstOrNull { it.activityInfo.packageName.equals(q, ignoreCase = true) }
            ?.activityInfo?.packageName
        if (found == null) {
            val matches = apps.filter { it.loadLabel(pm).toString().contains(q, ignoreCase = true) }
            val exact = matches.firstOrNull { it.loadLabel(pm).toString().equals(q, ignoreCase = true) }
            val best = exact ?: matches.minByOrNull { it.loadLabel(pm).toString().length }
            found = best?.activityInfo?.packageName
        }
        if (found == null) return ActionOutcome(false, "Nie znaleziono aplikacji „$q” wśród zainstalowanych")
        val target: String = found
        if (Safety.isBlocked(target, blocklist)) {
            return ActionOutcome(false, "Aplikacja $target jest na liście zablokowanych")
        }
        val intent = pm.getLaunchIntentForPackage(target)
            ?: return ActionOutcome(false, "Brak aktywności startowej dla $target")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            return ActionOutcome(false, "Android nie pozwolił uruchomić aplikacji: ${e.message}")
        }
        repeat(20) {
            delay(250)
            if (currentPackage() == target) return ActionOutcome(true, "Uruchomiono $target (potwierdzone na ekranie)")
        }
        return ActionOutcome(
            false,
            "Nie potwierdzono otwarcia $target — Android może blokować uruchamianie z tła; spróbuj przez Ekran główny"
        )
    }

    /** Test diagnostyczny: dotknięcie elementu o podanym tekście w aktywnym oknie. */
    suspend fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = root.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        val r = Rect()
        n.getBoundsInScreen(r)
        return tapXY(r.centerX(), r.centerY(), false)
    }

    /** Zrzut przez usługę dostępności (zapasowo, gdy MediaProjection nie jest aktywne). Android 11+. */
    suspend fun takeScreenshotFallback(): Bitmap? = suspendCancellableCoroutine<Bitmap?> { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bmp: Bitmap? = try {
                        val hb = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                        sw
                    } catch (e: Throwable) {
                        null
                    }
                    if (cont.isActive) cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (e: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }
}
