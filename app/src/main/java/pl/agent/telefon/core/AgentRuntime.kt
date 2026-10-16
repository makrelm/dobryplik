package pl.agent.telefon.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.agent.telefon.device.AgentAccessibilityService
import pl.agent.telefon.device.AgentForegroundService
import pl.agent.telefon.device.Notifs

/**
 * Centralny stan agenta (jeden proces). Interfejs, nakładka, powiadomienie i pętla agenta
 * komunikują się przez [state] oraz przez metody sterujące poniżej.
 */
object AgentRuntime {
    private const val APPROVAL_TIMEOUT_MS = 5 * 60_000L
    private const val ASK_TIMEOUT_MS = 30 * 60_000L
    private val ACTIVE_NAMES = setOf("PLANNING", "EXECUTING", "WAITING", "PAUSED")

    lateinit var app: Context
        private set

    val state = MutableStateFlow(RuntimeState())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ostatni zrzut ekranu (JPEG) tylko w pamięci — do podglądu w aplikacji. */
    @Volatile
    var lastShot: ByteArray? = null

    private var job: Job? = null
    private var stopReason: String? = null
    private val pauseGate = MutableStateFlow(false)
    private var approvalWait: CompletableDeferred<Boolean>? = null
    private var answerWait: CompletableDeferred<String>? = null
    private var record: TaskRecord? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Prefs.init(app)
        SecretStore.init(app)
        Store.init(app)
        Notifs.ensureChannels(app)
        NetMonitor.start(app)
        recoverInterrupted()
    }

    fun update(f: (RuntimeState) -> RuntimeState) {
        state.update(f)
    }

    /** Po restarcie procesu: zadanie, które było w toku, oznaczamy jako przerwane (nie wznawiamy go samo). */
    private fun recoverInterrupted() {
        val last = Store.loadAll().firstOrNull() ?: return
        if (last.status in ACTIVE_NAMES) {
            last.status = AgentStatus.STOPPED.name
            last.message = "Przerwane (restart procesu lub zamknięcie aplikacji)."
            last.endedAt = System.currentTimeMillis()
            Store.save(last)
            update { it.copy(interrupted = last) }
        }
    }

    fun dismissInterrupted() {
        update { it.copy(interrupted = null) }
    }

    fun resumeInterrupted(): String? {
        val rec = state.value.interrupted ?: return null
        return startTask(rec.text, rec)
    }

    /** Zwraca komunikat błędu (po polsku) albo null, gdy zadanie wystartowało. */
    fun startTask(text: String, resume: TaskRecord? = null): String? {
        if (state.value.status.hasTask) return "Zadanie już trwa."
        if (text.isBlank()) return "Wpisz polecenie."
        val s = Prefs.load()
        if (!s.consent) return "Najpierw zaakceptuj informację o wysyłaniu danych do API (karta na górze)."
        val keyName = if (s.provider == "gemini") "key_gemini" else "key_deepseek"
        if (SecretStore.get(keyName).isBlank()) return "Brak klucza API — dodaj go w Ustawieniach."
        if (AgentAccessibilityService.instance == null) {
            return "Usługa dostępności nie jest połączona — włącz ją w zakładce Diagnostyka."
        }

        val model = if (s.provider == "gemini") s.geminiModel else s.deepseekModel
        val now = System.currentTimeMillis()
        val rec = resume ?: TaskRecord(
            id = now.toString(), text = text.trim(), startedAt = now, provider = s.provider, model = model
        )
        rec.status = AgentStatus.PLANNING.name
        rec.endedAt = 0L
        rec.message = ""
        record = rec
        Store.save(rec)

        stopReason = null
        pauseGate.value = false
        lastShot = null
        update {
            it.copy(
                status = AgentStatus.PLANNING, taskId = rec.id, taskText = rec.text, stepText = "Start…",
                message = "", steps = rec.steps.toList(), question = null, approval = null,
                usage = Usage(), startedAt = now, shotVersion = it.shotVersion + 1, interrupted = null
            )
        }
        AgentForegroundService.ensureRunning(app)

        job = scope.launch {
            var st = AgentStatus.DONE
            var msg = ""
            try {
                val r = AgentLoop.run(rec.id, rec.text, rec.steps.toList())
                st = r.status
                msg = r.message
            } catch (e: CancellationException) {
                st = AgentStatus.STOPPED
                msg = stopReason ?: "Zatrzymano."
            } catch (e: Throwable) {
                st = AgentStatus.ERROR
                msg = "Nieoczekiwany błąd: ${e.message ?: e.javaClass.simpleName}"
            }
            finish(st, msg)
        }
        return null
    }

    private fun finish(st: AgentStatus, msg: String) {
        answerWait = null
        approvalWait = null
        val rec = record
        if (rec != null) {
            rec.status = st.name
            rec.message = msg
            rec.endedAt = System.currentTimeMillis()
            Store.save(rec)
        }
        update { it.copy(status = st, message = msg, stepText = "", question = null, approval = null) }
        job = null
        Notifs.clearAlert(app)
        Notifs.result(app, "Agent: ${st.label}", msg.ifBlank { rec?.text.orEmpty() })
        AgentForegroundService.requestCheck(app)
    }

    // ---------------- sterowanie ----------------

    fun pause() {
        val st = state.value.status
        if (st == AgentStatus.PLANNING || st == AgentStatus.EXECUTING) {
            pauseGate.value = true
            update { it.copy(status = AgentStatus.PAUSED, message = "Wstrzymano — bieżący krok zostanie dokończony.") }
        }
    }

    /** Wstrzymanie wywołane przez samego agenta (np. zablokowany ekran). */
    fun autoPause(reason: String) {
        pauseGate.value = true
        update { it.copy(status = AgentStatus.PAUSED, message = reason, stepText = "") }
    }

    fun resume() {
        if (state.value.status == AgentStatus.PAUSED) {
            pauseGate.value = false
            update { it.copy(status = AgentStatus.PLANNING, message = "") }
        }
    }

    suspend fun awaitUnpaused() {
        if (!pauseGate.value) return
        update { it.copy(status = AgentStatus.PAUSED) }
        pauseGate.first { !it }
        update { it.copy(status = AgentStatus.PLANNING) }
    }

    fun stop(reason: String = "Zatrzymano przez użytkownika.") {
        stopReason = reason
        val j = job
        if (j != null) {
            j.cancel()
        } else if (state.value.status.hasTask) {
            finish(AgentStatus.STOPPED, reason)
        }
    }

    // ---------------- zatwierdzenia i pytania ----------------

    suspend fun requestApproval(a: Approval): Boolean {
        val d = CompletableDeferred<Boolean>()
        approvalWait = d
        update { it.copy(status = AgentStatus.WAITING, approval = a, stepText = "Czekam na Twoje zatwierdzenie") }
        Notifs.alert(app, a.title, a.details, approval = true)
        val res = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { d.await() } ?: false
        approvalWait = null
        update { it.copy(approval = null, status = AgentStatus.EXECUTING) }
        Notifs.clearAlert(app)
        return res
    }

    fun approve(yes: Boolean) {
        approvalWait?.complete(yes)
    }

    suspend fun askUser(question: String): String? {
        val d = CompletableDeferred<String>()
        answerWait = d
        update { it.copy(status = AgentStatus.WAITING, question = question, stepText = "Agent pyta: $question") }
        Notifs.alert(app, "Agent ma pytanie", question, approval = false)
        val res = withTimeoutOrNull(ASK_TIMEOUT_MS) { d.await() }
        answerWait = null
        update { it.copy(question = null, status = AgentStatus.PLANNING) }
        Notifs.clearAlert(app)
        return res
    }

    fun answer(text: String) {
        if (text.isNotBlank()) answerWait?.complete(text.trim())
    }

    // ---------------- zapis kroków i stan ----------------

    fun addStep(step: StepRecord) {
        record?.let {
            it.steps.add(step)
            Store.save(it)
        }
        update { it.copy(steps = it.steps + step) }
    }

    fun setStatus(st: AgentStatus, text: String) {
        val paused = pauseGate.value
        update { it.copy(status = if (paused) AgentStatus.PAUSED else st, stepText = text) }
    }

    fun onCaptureLost(msg: String) {
        update {
            it.copy(captureActive = false, message = if (it.status.hasTask) msg else it.message)
        }
    }

    fun onA11yLost() {
        update { it.copy(a11yConnected = false) }
    }
}
