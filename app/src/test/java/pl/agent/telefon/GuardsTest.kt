package pl.agent.telefon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.agent.telefon.core.Guards
import pl.agent.telefon.core.Limits
import pl.agent.telefon.core.Prices
import pl.agent.telefon.core.ProgressGuard

class GuardsTest {

    @Test
    fun limits_trigger_on_steps_time_and_budgets() {
        val l = Limits(maxSteps = 10, maxMinutes = 5, taskBudgetUsd = 1.0, totalBudgetUsd = 10.0)
        assertNull(Guards.limitReason(l, 3, 1_000L, 0.1, 0.1))
        assertNotNull(Guards.limitReason(l, 10, 1_000L, 0.1, 0.1))
        assertNotNull(Guards.limitReason(l, 1, 6 * 60_000L, 0.1, 0.1))
        assertNotNull(Guards.limitReason(l, 1, 1_000L, 1.0, 0.1))
        assertNotNull(Guards.limitReason(l, 1, 1_000L, 0.1, 10.0))
    }

    @Test
    fun zero_limit_means_unlimited() {
        val l = Limits(0, 0, 0.0, 0.0)
        assertNull(Guards.limitReason(l, 1_000, 10_000_000L, 99.0, 99.0))
    }

    @Test
    fun unchanged_screen_gives_warning_then_stop() {
        val g = ProgressGuard()
        var last = ProgressGuard.Result(ProgressGuard.Verdict.OK)
        for (i in 1..3) last = g.record("tap|$i", true, false)
        assertEquals(ProgressGuard.Verdict.WARN, last.verdict)
        for (i in 4..5) last = g.record("tap|$i", true, false)
        assertEquals(ProgressGuard.Verdict.STOP, last.verdict)
    }

    @Test
    fun repeated_action_is_detected_even_if_screen_changes() {
        val g = ProgressGuard()
        var v = ProgressGuard.Verdict.OK
        repeat(5) { v = g.record("tap|7", true, true).verdict }
        assertEquals(ProgressGuard.Verdict.STOP, v)
    }

    @Test
    fun changing_screen_with_varied_actions_is_fine() {
        val g = ProgressGuard()
        for (i in 1..10) {
            assertEquals(ProgressGuard.Verdict.OK, g.record("tap|$i", true, true).verdict)
        }
    }

    @Test
    fun cost_is_computed_from_tokens() {
        assertEquals(0.015, Prices.cost(10_000, 2_000, 0.75, 3.75), 1e-9)
    }
}
