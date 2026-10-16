package pl.agent.telefon.core

import kotlin.math.max

enum class AgentStatus(val label: String) {
    IDLE("Bezczynny"),
    PLANNING("Planowanie"),
    EXECUTING("Wykonywanie"),
    WAITING("Oczekiwanie"),
    PAUSED("Pauza"),
    ERROR("Błąd"),
    DONE("Ukończono"),
    STOPPED("Zatrzymano");

    /** Zadanie istnieje i nie jest zakończone (także gdy jest wstrzymane). */
    val hasTask: Boolean
        get() = this == PLANNING || this == EXECUTING || this == WAITING || this == PAUSED
}

/** Jedno polecenie zwrócone przez model. Współrzędne x/y są w siatce 0-1000. */
data class AgentAction(
    val tool: String,
    val id: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val replace: Boolean = true,
    val direction: String? = null,
    val app: String? = null,
    val seconds: Double? = null,
    val question: String? = null,
    val summary: String? = null,
    val success: Boolean? = null,
    val reason: String = "",
    val highRisk: Boolean = false,
    val riskNote: String = ""
) {
    /** Czy czynność ma zmieniać wygląd ekranu (służy do weryfikacji skutku). */
    val isScreenAction: Boolean get() = tool in SCREEN_TOOLS

    fun signature(): String =
        listOf(tool, id, x, y, x2, y2, direction, app, text?.take(30)).joinToString("|")

    companion object {
        val SCREEN_TOOLS = setOf(
            "tap", "long_press", "swipe", "scroll", "input_text", "press_back", "press_home", "open_app"
        )
        val ALL_TOOLS = setOf(
            "get_screen", "get_ui_tree", "tap", "long_press", "swipe", "scroll", "input_text",
            "press_back", "press_home", "open_app", "wait", "ask_user", "finish_task"
        )
    }
}

data class ActionOutcome(val ok: Boolean, val note: String)

data class UiNode(
    val id: Int,
    val cls: String,
    val text: String,
    val desc: String,
    val rid: String,
    val l: Int,
    val t: Int,
    val r: Int,
    val b: Int,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val password: Boolean
) {
    val area: Long get() = (r - l).toLong() * (b - t).toLong()

    /** Tekst opisujący element (do heurystyk bezpieczeństwa i opisów w UI). */
    fun label(): String =
        listOf(if (password) "" else text, desc, rid.replace('_', ' '))
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

data class UiSnapshot(
    val pkg: String,
    val w: Int,
    val h: Int,
    val nodes: List<UiNode>,
    val truncated: Boolean
) {
    /** Odcisk zawartości ekranu — do wykrywania, czy czynność coś zmieniła. */
    val fingerprint: Int by lazy {
        var acc = pkg.hashCode()
        for (n in nodes) {
            acc = 31 * acc + n.text.hashCode()
            acc = 31 * acc + n.desc.hashCode()
            acc = 31 * acc + n.l
            acc = 31 * acc + n.t
            acc = 31 * acc + (if (n.checked) 1 else 0)
        }
        acc
    }

    /** Najmniejszy element zawierający punkt (siatka 0-1000). */
    fun nodeAt(xn: Int, yn: Int): UiNode? {
        val px = xn * max(w, 1) / 1000
        val py = yn * max(h, 1) / 1000
        return nodes.filter { px in it.l..it.r && py in it.t..it.b }.minByOrNull { it.area }
    }

    /** Zwarty opis ekranu dla modelu. Ramki przeliczone na siatkę 0-1000. */
    fun toPrompt(): String {
        val ww = max(w, 1)
        val hh = max(h, 1)
        val sb = StringBuilder()
        for (n in nodes) {
            sb.append('[').append(n.id).append("] ").append(n.cls)
            if (n.text.isNotEmpty()) {
                val shown = if (n.password) "<hasło ukryte>" else n.text.take(80).replace("\n", " ")
                sb.append(" \"").append(shown).append('"')
            }
            if (n.desc.isNotEmpty()) sb.append(" desc=\"").append(n.desc.take(60).replace("\n", " ")).append('"')
            if (n.rid.isNotEmpty()) sb.append(" rid=").append(n.rid)
            sb.append(" (").append(n.l * 1000 / ww).append(',').append(n.t * 1000 / hh).append(',')
                .append(n.r * 1000 / ww).append(',').append(n.b * 1000 / hh).append(')')
            val f = ArrayList<String>()
            if (n.clickable) f.add("click")
            if (n.longClickable) f.add("long")
            if (n.editable) f.add("edit")
            if (n.scrollable) f.add("scroll")
            if (n.checkable) f.add(if (n.checked) "checked" else "unchecked")
            if (!n.enabled) f.add("disabled")
            if (n.focused) f.add("focused")
            if (n.password) f.add("password")
            if (f.isNotEmpty()) sb.append(' ').append(f.joinToString(","))
            sb.append('\n')
        }
        if (truncated) sb.append("(lista skrócona — poproś o get_ui_tree, jeśli brakuje elementu)\n")
        return sb.toString()
    }
}

data class StepRecord(
    val index: Int,
    val tool: String,
    val description: String,
    val reason: String,
    val result: String,
    val ok: Boolean,
    val shotPath: String?,
    val timeMs: Long
)

data class Usage(
    val calls: Int = 0,
    val inTokens: Long = 0,
    val outTokens: Long = 0,
    val costUsd: Double = 0.0
) {
    fun plus(inTok: Int, outTok: Int, cost: Double) =
        Usage(calls + 1, inTokens + inTok, outTokens + outTok, costUsd + cost)
}

data class Approval(val title: String, val details: String)

/** Stan widoczny dla interfejsu, nakładki i powiadomienia. */
data class RuntimeState(
    val status: AgentStatus = AgentStatus.IDLE,
    val taskId: String = "",
    val taskText: String = "",
    val stepText: String = "",
    val message: String = "",
    val steps: List<StepRecord> = emptyList(),
    val question: String? = null,
    val approval: Approval? = null,
    val usage: Usage = Usage(),
    val startedAt: Long = 0L,
    val shotVersion: Int = 0,
    val captureActive: Boolean = false,
    val a11yConnected: Boolean = false,
    val online: Boolean = true,
    val interrupted: TaskRecord? = null
)
