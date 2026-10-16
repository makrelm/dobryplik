package pl.agent.telefon.core

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import pl.agent.telefon.ai.AiException
import pl.agent.telefon.ai.AiProvider
import pl.agent.telefon.ai.AiRequest
import pl.agent.telefon.ai.AiResult
import pl.agent.telefon.ai.Providers
import pl.agent.telefon.device.AgentAccessibilityService
import pl.agent.telefon.device.Capture

data class LoopResult(val status: AgentStatus, val message: String)

/**
 * Pętla agenta: odczyt ekranu -> model -> walidacja i zatwierdzenie -> pojedyncza czynność ->
 * odczekanie na stabilny interfejs -> ponowny odczyt i weryfikacja -> kolejny krok.
 */
object AgentLoop {
    private const val HISTORY = 10

    private fun step(idx: Int, a: AgentAction, desc: String, result: String, ok: Boolean, shot: String?) =
        StepRecord(idx, a.tool, desc, a.reason, result, ok, shot, System.currentTimeMillis())

    suspend fun run(taskId: String, taskText: String, prior: List<StepRecord>): LoopResult {
        val rt = AgentRuntime
        val ctx = rt.app
        val cfg = Prefs.load()
        val provider = Providers.create(cfg)
            ?: return LoopResult(AgentStatus.ERROR, "Brak klucza API dla wybranego dostawcy.")
        val blocklist = Safety.parseBlocklist(cfg.blocklist)
        val limits = Limits(cfg.maxSteps, cfg.maxMinutes, cfg.taskBudgetUsd, cfg.totalBudgetUsd)
        val guard = ProgressGuard()
        val priceIn = if (cfg.provider == "gemini") cfg.geminiPriceIn else cfg.deepseekPriceIn
        val priceOut = if (cfg.provider == "gemini") cfg.geminiPriceOut else cfg.deepseekPriceOut
        val reasoning = if (cfg.provider == "gemini") cfg.geminiThinking else cfg.deepseekEffort
        val imagesAllowed = provider.supportsImages && cfg.imageMode != "never"

        val steps = prior.toMutableList()
        val startIndex = steps.size
        val started = SystemClock.elapsedRealtime()
        var taskCost = 0.0
        var note = if (prior.isNotEmpty()) {
            "Zadanie wznowione po przerwaniu; stan ekranu mógł się zmienić — zweryfikuj ekran przed działaniem."
        } else ""
        var answer: String? = null
        var forceImage = false
        var imageSentOnce = false
        var fullTree = false
        var carry: UiSnapshot? = null
        var obsStreak = 0
        var badReplies = 0
        var invalid = 0
        var rejections = 0
        var blind = 0
        var lastChanged = true
        var firstLoop = true

        while (true) {
            currentCoroutineContext().ensureActive()
            rt.awaitUnpaused()

            // 1) limity kroków, czasu i kosztów
            val limit = Guards.limitReason(
                limits, steps.size - startIndex, SystemClock.elapsedRealtime() - started, taskCost, Prefs.totalCost()
            )
            if (limit != null) return LoopResult(AgentStatus.STOPPED, limit)

            // 2) warunki pracy: ekran, sieć, usługa dostępności
            if (screenUnavailable(ctx)) {
                rt.autoPause("Ekran jest wyłączony lub zablokowany. Odblokuj telefon i wznów zadanie — nie omijam blokady.")
                carry = null
                continue
            }
            if (!NetMonitor.online) {
                rt.setStatus(AgentStatus.WAITING, "Brak sieci — czekam na połączenie…")
                val back = withTimeoutOrNull(120_000L) {
                    while (!NetMonitor.online) delay(1000)
                    true
                }
                if (back == null) {
                    rt.autoPause("Brak połączenia z internetem. Wznów zadanie, gdy sieć wróci.")
                    continue
                }
            }
            val svc = waitForService()
            if (svc == null) {
                rt.autoPause("Usługa dostępności jest niepodłączona. Włącz ją w zakładce Diagnostyka i wznów zadanie.")
                carry = null
                continue
            }

            // 3) odczyt ekranu
            var snap = carry ?: readSnap(svc, if (fullTree) 400 else 120)
            carry = null
            fullTree = false

            if (firstLoop) {
                firstLoop = false
                if (snap.pkg == ctx.packageName) {
                    // Start z ekranu aplikacji agenta: najpierw Ekran główny (lokalnie, bez wywołania modelu).
                    svc.home()
                    svc.waitForIdle()
                    val rec = StepRecord(
                        steps.size + 1, "press_home", "Wyjście z ekranu aplikacji agenta (Ekran główny)",
                        "Start zadania", "OK", true, null, System.currentTimeMillis()
                    )
                    steps.add(rec)
                    rt.addStep(rec)
                    snap = readSnap(svc, 120)
                }
            }

            val blocked = Safety.isBlocked(snap.pkg, blocklist)

            // 4) obraz: tylko gdy potrzebny (oszczędność kosztów) lub do podglądu (bez wysyłania)
            val sparse = snap.nodes.size < 3
            val wantImage = !blocked && imagesAllowed &&
                (cfg.imageMode == "always" || forceImage || sparse || !lastChanged || !imageSentOnce)
            forceImage = false
            var imageB64: String? = null
            var shotPath: String? = null
            if (blocked) {
                rt.lastShot = null
                rt.update { it.copy(shotVersion = it.shotVersion + 1) }
            } else if (wantImage || cfg.livePreview) {
                val jpeg = Capture.grabJpeg(svc, cfg.imageMaxSide)
                if (jpeg != null) {
                    rt.lastShot = jpeg
                    rt.update { it.copy(shotVersion = it.shotVersion + 1) }
                    if (cfg.saveShots) shotPath = Store.saveShot(taskId, steps.size + 1, jpeg)
                    if (wantImage) {
                        imageB64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        imageSentOnce = true
                    }
                }
            }
            if (wantImage && imageB64 == null) {
                note += " Zrzut ekranu jest chwilowo niedostępny (brak przechwytywania) — korzystaj z listy elementów."
            }

            // brak jakiegokolwiek widoku ekranu -> poczekaj, a potem wstrzymaj
            if (!blocked && snap.nodes.isEmpty() && imageB64 == null) {
                blind++
                if (blind >= 3) {
                    blind = 0
                    rt.autoPause("Nie mogę odczytać ekranu: brak listy elementów i brak zrzutu. Włącz przechwytywanie ekranu (Diagnostyka) lub otwórz właściwą aplikację i wznów.")
                    continue
                }
                rt.setStatus(AgentStatus.WAITING, "Ekran jest pusty lub się ładuje — próbuję ponownie…")
                delay(1500)
                continue
            }
            blind = 0

            // 5) zapytanie do modelu (z kontrolowanym ponawianiem)
            val userText = Prompt.user(taskText, answer, note, steps.takeLast(HISTORY), snap, blocked, imageB64 != null, imagesAllowed)
            note = ""
            answer = null
            val how = if (imageB64 != null) "lista elementów + obraz" else "lista elementów"
            rt.setStatus(AgentStatus.PLANNING, "Pytam model ${provider.displayName} ($how)…")
            val reply: AiResult = try {
                callModel(provider, AiRequest(Prompt.SYSTEM, userText, imageB64, Tools.all, reasoning))
            } catch (e: AiException) {
                return LoopResult(AgentStatus.ERROR, e.userMessage())
            }

            // 6) rzeczywiste zużycie i koszt
            val cost = Prices.cost(reply.inTokens, reply.outTokens, priceIn, priceOut)
            taskCost += cost
            Prefs.addUsage(cfg.provider, reply.inTokens, reply.outTokens, cost)
            rt.update { it.copy(usage = it.usage.plus(reply.inTokens, reply.outTokens, cost)) }

            val action = reply.action
            if (action == null) {
                badReplies++
                if (badReplies > 2) {
                    return LoopResult(AgentStatus.ERROR, "Model nie zwrócił poprawnego polecenia (3 próby).")
                }
                note = "Twoja poprzednia odpowiedź nie zawierała poprawnego wywołania narzędzia. Wywołaj dokładnie jedno narzędzie."
                carry = snap
                continue
            }
            badReplies = 0

            // po wywołaniu: jeśli budżet wyczerpany, nie wykonujemy już czynności (poza zakończeniem)
            val overBudget = Guards.limitReason(limits, 0, 0L, taskCost, Prefs.totalCost())
            if (overBudget != null && action.tool != "finish_task") {
                return LoopResult(AgentStatus.STOPPED, overBudget)
            }

            // 7) walidacja lokalna
            val desc = Describe.action(action, snap)
            val err = Safety.validate(action, snap.nodes.size, blocked)
            if (err != null) {
                val rec = step(steps.size + 1, action, desc, "Odrzucone lokalnie: $err", false, shotPath)
                steps.add(rec)
                rt.addStep(rec)
                invalid++
                if (invalid >= 4) {
                    return LoopResult(AgentStatus.ERROR, "Model wielokrotnie zwracał niepoprawne polecenia. Ostatni błąd: $err")
                }
                note = "Polecenie odrzucone przez aplikację: $err"
                carry = snap
                continue
            }
            invalid = 0

            // 8) narzędzia obserwacji
            if (action.tool == "get_screen" || action.tool == "get_ui_tree") {
                obsStreak++
                val result = if (action.tool == "get_screen") {
                    forceImage = true
                    carry = snap
                    if (imagesAllowed) "Zrzut zostanie dołączony w następnym kroku." else "Obrazy są wyłączone lub niewspierane — korzystaj z listy elementów."
                } else {
                    fullTree = true
                    "Pełna lista elementów w następnym kroku."
                }
                val rec = step(steps.size + 1, action, desc, result, true, shotPath)
                steps.add(rec)
                rt.addStep(rec)
                if (obsStreak >= 5) {
                    return LoopResult(AgentStatus.STOPPED, "Agent nie potrafi zinterpretować ekranu (wielokrotne prośby o obraz bez działania).")
                }
                if (obsStreak >= 3) {
                    note = "Wywołałeś narzędzia obserwacji $obsStreak razy z rzędu. Podejmij działanie albo zapytaj użytkownika (ask_user)."
                }
                continue
            }
            obsStreak = 0

            // 9) pytanie do użytkownika
            if (action.tool == "ask_user") {
                val a = rt.askUser(action.question.orEmpty())
                    ?: return LoopResult(AgentStatus.STOPPED, "Brak odpowiedzi na pytanie agenta w wyznaczonym czasie.")
                val rec = step(steps.size + 1, action, desc, "Odpowiedź użytkownika: ${a.take(200)}", true, shotPath)
                steps.add(rec)
                rt.addStep(rec)
                answer = a
                continue
            }

            // 10) zakończenie
            if (action.tool == "finish_task") {
                val ok = action.success != false
                val summary = action.summary.orEmpty().ifBlank { if (ok) "Zadanie zakończone." else "Zadanie nie powiodło się." }
                val rec = step(steps.size + 1, action, desc, summary, ok, shotPath)
                steps.add(rec)
                rt.addStep(rec)
                return if (ok) {
                    LoopResult(AgentStatus.DONE, summary)
                } else {
                    LoopResult(AgentStatus.ERROR, "Agent zgłasza niepowodzenie: $summary")
                }
            }

            // 11) zatwierdzenie czynności ryzykownych (model oznacza ryzyko + lokalna heurystyka)
            val label = Safety.targetLabel(action, snap)
            val risky = action.highRisk || Safety.looksRisky(label) ||
                (action.tool == "open_app" && Safety.looksRisky(action.app.orEmpty()))
            if (risky) {
                val details = buildString {
                    append(desc)
                    if (action.reason.isNotBlank()) append("\nPowód: ").append(action.reason)
                    if (action.riskNote.isNotBlank()) append("\nRyzyko: ").append(action.riskNote)
                    if (label.isNotBlank()) append("\nElement: ").append(label.take(80))
                }
                val approved = rt.requestApproval(Approval("Zatwierdzić czynność?", details))
                if (!approved) {
                    val rec = step(steps.size + 1, action, desc, "Użytkownik ODRZUCIŁ czynność (lub nie odpowiedział).", false, shotPath)
                    steps.add(rec)
                    rt.addStep(rec)
                    rejections++
                    if (rejections >= 3) {
                        return LoopResult(AgentStatus.STOPPED, "Odrzucono kolejne czynności — zadanie zatrzymane.")
                    }
                    note = "Użytkownik ODRZUCIŁ czynność: $desc. Nie powtarzaj jej; wybierz inną drogę albo zakończ zadanie (finish_task)."
                    carry = snap
                    continue
                }
            }

            // 12) wykonanie pojedynczej czynności + weryfikacja skutku
            rt.setStatus(AgentStatus.EXECUTING, desc)
            val before = snap.fingerprint
            val out = try {
                Executor.run(svc, action, snap, blocklist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ActionOutcome(false, "Wyjątek podczas wykonania: ${e.message ?: e.javaClass.simpleName}")
            }
            svc.waitForIdle()
            val after = readSnap(svc, 120)
            val changed = after.fingerprint != before
            val screenNote = if (action.isScreenAction) (if (changed) "ekran zmieniony" else "ekran bez zmian") else ""
            val result = listOf(out.note, screenNote).filter { it.isNotBlank() }.joinToString("; ")
            val rec = step(steps.size + 1, action, desc, result, out.ok, shotPath)
            steps.add(rec)
            rt.addStep(rec)
            lastChanged = changed || !action.isScreenAction
            if (!out.ok || (action.isScreenAction && !changed)) forceImage = true
            carry = after

            // 13) brak postępu / zapętlenie
            val g = guard.record(action.signature(), action.isScreenAction, changed)
            when (g.verdict) {
                ProgressGuard.Verdict.STOP -> return LoopResult(AgentStatus.STOPPED, g.message)
                ProgressGuard.Verdict.WARN -> {
                    note += " " + g.message
                    forceImage = true
                }
                ProgressGuard.Verdict.OK -> {}
            }
        }
    }

    private suspend fun callModel(p: AiProvider, req: AiRequest): AiResult {
        val waits = longArrayOf(2_000, 6_000, 15_000)
        var attempt = 0
        while (true) {
            try {
                return p.nextAction(req)
            } catch (e: AiException) {
                if (!e.retryable || attempt >= waits.size) throw e
                val wait = waits[attempt]
                attempt++
                AgentRuntime.setStatus(
                    AgentStatus.WAITING,
                    "Błąd API (${e.message.orEmpty().take(80)}) — ponawiam $attempt/${waits.size} za ${wait / 1000} s"
                )
                delay(wait)
                if (!NetMonitor.online) {
                    withTimeoutOrNull(120_000L) {
                        while (!NetMonitor.online) delay(1000)
                        true
                    }
                }
                AgentRuntime.setStatus(AgentStatus.PLANNING, "Ponawiam zapytanie do modelu…")
            }
        }
    }

    private fun screenUnavailable(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        return (pm != null && !pm.isInteractive) || (km != null && km.isKeyguardLocked)
    }

    private suspend fun waitForService(): AgentAccessibilityService? {
        var waited = 0
        while (AgentAccessibilityService.instance == null && waited < 20) {
            delay(1000)
            waited++
        }
        return AgentAccessibilityService.instance
    }

    private suspend fun readSnap(svc: AgentAccessibilityService, maxNodes: Int): UiSnapshot {
        repeat(3) {
            val s = svc.readSnapshot(maxNodes)
            if (s != null) return s
            delay(500)
        }
        return svc.emptySnapshot()
    }
}

/** Mapowanie poleceń modelu na wywołania usługi dostępności (współrzędne 0-1000 -> piksele). */
object Executor {
    private fun px(norm: Int, size: Int): Int = (norm.coerceIn(0, 1000).toLong() * size / 1000).toInt()

    suspend fun run(
        svc: AgentAccessibilityService,
        a: AgentAction,
        snap: UiSnapshot,
        blocklist: Set<String>
    ): ActionOutcome = when (a.tool) {
        "tap" -> tapLike(svc, a, snap, false)
        "long_press" -> tapLike(svc, a, snap, true)
        "swipe" -> {
            val ok = svc.swipe(
                px(a.x ?: 0, snap.w), px(a.y ?: 0, snap.h), px(a.x2 ?: 0, snap.w), px(a.y2 ?: 0, snap.h),
                a.durationMs ?: 350L
            )
            ActionOutcome(ok, if (ok) "Przesunięcie wykonane" else "Gest przesunięcia nie powiódł się")
        }
        "scroll" -> {
            val (x1, y1, x2, y2) = when (a.direction) {
                "down" -> listOf(500, 750, 500, 250)
                "up" -> listOf(500, 250, 500, 750)
                "right" -> listOf(800, 500, 200, 500)
                else -> listOf(200, 500, 800, 500)
            }
            val ok = svc.swipe(px(x1, snap.w), px(y1, snap.h), px(x2, snap.w), px(y2, snap.h), 400L)
            ActionOutcome(ok, if (ok) "Przewinięto" else "Gest przewijania nie powiódł się")
        }
        "input_text" -> svc.setText(a.id, a.text.orEmpty(), a.replace)
        "press_back" -> {
            val ok = svc.back()
            ActionOutcome(ok, if (ok) "Wciśnięto Wstecz" else "System odrzucił Wstecz")
        }
        "press_home" -> {
            val ok = svc.home()
            ActionOutcome(ok, if (ok) "Przejście do ekranu głównego" else "System odrzucił Home")
        }
        "open_app" -> svc.openApp(a.app.orEmpty(), blocklist)
        "wait" -> {
            val s = (a.seconds ?: 1.0).coerceIn(0.2, 30.0)
            delay((s * 1000).toLong())
            ActionOutcome(true, "Odczekano $s s")
        }
        else -> ActionOutcome(false, "Nieobsługiwane narzędzie: ${a.tool}")
    }

    private suspend fun tapLike(
        svc: AgentAccessibilityService,
        a: AgentAction,
        snap: UiSnapshot,
        long: Boolean
    ): ActionOutcome {
        if (a.id != null) return svc.tapNode(a.id, long)
        val ok = svc.tapXY(px(a.x ?: 0, snap.w), px(a.y ?: 0, snap.h), long)
        return ActionOutcome(ok, if (ok) "Gest dotknięcia wykonany" else "Gest dotknięcia nie powiódł się")
    }
}
