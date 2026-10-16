package pl.agent.telefon.core

data class Limits(
    val maxSteps: Int,
    val maxMinutes: Int,
    val taskBudgetUsd: Double,
    val totalBudgetUsd: Double
)

object Guards {
    fun money(v: Double): String = "%.2f USD".format(v)

    /** Powód zatrzymania z powodu limitów albo null. Wartość limitu <= 0 oznacza „bez limitu”. */
    fun limitReason(l: Limits, stepsDone: Int, elapsedMs: Long, taskCost: Double, totalCost: Double): String? = when {
        l.maxSteps > 0 && stepsDone >= l.maxSteps ->
            "Osiągnięto limit kroków (${l.maxSteps})."
        l.maxMinutes > 0 && elapsedMs >= l.maxMinutes * 60_000L ->
            "Osiągnięto limit czasu (${l.maxMinutes} min)."
        l.taskBudgetUsd > 0 && taskCost >= l.taskBudgetUsd ->
            "Osiągnięto budżet zadania (${money(l.taskBudgetUsd)})."
        l.totalBudgetUsd > 0 && totalCost >= l.totalBudgetUsd ->
            "Osiągnięto budżet całkowity (${money(l.totalBudgetUsd)}). Zresetuj licznik w Ustawieniach, aby kontynuować."
        else -> null
    }
}

object Prices {
    fun cost(inTok: Int, outTok: Int, priceInPerM: Double, priceOutPerM: Double): Double =
        inTok * priceInPerM / 1_000_000.0 + outTok * priceOutPerM / 1_000_000.0
}

/** Wykrywa brak postępu (ekran się nie zmienia) i zapętlenia (ta sama czynność w kółko). */
class ProgressGuard(
    private val warnUnchanged: Int = 3,
    private val stopUnchanged: Int = 5,
    private val warnRepeats: Int = 3,
    private val stopRepeats: Int = 5
) {
    enum class Verdict { OK, WARN, STOP }
    data class Result(val verdict: Verdict, val message: String = "")

    private var unchanged = 0
    private val recent = ArrayDeque<String>()

    fun record(signature: String, screenAction: Boolean, changed: Boolean): Result {
        if (screenAction) unchanged = if (changed) 0 else unchanged + 1
        recent.addLast(signature)
        while (recent.size > 8) recent.removeFirst()
        val repeats = recent.count { it == signature }
        return when {
            unchanged >= stopUnchanged ->
                Result(Verdict.STOP, "Brak postępu: ekran nie zmienia się od $unchanged kolejnych czynności.")
            repeats >= stopRepeats ->
                Result(Verdict.STOP, "Wykryto zapętlenie: ta sama czynność powtórzona $repeats razy w ostatnich krokach.")
            unchanged >= warnUnchanged ->
                Result(Verdict.WARN, "Ostatnie $unchanged czynności nie zmieniły ekranu. Zmień podejście (inny element, przewinięcie, Wstecz) albo zapytaj użytkownika (ask_user).")
            repeats >= warnRepeats ->
                Result(Verdict.WARN, "Powtarzasz tę samą czynność. Spróbuj czegoś innego albo zapytaj użytkownika (ask_user).")
            else -> Result(Verdict.OK)
        }
    }
}
