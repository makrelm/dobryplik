package pl.agent.telefon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agent.telefon.core.AgentAction
import pl.agent.telefon.core.Prompt
import pl.agent.telefon.core.Tools
import pl.agent.telefon.core.UiNode
import pl.agent.telefon.core.UiSnapshot

class ModelsTest {

    private fun node(
        id: Int, text: String, password: Boolean = false,
        l: Int = 100, t: Int = 200, r: Int = 500, b: Int = 400
    ) = UiNode(
        id = id, cls = "Button", text = text, desc = "", rid = "ok_btn",
        l = l, t = t, r = r, b = b,
        clickable = true, longClickable = false, editable = false, scrollable = false,
        checkable = false, checked = false, enabled = true, focused = false, password = password
    )

    @Test
    fun prompt_uses_normalized_grid_and_flags() {
        val snap = UiSnapshot("com.x", 1000, 2000, listOf(node(0, "Zaloguj")), false)
        val p = snap.toPrompt()
        assertTrue(p.contains("[0] Button \"Zaloguj\""))
        assertTrue(p.contains("rid=ok_btn"))
        assertTrue(p.contains("(100,100,500,200)"))
        assertTrue(p.contains("click"))
    }

    @Test
    fun password_text_is_never_exposed() {
        val snap = UiSnapshot("com.x", 1000, 2000, listOf(node(0, "tajne123", password = true)), false)
        assertFalse(snap.toPrompt().contains("tajne123"))
        assertFalse(snap.nodes[0].label().contains("tajne123"))
    }

    @Test
    fun fingerprint_changes_with_content() {
        val a = UiSnapshot("com.x", 1000, 2000, listOf(node(0, "A")), false)
        val b = UiSnapshot("com.x", 1000, 2000, listOf(node(0, "B")), false)
        val c = UiSnapshot("com.x", 1000, 2000, listOf(node(0, "A")), false)
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.fingerprint, c.fingerprint)
    }

    @Test
    fun nodeAt_prefers_the_smallest_containing_node() {
        val big = node(0, "tlo", l = 0, t = 0, r = 1000, b = 2000)
        val small = node(1, "przycisk")
        val snap = UiSnapshot("com.x", 1000, 2000, listOf(big, small), false)
        assertEquals(1, snap.nodeAt(200, 150)?.id)
        assertEquals(0, snap.nodeAt(900, 900)?.id)
    }

    @Test
    fun blocked_app_prompt_contains_no_screen_content() {
        val snap = UiSnapshot("pl.bank", 1000, 2000, listOf(node(0, "Saldo 1234")), false)
        val text = Prompt.user("zadanie", null, "", emptyList(), snap, true, false, true)
        assertTrue(text.contains("ZABLOKOWANA"))
        assertFalse(text.contains("Saldo 1234"))
    }

    @Test
    fun tool_definitions_match_the_allowed_tool_set() {
        assertEquals(AgentAction.ALL_TOOLS, Tools.all.map { it.name }.toSet())
        assertEquals(13, Tools.all.size)
    }
}
