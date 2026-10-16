package pl.agent.telefon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import pl.agent.telefon.core.AgentStatus

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86),
    onPrimary = Color.White,
    secondary = Color(0xFF8A5A00),
    background = Color(0xFFF3F5F2),
    onBackground = Color(0xFF14212B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14212B),
    surfaceVariant = Color(0xFFE4EAE6),
    onSurfaceVariant = Color(0xFF3B4A52),
    error = Color(0xFFC62828),
    outline = Color(0xFF8A989F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3CC),
    onPrimary = Color(0xFF00282B),
    secondary = Color(0xFFFFC857),
    background = Color(0xFF0E171D),
    onBackground = Color(0xFFE3EAEE),
    surface = Color(0xFF15222B),
    onSurface = Color(0xFFE3EAEE),
    surfaceVariant = Color(0xFF1F2F3A),
    onSurfaceVariant = Color(0xFFB4C2CA),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF6B7C85)
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

fun statusColor(s: AgentStatus): Color = when (s) {
    AgentStatus.IDLE -> Color(0xFF64748B)
    AgentStatus.PLANNING -> Color(0xFF2F7DD1)
    AgentStatus.EXECUTING -> Color(0xFF14A06B)
    AgentStatus.WAITING -> Color(0xFFD98E04)
    AgentStatus.PAUSED -> Color(0xFF7E57C2)
    AgentStatus.ERROR -> Color(0xFFD32F2F)
    AgentStatus.DONE -> Color(0xFF2E9E4F)
    AgentStatus.STOPPED -> Color(0xFF6B7280)
}
