package pl.agent.telefon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.AgentStatus
import pl.agent.telefon.core.Approval
import pl.agent.telefon.core.Prefs
import pl.agent.telefon.core.RuntimeState
import pl.agent.telefon.core.StepRecord
import pl.agent.telefon.core.TaskRecord

/**
 * Główny ekran: polecenie, status na żywo, przyciski sterowania, podgląd ekranu telefonu i przebieg zadania.
 * Dwa elementy mają pierwszeństwo wizualne: podgląd z urządzenia i przycisk „Natychmiast zatrzymaj”.
 */
@Composable
fun AgentScreen() {
    val rt by AgentRuntime.state.collectAsState()
    var settings by remember { mutableStateOf(Prefs.load()) }
    var command by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(rt.status.hasTask) {
        while (rt.status.hasTask) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { LiveStrip(rt, now) }

        if (!settings.consent) {
            item {
                ConsentCard {
                    Prefs.save(settings.copy(consent = true))
                    settings = Prefs.load()
                }
            }
        }

        val interrupted = rt.interrupted
        if (interrupted != null) {
            item {
                InterruptedCard(
                    rec = interrupted,
                    onResume = { error = AgentRuntime.resumeInterrupted() },
                    onDismiss = { AgentRuntime.dismissInterrupted() }
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Polecenie dla agenta") },
                    placeholder = { Text("np. Otwórz Chrome i sprawdź pogodę na jutro") },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !rt.status.hasTask
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { error = AgentRuntime.startTask(command) },
                        enabled = !rt.status.hasTask && command.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = { AgentRuntime.pause() },
                        enabled = rt.status == AgentStatus.PLANNING || rt.status == AgentStatus.EXECUTING,
                        modifier = Modifier.weight(1f)
                    ) { Text("Pauza") }
                    OutlinedButton(
                        onClick = { AgentRuntime.resume() },
                        enabled = rt.status == AgentStatus.PAUSED,
                        modifier = Modifier.weight(1f)
                    ) { Text("Wznów") }
                }
                Button(
                    onClick = { AgentRuntime.stop("Zatrzymano przyciskiem w aplikacji.") },
                    enabled = rt.status.hasTask,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Natychmiast zatrzymaj", fontWeight = FontWeight.Bold) }
                val err = error
                if (err != null) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val q = rt.question
        if (q != null) {
            item { QuestionCard(q, answer, { answer = it }) { AgentRuntime.answer(answer); answer = "" } }
        }
        val ap = rt.approval
        if (ap != null) {
            item { ApprovalCard(ap) }
        }

        item { PreviewBlock(rt) }

        if (rt.steps.isNotEmpty()) {
            item { SectionTitle("Przebieg zadania") }
            items(rt.steps.asReversed()) { StepRow(it) }
        }
    }
}

@Composable
private fun LiveStrip(rt: RuntimeState, now: Long) {
    val color = statusColor(rt.status)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(rt.status.label, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = color)
            val line = rt.stepText.ifBlank { rt.message }
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium)
            if (rt.taskText.isNotBlank()) {
                Text(
                    "Zadanie: ${rt.taskText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            if (rt.steps.isNotEmpty() || rt.status.hasTask) {
                val secs = if (rt.status.hasTask) (now - rt.startedAt) / 1000 else 0L
                val time = if (rt.status.hasTask) "   Czas: ${clock(secs)}" else ""
                Text(
                    "Kroki: ${rt.steps.size}$time   Koszt: ${"%.3f".format(rt.usage.costUsd)} USD   Wywołania API: ${rt.usage.calls}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!rt.a11yConnected) {
                Text(
                    "Usługa dostępności niepodłączona — włącz ją w zakładce Diagnostyka.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ConsentCard(onAccept: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zanim zaczniesz", fontWeight = FontWeight.SemiBold)
            Text(
                "Podczas zadania treść ekranu (teksty elementów) i zrzuty ekranu są wysyłane do zewnętrznego API " +
                    "wybranego dostawcy (Gemini lub DeepSeek) i mogą zawierać prywatne dane. Aplikacje z listy blokad " +
                    "nie są odczytywane ani wysyłane. Agent może klikać i wpisywać tekst w Twoim telefonie; zatrzymasz " +
                    "go w każdej chwili przyciskiem „Natychmiast zatrzymaj”, w nakładce lub w powiadomieniu.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onAccept) { Text("Rozumiem i akceptuję") }
        }
    }
}

@Composable
private fun InterruptedCard(rec: TaskRecord, onResume: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Poprzednie zadanie zostało przerwane", fontWeight = FontWeight.SemiBold)
            Text(rec.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text(
                "Zrobione kroki: ${rec.steps.size}. Wznowienie sprawdzi aktualny ekran i dopiero wtedy działa dalej.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Wznów zadanie") }
                TextButton(onClick = onDismiss) { Text("Odrzuć") }
            }
        }
    }
}

@Composable
private fun QuestionCard(question: String, answer: String, onAnswer: (String) -> Unit, onSend: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(2.dp, Color(0xFFD98E04), RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Agent pyta", fontWeight = FontWeight.Bold)
            Text(question)
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswer,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Twoja odpowiedź") }
            )
            Button(onClick = onSend, enabled = answer.isNotBlank()) { Text("Wyślij odpowiedź") }
        }
    }
}

@Composable
private fun ApprovalCard(a: Approval) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(2.dp, Color(0xFFD98E04), RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.title, fontWeight = FontWeight.Bold)
            Text(a.details)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { AgentRuntime.approve(true) }, modifier = Modifier.weight(1f)) { Text("Zatwierdź") }
                OutlinedButton(onClick = { AgentRuntime.approve(false) }, modifier = Modifier.weight(1f)) { Text("Odrzuć") }
            }
        }
    }
}

@Composable
private fun PreviewBlock(rt: RuntimeState) {
    val bmp = remember(rt.shotVersion) { decodeJpeg(AgentRuntime.lastShot) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Podgląd ekranu telefonu")
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Ostatni zrzut ekranu z urządzenia",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            )
        } else {
            Text(
                if (rt.status.hasTask) {
                    "Brak zrzutu: ekran jest w aplikacji z listy blokad albo przechwytywanie nie działa (Diagnostyka)."
                } else {
                    "Podgląd pojawi się po rozpoczęciu zadania. To prawdziwy zrzut z urządzenia, nie opis modelu."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepRow(s: StepRecord) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (s.ok) Color(0xFF2E9E4F) else Color(0xFFD32F2F))
        )
        Column(Modifier.weight(1f)) {
            Text("${s.index}. ${s.description}", fontWeight = FontWeight.Medium)
            if (s.reason.isNotBlank()) {
                Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.result.isNotBlank()) {
                Text(s.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
