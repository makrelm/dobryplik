package pl.agent.telefon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.AgentStatus
import pl.agent.telefon.core.StepRecord
import pl.agent.telefon.core.Store
import pl.agent.telefon.core.TaskRecord

/** Historia zadań z listą kroków i zrzutami ekranu (dotknij miniaturę, aby powiększyć). */
@Composable
fun HistoryScreen() {
    val rt by AgentRuntime.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val tasks = remember(rt.status, rt.steps.size, tick) { Store.loadAll() }
    var openId by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Historia zadań", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        Store.clearAll()
                        tick++
                    },
                    enabled = tasks.isNotEmpty() && !rt.status.hasTask
                ) { Text("Wyczyść") }
            }
        }
        if (tasks.isEmpty()) {
            item {
                Text(
                    "Brak zapisanych zadań. Zrzuty ekranu są przechowywane tylko lokalnie, w pamięci podręcznej aplikacji.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(tasks) { t ->
            TaskCard(
                t = t,
                open = openId == t.id,
                onToggle = { openId = if (openId == t.id) null else t.id },
                onZoom = { zoom = it }
            )
        }
    }

    val z = zoom
    if (z != null) {
        val bmp = remember(z) { decodeFile(z) }
        Dialog(onDismissRequest = { zoom = null }) {
            Surface(shape = RoundedCornerShape(12.dp)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "Zrzut ekranu z kroku zadania",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().clickable { zoom = null }
                    )
                } else {
                    Text("Brak obrazu (system mógł usunąć plik z pamięci podręcznej).", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskRecord, open: Boolean, onToggle: () -> Unit, onZoom: (String) -> Unit) {
    val label = runCatching { AgentStatus.valueOf(t.status).label }.getOrDefault(t.status)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                t.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (open) 10 else 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$label, ${formatTime(t.startedAt)}, kroki: ${t.steps.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (t.message.isNotBlank()) {
                Text(
                    t.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (open) 10 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (open) {
                for (s in t.steps) HistoryStep(s, onZoom)
            }
        }
    }
}

@Composable
private fun HistoryStep(s: StepRecord, onZoom: (String) -> Unit) {
    val thumb = remember(s.shotPath) { decodeFile(s.shotPath, 4) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("${s.index}. ${s.description}", fontWeight = FontWeight.Medium)
            if (s.result.isNotBlank()) {
                Text(s.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = "Zrzut z kroku ${s.index}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onZoom(s.shotPath.orEmpty()) }
            )
        }
    }
}
