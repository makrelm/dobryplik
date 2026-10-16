package pl.agent.telefon.ai

import org.json.JSONException
import org.json.JSONObject
import pl.agent.telefon.core.AgentAction
import pl.agent.telefon.core.Net
import pl.agent.telefon.core.SecretStore
import pl.agent.telefon.core.Settings
import pl.agent.telefon.core.ToolSpec
import java.io.IOException

class AiException(val kind: Kind, message: String, val http: Int = 0) : Exception(message) {
    enum class Kind { NETWORK, AUTH, RATE_LIMIT, SERVER, BAD_REQUEST, PARSE }

    val retryable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.RATE_LIMIT || kind == Kind.SERVER

    fun userMessage(): String = when (kind) {
        Kind.NETWORK -> "Błąd sieci przy połączeniu z API: $message"
        Kind.AUTH -> "API odrzuciło klucz (HTTP $http). Sprawdź klucz w Ustawieniach."
        Kind.RATE_LIMIT -> "Limit zapytań lub kwota API wyczerpana (HTTP $http): $message"
        Kind.SERVER -> "Błąd po stronie dostawcy API (HTTP $http): $message"
        Kind.BAD_REQUEST -> "API odrzuciło zapytanie (HTTP $http): $message"
        Kind.PARSE -> "Nie udało się odczytać odpowiedzi modelu: $message"
    }
}

data class AiRequest(
    val system: String,
    val userText: String,
    val imageJpegB64: String?,
    val tools: List<ToolSpec>,
    val reasoning: String
)

/** action == null oznacza, że model nie zwrócił poprawnego wywołania narzędzia. */
data class AiResult(val action: AgentAction?, val inTokens: Int, val outTokens: Int, val raw: String)

interface AiProvider {
    val id: String
    val displayName: String
    val supportsImages: Boolean

    suspend fun nextAction(req: AiRequest): AiResult

    /** Tani test połączenia i klucza (bez generowania tokenów). */
    suspend fun ping(): String
}

object Providers {
    fun create(s: Settings): AiProvider? = when (s.provider) {
        "gemini" -> SecretStore.get("key_gemini").takeIf { it.isNotBlank() }
            ?.let { GeminiProvider(it, s.geminiModel.trim(), s.geminiThinking) }
        "deepseek" -> SecretStore.get("key_deepseek").takeIf { it.isNotBlank() }
            ?.let { DeepSeekProvider(it, s.deepseekModel.trim(), s.deepseekEffort) }
        else -> null
    }
}

/** Zamiana argumentów wywołania narzędzia (JSON) na [AgentAction]. */
object ActionParser {
    fun fromCall(name: String, a: JSONObject): AgentAction = AgentAction(
        tool = name,
        id = a.int("id"),
        x = a.int("x"),
        y = a.int("y"),
        x2 = a.int("x2"),
        y2 = a.int("y2"),
        durationMs = a.int("duration_ms")?.toLong(),
        text = a.str("text"),
        replace = a.optBoolean("replace", true),
        direction = a.str("direction")?.lowercase(),
        app = a.str("app"),
        seconds = a.dbl("seconds"),
        question = a.str("question"),
        summary = a.str("summary"),
        success = if (a.has("success") && !a.isNull("success")) a.optBoolean("success", true) else null,
        reason = a.optString("reason", "").take(300),
        highRisk = a.optBoolean("high_risk", false),
        riskNote = a.optString("risk_note", "").take(300)
    )

    /** Zapasowy parser, gdy model odpowie tekstem z JSON-em zamiast wywołania narzędzia. */
    fun fromText(text: String): AgentAction? {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val o = JSONObject(text.substring(s, e + 1))
            val name = o.optString("tool", o.optString("name", ""))
            if (name.isEmpty()) {
                null
            } else {
                val args = o.optJSONObject("args") ?: o.optJSONObject("arguments") ?: o
                fromCall(name, args)
            }
        } catch (ex: Exception) {
            null
        }
    }

    private fun JSONObject.int(k: String): Int? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    private fun JSONObject.dbl(k: String): Double? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.str(k: String): String? {
        if (!has(k) || isNull(k)) return null
        return opt(k)?.toString()
    }
}

internal suspend fun postJson(
    url: String,
    headers: Map<String, String>,
    body: String,
    errorMessage: (JSONObject) -> String?
): JSONObject {
    val res = try {
        Net.request("POST", url, headers + ("Content-Type" to "application/json"), body)
    } catch (e: IOException) {
        throw AiException(AiException.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
    }
    return parseResponse(res, errorMessage)
}

internal fun parseResponse(res: Net.HttpResult, errorMessage: (JSONObject) -> String?): JSONObject {
    if (res.code in 200..299) {
        return try {
            JSONObject(res.body)
        } catch (e: JSONException) {
            throw AiException(AiException.Kind.PARSE, "niepoprawny JSON w odpowiedzi")
        }
    }
    val parsed: String? = try {
        errorMessage(JSONObject(res.body))
    } catch (e: Exception) {
        null
    }
    val msg = parsed ?: res.body.take(200)
    var kind = when (res.code) {
        401, 403 -> AiException.Kind.AUTH
        429 -> AiException.Kind.RATE_LIMIT
        in 500..599 -> AiException.Kind.SERVER
        else -> AiException.Kind.BAD_REQUEST
    }
    // Gemini zwraca 400 dla niepoprawnego klucza
    if (kind == AiException.Kind.BAD_REQUEST && msg.contains("API key", ignoreCase = true) &&
        msg.contains("valid", ignoreCase = true)
    ) {
        kind = AiException.Kind.AUTH
    }
    throw AiException(kind, msg, res.code)
}
