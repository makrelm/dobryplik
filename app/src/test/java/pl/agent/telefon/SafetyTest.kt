package pl.agent.telefon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agent.telefon.core.AgentAction
import pl.agent.telefon.core.Safety

class SafetyTest {

    @Test
    fun risky_labels_are_detected() {
        assertTrue(Safety.looksRisky("Wyślij"))
        assertTrue(Safety.looksRisky("send_button"))
        assertTrue(Safety.looksRisky("Kup teraz"))
        assertTrue(Safety.looksRisky("Delete account"))
        assertFalse(Safety.looksRisky("Dalej"))
        assertFalse(Safety.looksRisky(""))
    }

    @Test
    fun blocklist_parsing_ignores_comments_and_blank_lines() {
        val set = Safety.parseBlocklist("# komentarz\ncom.a\n\n  com.b  \n")
        assertEquals(setOf("com.a", "com.b"), set)
        assertTrue(Safety.isBlocked("com.a", set))
        assertFalse(Safety.isBlocked("com.c", set))
        assertFalse(Safety.isBlocked(null, set))
    }

    @Test
    fun default_blocklist_contains_own_package() {
        assertTrue(Safety.parseBlocklist(Safety.DEFAULT_BLOCKLIST_TEXT).contains(Safety.OWN_PACKAGE))
    }

    @Test
    fun tap_requires_a_valid_target() {
        assertNotNull(Safety.validate(AgentAction("tap"), 5, false))
        assertNull(Safety.validate(AgentAction("tap", id = 2), 5, false))
        assertNotNull(Safety.validate(AgentAction("tap", id = 9), 5, false))
        assertNull(Safety.validate(AgentAction("tap", x = 500, y = 500), 5, false))
        assertNotNull(Safety.validate(AgentAction("tap", x = 1500, y = 500), 5, false))
    }

    @Test
    fun blocked_app_forbids_interaction_but_allows_leaving() {
        assertNotNull(Safety.validate(AgentAction("tap", id = 1), 5, true))
        assertNull(Safety.validate(AgentAction("press_home"), 5, true))
        assertNull(Safety.validate(AgentAction("press_back"), 5, true))
    }

    @Test
    fun unknown_tool_is_rejected() {
        assertNotNull(Safety.validate(AgentAction("rm_rf"), 5, false))
    }

    @Test
    fun other_tools_validate_required_fields() {
        assertNotNull(Safety.validate(AgentAction("scroll", direction = "sideways"), 5, false))
        assertNull(Safety.validate(AgentAction("scroll", direction = "down"), 5, false))
        assertNotNull(Safety.validate(AgentAction("input_text"), 5, false))
        assertNull(Safety.validate(AgentAction("input_text", text = "abc"), 5, false))
        assertNotNull(Safety.validate(AgentAction("open_app", app = " "), 5, false))
        assertNotNull(Safety.validate(AgentAction("swipe", x = 1, y = 2, x2 = 3), 5, false))
        assertNull(Safety.validate(AgentAction("swipe", x = 1, y = 2, x2 = 3, y2 = 4), 5, false))
    }
}
