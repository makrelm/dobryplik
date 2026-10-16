package pl.agent.telefon.core

/** Lokalne zabezpieczenia: lista zablokowanych aplikacji, walidacja poleceń modelu, heurystyka ryzyka. */
object Safety {
    const val OWN_PACKAGE = "pl.agent.telefon"

    val DEFAULT_BLOCKLIST_TEXT: String = """
# Aplikacje, w których agent nie działa (jedna nazwa pakietu w linii, # = komentarz).
# Dla tych aplikacji nie odczytuję ekranu, nie robię zrzutów i nic nie wysyłam do API.
pl.agent.telefon
com.android.vending
com.google.android.apps.walletnfc
com.android.settings
com.android.packageinstaller
com.google.android.packageinstaller
pl.pkobp.iko
pl.mbank
pl.ing.mojeing
com.revolut.revolut
com.paypal.android.p2pmobile
""".trimIndent()

    fun parseBlocklist(text: String): Set<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    fun isBlocked(pkg: String?, blocklist: Set<String>): Boolean = pkg != null && pkg in blocklist

    private val RISKY_TOKENS = setOf(
        "wyślij", "wyslij", "send", "opublikuj", "publish", "post", "tweet",
        "zapłać", "zaplac", "zapłata", "pay", "payment", "buy", "kup", "kupuję", "kupuje", "purchase",
        "zamów", "zamow", "zamawiam", "order", "checkout", "subscribe", "subskrybuj",
        "przelej", "przelew", "transfer", "wypłać", "wyplac", "withdraw",
        "usuń", "usun", "delete", "erase", "wymaż", "wymaz", "odinstaluj", "uninstall",
        "wyloguj", "logout", "zresetuj", "reset"
    )

    private val RISKY_PHRASES = listOf(
        "sign out", "log out", "place order", "złóż zamówienie", "kup teraz", "buy now", "pay now",
        "confirm payment", "potwierdź płatność", "usuń konto", "delete account", "factory reset",
        "przywróć ustawienia", "wyczyść dane", "clear data", "install", "zainstaluj"
    )

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Heurystyka: czy etykieta elementu wygląda na czynność nieodwracalną lub widoczną na zewnątrz. */
    fun looksRisky(label: String): Boolean {
        val s = label.trim().lowercase()
        if (s.isEmpty() || s.length > 80) return false
        if (RISKY_PHRASES.any { s.contains(it) }) return true
        return s.split(SPLIT).any { it in RISKY_TOKENS }
    }

    /** Etykieta elementu, którego dotyczy czynność (puste, gdy nie da się ustalić). */
    fun targetLabel(a: AgentAction, snap: UiSnapshot): String {
        val n = when {
            a.id != null -> snap.nodes.getOrNull(a.id)
            a.x != null && a.y != null -> snap.nodeAt(a.x, a.y)
            else -> null
        }
        return n?.label().orEmpty()
    }

    /** Zwraca komunikat błędu albo null, gdy polecenie jest poprawne. */
    fun validate(a: AgentAction, nodeCount: Int, blocked: Boolean): String? {
        if (a.tool !in AgentAction.ALL_TOOLS) return "Nieznane narzędzie: ${a.tool}"
        if (blocked && a.tool in setOf("tap", "long_press", "swipe", "scroll", "input_text")) {
            return "Aplikacja na pierwszym planie jest zablokowana przez właściciela — opuść ją (press_back / press_home)."
        }
        fun grid(v: Int?) = v != null && v in 0..1000
        return when (a.tool) {
            "tap", "long_press" -> when {
                a.id != null ->
                    if (a.id < 0 || a.id >= nodeCount) "Nieprawidłowe id elementu: ${a.id} (dostępne 0..${nodeCount - 1})" else null
                grid(a.x) && grid(a.y) -> null
                else -> "Podaj id elementu albo współrzędne x,y w siatce 0-1000."
            }
            "swipe" ->
                if (grid(a.x) && grid(a.y) && grid(a.x2) && grid(a.y2)) null
                else "swipe wymaga x, y, x2, y2 w siatce 0-1000."
            "scroll" ->
                if (a.direction in setOf("up", "down", "left", "right")) null
                else "scroll wymaga direction: up, down, left lub right."
            "input_text" -> when {
                a.text == null -> "input_text wymaga pola text."
                a.text.length > 2000 -> "Tekst jest za długi (limit 2000 znaków)."
                a.text.isEmpty() && !a.replace -> "Pusty tekst ma sens tylko z replace=true."
                a.id != null && (a.id < 0 || a.id >= nodeCount) -> "Nieprawidłowe id pola: ${a.id}"
                else -> null
            }
            "open_app" -> if (a.app.isNullOrBlank()) "open_app wymaga nazwy aplikacji." else null
            "wait" -> if (a.seconds == null) "wait wymaga seconds." else null
            "ask_user" -> if (a.question.isNullOrBlank()) "ask_user wymaga pytania." else null
            else -> null
        }
    }
}

/** Opisy czynności po polsku (lista kroków, zatwierdzenia, powiadomienia). */
object Describe {
    private fun dir(d: String?): String = when (d) {
        "up" -> "w górę"
        "down" -> "w dół"
        "left" -> "w lewo"
        "right" -> "w prawo"
        else -> d.orEmpty()
    }

    fun target(a: AgentAction, snap: UiSnapshot?): String {
        if (a.id != null) {
            val label = snap?.nodes?.getOrNull(a.id)?.label().orEmpty().take(40)
            return if (label.isNotEmpty()) "element #${a.id} „$label”" else "element #${a.id}"
        }
        return "punkt (${a.x}, ${a.y})"
    }

    fun action(a: AgentAction, snap: UiSnapshot?): String = when (a.tool) {
        "get_screen" -> "Pobierz zrzut ekranu"
        "get_ui_tree" -> "Pobierz pełną listę elementów ekranu"
        "tap" -> "Kliknij ${target(a, snap)}"
        "long_press" -> "Przytrzymaj ${target(a, snap)}"
        "swipe" -> "Przesuń palcem (${a.x}, ${a.y}) → (${a.x2}, ${a.y2})"
        "scroll" -> "Przewiń ${dir(a.direction)}"
        "input_text" -> "Wpisz tekst „${a.text.orEmpty().take(40)}”" + (if (a.id != null) " w ${target(a, snap)}" else "")
        "press_back" -> "Wstecz"
        "press_home" -> "Ekran główny"
        "open_app" -> "Uruchom aplikację: ${a.app}"
        "wait" -> "Czekaj ${a.seconds} s"
        "ask_user" -> "Zapytaj użytkownika: ${a.question.orEmpty().take(80)}"
        "finish_task" -> "Zakończ zadanie"
        else -> a.tool
    }
}
