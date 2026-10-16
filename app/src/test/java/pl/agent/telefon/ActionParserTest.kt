package pl.agent.telefon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agent.telefon.ai.ActionParser

class ActionParserTest {

    @Test
    fun parses_function_call_arguments() {
        val a = ActionParser.fromCall(
            "tap",
            JSONObject("""{"id": 4.0, "reason": "klik", "high_risk": true, "risk_note": "wysyła"}""")
        )
        assertEquals("tap", a.tool)
        assertEquals(4, a.id)
        assertTrue(a.highRisk)
        assertEquals("klik", a.reason)
        assertEquals("wysyła", a.riskNote)
    }

    @Test
    fun missing_optional_fields_are_null_or_default() {
        val a = ActionParser.fromCall("press_back", JSONObject("{}"))
        assertNull(a.id)
        assertNull(a.text)
        assertTrue(a.replace)
        assertFalse(a.highRisk)
    }

    @Test
    fun text_fallback_parses_embedded_json() {
        val a = ActionParser.fromText("Oto: {\"tool\":\"scroll\",\"args\":{\"direction\":\"DOWN\",\"reason\":\"x\"}}")
        assertNotNull(a)
        assertEquals("scroll", a!!.tool)
        assertEquals("down", a.direction)
    }

    @Test
    fun text_fallback_rejects_garbage() {
        assertNull(ActionParser.fromText("brak json"))
        assertNull(ActionParser.fromText("{\"foo\": 1}"))
    }
}
