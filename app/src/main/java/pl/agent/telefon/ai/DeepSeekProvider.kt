package pl.agent.telefon.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.agent.telefon.core.Net
import pl.agent.telefon.core.ToolSpec
import pl.agent.telefon.core.Tools
import java.io.IOException

/**
 * DeepSeek API (format zgodny z chat/completions). Osobna integracja:
 * narzędzia jako tools[type=function], obraz jako image_url (data URL) tylko w wiadomości użytkownika,
 * rozumowanie przez thinking{type} + reasoning_effort. W trybie myślenia tool_choice="required" nie jest
 * obsługiwane, dlatego używamy "auto" i zapasowego parsera JSON; bez myślenia wymuszamy "required".
 */
class DeepSeekProvider(
    private val apiKey: String,
    private val model: String,
    private val effort: String
) : AiProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val supportsImages = true

    private val base = "https://api.deepseek.com"
    private val headers: Map<String, String> get() = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun nextAction(req: AiRequest): AiResult {
        val userContent: Any = if (req.imageJpegB64 != null) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", req.userText))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,${req.imageJpegB64}")
                    )
                )
        } else {
            req.userText
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", req.system))
            .put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", toolsJson(req.tools))
            .put("max_tokens", 16384)
        if (effort != "none") {
            body.put("thinking", JSONObject().put("type", "enabled"))
            body.put("reasoning_effort", effort.ifBlank { "low" })
            body.put("tool_choice", "auto")
        } else {
            body.put("thinking", JSONObject().put("type", "disabled"))
            body.put("tool_choice", "required")
            body.put("temperature", 0.2)
        }

        val json = postJson("$base/chat/completions", headers, body.toString()) {
            it.optJSONObject("error")?.optString("message")
        }
        return parse(json)
    }

    private fun toolsJson(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (p in t.params + Tools.common) {
                val o = JSONObject().put("type", p.type).put("description", p.description)
                p.options?.let { o.put("enum", JSONArray(it)) }
                props.put(p.name, o)
                if (p.required) required.put(p.name)
            }
            val params = JSONObject().put("type", "object").put("properties", props).put("required", required)
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", t.name).put("description", t.description).put("parameters", params)
                )
            )
        }
        return arr
    }

    private fun parse(root: JSONObject): AiResult {
        val u = root.optJSONObject("usage")
        val inTok = u?.optInt("prompt_tokens", 0) ?: 0
        val outTok = u?.optInt("completion_tokens", 0) ?: 0
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")

        val calls = msg?.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val fn = calls.optJSONObject(0)?.optJSONObject("function")
            if (fn != null) {
                val argText = fn.optString("arguments", "{}")
                val args = try {
                    JSONObject(if (argText.isBlank()) "{}" else argText)
                } catch (e: JSONException) {
                    null
                }
                val action = if (args != null) ActionParser.fromCall(fn.optString("name"), args) else null
                return AiResult(action, inTok, outTok, argText)
            }
        }
        val content = msg?.optString("content").orEmpty()
        val fallback = if (content.isNotBlank()) ActionParser.fromText(content) else null
        return AiResult(fallback, inTok, outTok, content)
    }

    override suspend fun ping(): String {
        val res = try {
            Net.request("GET", "$base/models", headers, null, 30_000)
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "brak sieci")
        }
        val json = parseResponse(res) { it.optJSONObject("error")?.optString("message") }
        val data = json.optJSONArray("data")
        var found = false
        if (data != null) {
            for (i in 0 until data.length()) {
                if (data.optJSONObject(i)?.optString("id") == model) found = true
            }
        }
        return if (found) {
            "Połączenie OK: model $model jest dostępny."
        } else {
            "Klucz działa, ale nie widzę modelu „$model” na liście dostawcy — sprawdź nazwę modelu."
        }
    }
}
