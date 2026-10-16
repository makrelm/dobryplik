package pl.agent.telefon.ai

import org.json.JSONArray
import org.json.JSONObject
import pl.agent.telefon.core.Net
import pl.agent.telefon.core.ToolSpec
import pl.agent.telefon.core.Tools
import java.io.IOException

/**
 * Google Gemini API (generateContent). Natywny format: functionDeclarations + functionCallingConfig(ANY),
 * obraz jako inlineData, poziom rozumowania w generationConfig.thinkingConfig.thinkingLevel (modele Gemini 3).
 */
class GeminiProvider(
    private val apiKey: String,
    model: String,
    private val thinking: String
) : AiProvider {
    override val id = "gemini"
    override val displayName = "Gemini"
    override val supportsImages = true

    private val model = model.removePrefix("models/")
    private val base = "https://generativelanguage.googleapis.com/v1beta/models"
    private val headers: Map<String, String> get() = mapOf("x-goog-api-key" to apiKey)

    override suspend fun nextAction(req: AiRequest): AiResult {
        val parts = JSONArray()
        if (req.imageJpegB64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", req.imageJpegB64)
                )
            )
        }
        parts.put(JSONObject().put("text", req.userText))

        // Dla Gemini 3 zalecana jest domyślna temperatura — nie ustawiamy jej.
        val gen = JSONObject().put("maxOutputTokens", 8192)
        if (model.startsWith("gemini-3")) {
            gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking.ifBlank { "low" }))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations(req.tools))))
            .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "ANY")))
            .put("generationConfig", gen)

        val json = postJson("$base/$model:generateContent", headers, body.toString()) {
            it.optJSONObject("error")?.optString("message")
        }
        return parse(json)
    }

    private fun declarations(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val props = JSONObject()
            val required = JSONArray()
            for (p in t.params + Tools.common) {
                val o = JSONObject().put("type", p.type.uppercase()).put("description", p.description)
                p.options?.let { o.put("enum", JSONArray(it)) }
                props.put(p.name, o)
                if (p.required) required.put(p.name)
            }
            val params = JSONObject().put("type", "OBJECT").put("properties", props)
            if (required.length() > 0) params.put("required", required)
            arr.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", params))
        }
        return arr
    }

    private fun parse(root: JSONObject): AiResult {
        val usage = root.optJSONObject("usageMetadata")
        val inTok = usage?.optInt("promptTokenCount", 0) ?: 0
        // tokeny „myślenia” są rozliczane jak wyjściowe
        val outTok = (usage?.optInt("candidatesTokenCount", 0) ?: 0) + (usage?.optInt("thoughtsTokenCount", 0) ?: 0)

        val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (block.isNotEmpty()) {
            throw AiException(AiException.Kind.BAD_REQUEST, "Zapytanie zablokowane przez filtr dostawcy ($block).", 200)
        }
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        var text = ""
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    return AiResult(ActionParser.fromCall(fc.optString("name"), args), inTok, outTok, fc.toString())
                }
                if (!p.optBoolean("thought", false)) text += p.optString("text")
            }
        }
        val fallback = if (text.isNotBlank()) ActionParser.fromText(text) else null
        val raw = text.ifBlank { cand?.optString("finishReason").orEmpty() }
        return AiResult(fallback, inTok, outTok, raw)
    }

    override suspend fun ping(): String {
        val res = try {
            Net.request("GET", "$base/$model", headers, null, 30_000)
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, e.message ?: "brak sieci")
        }
        val json = parseResponse(res) { it.optJSONObject("error")?.optString("message") }
        val limit = json.optInt("inputTokenLimit", 0)
        val tail = if (limit > 0) " (limit wejścia: $limit tokenów)." else "."
        return "Połączenie OK: ${json.optString("name", model)}$tail"
    }
}
