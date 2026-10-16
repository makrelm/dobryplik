package pl.agent.telefon.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.agent.telefon.core.AgentRuntime
import pl.agent.telefon.core.Prefs
import pl.agent.telefon.core.RuntimeState
import pl.agent.telefon.core.SecretStore
import pl.agent.telefon.device.AgentAccessibilityService
import pl.agent.telefon.device.AgentForegroundService
import pl.agent.telefon.device.Capture

private class Check(val name: String, val ok: Boolean, val detail: String)

private fun a11yEnabled(ctx: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(
        ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val me = ComponentName(ctx, AgentAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

private fun runChecks(ctx: Context, rt: RuntimeState): List<Check> {
    val out = ArrayList<Check>()
    val enabled = a11yEnabled(ctx)
    val connected = AgentAccessibilityService.instance != null
    out.add(
        Check(
            "Usługa dostępności", enabled && connected,
            when {
                !enabled -> "Wyłączona. Włącz „Agent Telefonu” w Ustawienia → Ułatwienia dostępu."
                !connected -> "Włączona, ale niepołączona. Wyłącz ją i włącz ponownie."
                else -> "Włączona i połączona."
            }
        )
    )
    out.add(
        Check(
            "Przechwytywanie ekranu (MediaProjection)", rt.captureActive,
            if (rt.captureActive) "Aktywne." else
                "Nieaktywne. Zrzuty będą pobierane zapasowo przez usługę dostępności. Zgoda jest wymagana przy każdej sesji."
        )
    )
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    out.add(
        Check(
            "Powiadomienia", notif,
            if (notif) "Zgoda udzielona." else "Brak zgody — powiadomienie z przyciskami może się nie wyświetlać."
        )
    )
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    out.add(
        Check(
            "Optymalizacja baterii", ignoring,
            if (ignoring) "Wyłączona dla tej aplikacji." else "Zalecane: wyłącz ją dla tej aplikacji (przycisk poniżej)."
        )
    )
    out.add(Check("Internet", rt.online, if (rt.online) "Połączono." else "Brak sieci."))
    val s = Prefs.load()
    val keyName = if (s.provider == "gemini") "key_gemini" else "key_deepseek"
    val hasKey = SecretStore.has(keyName)
    out.add(
        Check(
            "Klucz API (${if (s.provider == "gemini") "Gemini" else "DeepSeek"})", hasKey,
            if (hasKey) "Zapisany w Keystore." else "Brak — dodaj w Ustawieniach."
        )
    )
    val km = ctx.getSystemService(KeyguardManager::class.java)
    val screenOk = pm.isInteractive && !km.isKeyguardLocked
    out.add(
        Check(
            "Ekran", screenOk,
            if (screenOk) "Włączony i odblokowany." else "Wyłączony lub zablokowany — agent wstrzyma zadanie."
        )
    )
    return out
}

@Composable
fun DiagnosticsScreen(onRequestCapture: () -> Unit) {
    val ctx = LocalContext.current
    val rt by AgentRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var clickInfo by remember { mutableStateOf("") }
    var shotInfo by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    val checks = remember(tick, rt.a11yConnected, rt.captureActive, rt.online) { runChecks(ctx, rt) }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            clickInfo = "Nie można otworzyć ustawień: ${e.message}"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Diagnostyka", style = MaterialTheme.typography.headlineSmall) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in checks) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (c.ok) "✓" else "✗",
                            color = if (c.ok) Color(0xFF2E9E4F) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold
                        )
                        Column {
                            Text(c.name, fontWeight = FontWeight.Medium)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Uprawnienia")
                Button(
                    onClick = { open(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Otwórz ustawienia dostępności") }
                OutlinedButton(
                    onClick = {
                        open(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", ctx.packageName, null)
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Informacje o aplikacji (ograniczone ustawienia)") }
                Text(
                    "Android 13+: po instalacji z pliku APK włączenie usługi dostępności bywa zablokowane. W „Informacje o " +
                        "aplikacji” dotknij ⋮ i wybierz „Zezwól na ograniczone ustawienia”, potem włącz usługę.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                    Text("Włącz przechwytywanie ekranu")
                }
                OutlinedButton(
                    onClick = { AgentForegroundService.stopCapture(ctx) },
                    enabled = rt.captureActive,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Zatrzymaj przechwytywanie ekranu") }
                OutlinedButton(
                    onClick = {
                        open(
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Wyłącz optymalizację baterii (okno systemowe)") }
                Text(
                    "Dodatkowo: w ustawieniach baterii telefonu ustaw dla tej aplikacji „Bez ograniczeń”. " +
                        "Aplikacja niczego nie zmienia sama — decyzję podejmujesz w oknach systemowych.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Test kliknięcia przez usługę dostępności")
                OutlinedButton(onClick = { hits++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cel testu kliknięcia ($hits)")
                }
                Button(
                    onClick = {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            clickInfo = "Usługa dostępności nie jest połączona."
                        } else {
                            val before = hits
                            clickInfo = "Wysyłam gest…"
                            scope.launch {
                                val sent = svc.tapByText("Cel testu kliknięcia")
                                delay(700)
                                clickInfo = if (hits > before) {
                                    "OK: gest dotarł do celu (licznik: $hits)."
                                } else {
                                    "Gest wysłany: $sent, ale cel nie zareagował. Sprawdź, czy ta karta jest widoczna na ekranie."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Wykonaj test") }
                if (clickInfo.isNotBlank()) Text(clickInfo, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Test zrzutu ekranu")
                Button(
                    onClick = {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            shotInfo = "Usługa dostępności nie jest połączona."
                        } else {
                            shotInfo = "Pobieram zrzut…"
                            scope.launch {
                                val jpeg = Capture.grabJpeg(svc, 640)
                                shot = decodeJpeg(jpeg)
                                shotInfo = if (jpeg == null) {
                                    "Nie udało się pobrać zrzutu."
                                } else {
                                    val src = if (rt.captureActive) "MediaProjection" else "usługa dostępności (zapasowo)"
                                    "Zrzut OK: ${jpeg.size / 1024} KB, źródło: $src."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Pobierz zrzut") }
                if (shotInfo.isNotBlank()) Text(shotInfo, style = MaterialTheme.typography.bodySmall)
                val img = shot
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = "Zrzut testowy",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }

        item {
            Text(
                "Znane ograniczenia: okna z flagą FLAG_SECURE (np. banki) dają czarny zrzut; ekranu blokady nie omijam; " +
                    "Tryb zaawansowanej ochrony w nowszych Androidach może blokować usługi dostępności aplikacji " +
                    "spoza sklepu; po restarcie telefonu usługę dostępności trzeba czasem włączyć ponownie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
