package pl.agent.telefon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.agent.telefon.ai.AiException
import pl.agent.telefon.ai.Providers
import pl.agent.telefon.core.Guards
import pl.agent.telefon.core.Prefs
import pl.agent.telefon.core.SecretStore
import pl.agent.telefon.core.Settings
import pl.agent.telefon.core.Store

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(Prefs.load()) }
    val nums = remember {
        mutableStateMapOf(
            "maxSteps" to s.maxSteps.toString(),
            "maxMinutes" to s.maxMinutes.toString(),
            "taskBudget" to s.taskBudgetUsd.toString(),
            "totalBudget" to s.totalBudgetUsd.toString(),
            "gIn" to s.geminiPriceIn.toString(),
            "gOut" to s.geminiPriceOut.toString(),
            "dIn" to s.deepseekPriceIn.toString(),
            "dOut" to s.deepseekPriceOut.toString(),
            "imgSide" to s.imageMaxSide.toString()
        )
    }
    var geminiKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var usageTick by remember { mutableIntStateOf(0) }
    var keyTick by remember { mutableIntStateOf(0) }

    fun dbl(k: String, def: Double): Double = nums[k]?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: def
    fun int(k: String, def: Int): Int = nums[k]?.trim()?.toIntOrNull() ?: def

    fun commit(): Settings {
        val merged = s.copy(
            maxSteps = int("maxSteps", s.maxSteps).coerceIn(1, 500),
            maxMinutes = int("maxMinutes", s.maxMinutes).coerceIn(1, 600),
            taskBudgetUsd = dbl("taskBudget", s.taskBudgetUsd).coerceAtLeast(0.0),
            totalBudgetUsd = dbl("totalBudget", s.totalBudgetUsd).coerceAtLeast(0.0),
            geminiPriceIn = dbl("gIn", s.geminiPriceIn).coerceAtLeast(0.0),
            geminiPriceOut = dbl("gOut", s.geminiPriceOut).coerceAtLeast(0.0),
            deepseekPriceIn = dbl("dIn", s.deepseekPriceIn).coerceAtLeast(0.0),
            deepseekPriceOut = dbl("dOut", s.deepseekPriceOut).coerceAtLeast(0.0),
            imageMaxSide = int("imgSide", s.imageMaxSide).coerceIn(256, 1600)
        )
        Prefs.save(merged)
        s = merged
        return merged
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Ustawienia", style = MaterialTheme.typography.headlineSmall) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Dostawca modelu AI")
                ChipRow(listOf("gemini" to "Gemini", "deepseek" to "DeepSeek"), s.provider) {
                    s = s.copy(provider = it)
                }
                if (s.provider == "gemini") {
                    OutlinedTextField(
                        value = s.geminiModel,
                        onValueChange = { s = s.copy(geminiModel = it) },
                        label = { Text("Model Gemini") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Poziom rozumowania (thinkingLevel)", style = MaterialTheme.typography.bodySmall)
                    ChipRow(listOf("low" to "low", "medium" to "medium", "high" to "high"), s.geminiThinking) {
                        s = s.copy(geminiThinking = it)
                    }
                } else {
                    OutlinedTextField(
                        value = s.deepseekModel,
                        onValueChange = { s = s.copy(deepseekModel = it) },
                        label = { Text("Model DeepSeek") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Poziom rozumowania (reasoning_effort)", style = MaterialTheme.typography.bodySmall)
                    ChipRow(
                        listOf("none" to "wyłączone", "low" to "low", "high" to "high", "max" to "max"),
                        s.deepseekEffort
                    ) { s = s.copy(deepseekEffort = it) }
                }
            }
        }

        item {
            val hasG = remember(keyTick) { SecretStore.has("key_gemini") }
            val hasD = remember(keyTick) { SecretStore.has("key_deepseek") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Klucze API (szyfrowane w Android Keystore)")
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Klucz Gemini") },
                    placeholder = { Text(if (hasG) "zapisany — wpisz nowy, aby zastąpić" else "wklej klucz") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            SecretStore.put("key_gemini", geminiKey)
                            geminiKey = ""
                            keyTick++
                            info = "Klucz Gemini zapisany."
                        },
                        enabled = geminiKey.isNotBlank()
                    ) { Text("Zapisz klucz") }
                    OutlinedButton(
                        onClick = {
                            SecretStore.put("key_gemini", "")
                            keyTick++
                            info = "Klucz Gemini usunięty."
                        },
                        enabled = hasG
                    ) { Text("Usuń") }
                }
                OutlinedTextField(
                    value = deepseekKey,
                    onValueChange = { deepseekKey = it },
                    label = { Text("Klucz DeepSeek") },
                    placeholder = { Text(if (hasD) "zapisany — wpisz nowy, aby zastąpić" else "wklej klucz") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            SecretStore.put("key_deepseek", deepseekKey)
                            deepseekKey = ""
                            keyTick++
                            info = "Klucz DeepSeek zapisany."
                        },
                        enabled = deepseekKey.isNotBlank()
                    ) { Text("Zapisz klucz") }
                    OutlinedButton(
                        onClick = {
                            SecretStore.put("key_deepseek", "")
                            keyTick++
                            info = "Klucz DeepSeek usunięty."
                        },
                        enabled = hasD
                    ) { Text("Usuń") }
                }
                Button(onClick = {
                    commit()
                    info = "Testuję połączenie…"
                    scope.launch {
                        info = try {
                            Providers.create(Prefs.load())?.ping() ?: "Brak klucza API dla wybranego dostawcy."
                        } catch (e: AiException) {
                            "Błąd: ${e.userMessage()}"
                        } catch (e: Exception) {
                            "Błąd: ${e.message}"
                        }
                    }
                }) { Text("Testuj połączenie z wybranym dostawcą") }
                if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Limity i koszty")
                NumField("Maks. liczba kroków w zadaniu", nums["maxSteps"].orEmpty(), false) { nums["maxSteps"] = it }
                NumField("Maks. czas zadania (minuty)", nums["maxMinutes"].orEmpty(), false) { nums["maxMinutes"] = it }
                NumField("Budżet jednego zadania (USD, 0 = bez limitu)", nums["taskBudget"].orEmpty(), true) { nums["taskBudget"] = it }
                NumField("Budżet całkowity do resetu licznika (USD)", nums["totalBudget"].orEmpty(), true) { nums["totalBudget"] = it }
                Text(
                    "Ceny w USD za 1 mln tokenów. Wartości domyślne są orientacyjne i mogą być nieaktualne — wpisz je " +
                        "według aktualnego cennika dostawcy. Koszt liczę z rzeczywistych tokenów zwracanych przez API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NumField("Gemini: cena wejścia", nums["gIn"].orEmpty(), true) { nums["gIn"] = it }
                NumField("Gemini: cena wyjścia (w tym tokeny myślenia)", nums["gOut"].orEmpty(), true) { nums["gOut"] = it }
                NumField("DeepSeek: cena wejścia", nums["dIn"].orEmpty(), true) { nums["dIn"] = it }
                NumField("DeepSeek: cena wyjścia", nums["dOut"].orEmpty(), true) { nums["dOut"] = it }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Obrazy (oszczędzanie kosztów)")
                ChipRow(
                    listOf("auto" to "Auto (tylko gdy trzeba)", "always" to "Zawsze", "never" to "Nigdy"),
                    s.imageMode
                ) { s = s.copy(imageMode = it) }
                NumField("Maks. dłuższy bok obrazu (px)", nums["imgSide"].orEmpty(), false) { nums["imgSide"] = it }
                SwitchRow("Podgląd ekranu w aplikacji", "Robi zrzut w każdym kroku (bez wysyłania do API).", s.livePreview) {
                    s = s.copy(livePreview = it)
                }
                SwitchRow("Zapisuj zrzuty w historii", "Tylko w pamięci podręcznej aplikacji; do 80 plików.", s.saveShots) {
                    s = s.copy(saveShots = it)
                }
                OutlinedButton(onClick = {
                    Store.clearShots()
                    info = "Zrzuty usunięte."
                }) { Text("Usuń zapisane zrzuty") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Praca w tle")
                SwitchRow("Nakładka ze statusem", "Mały panel z przyciskami Pauza i Stop nad używaną aplikacją.", s.overlayEnabled) {
                    s = s.copy(overlayEnabled = it)
                }
                SwitchRow("Utrzymuj ekran włączony podczas zadania", "Zużywa więcej baterii. Blokady telefonu agent nie omija.", s.keepScreenOn) {
                    s = s.copy(keepScreenOn = it)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Aplikacje zablokowane dla agenta")
                Text(
                    "Jedna nazwa pakietu w linii. Dla tych aplikacji nie odczytuję ekranu, nie robię zrzutów i nic nie wysyłam do API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = s.blocklist,
                    onValueChange = { s = s.copy(blocklist = it) },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            val g = remember(usageTick) { Prefs.totals("gemini") }
            val d = remember(usageTick) { Prefs.totals("deepseek") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Zużycie (lokalny licznik)")
                Text("Gemini: ${g.calls} wywołań, ${g.inTokens} tok. wejścia, ${g.outTokens} tok. wyjścia, ${Guards.money(g.costUsd)}")
                Text("DeepSeek: ${d.calls} wywołań, ${d.inTokens} tok. wejścia, ${d.outTokens} tok. wyjścia, ${Guards.money(d.costUsd)}")
                OutlinedButton(onClick = {
                    Prefs.resetUsage()
                    usageTick++
                }) { Text("Resetuj licznik") }
            }
        }

        item {
            Button(
                onClick = {
                    commit()
                    info = "Ustawienia zapisane."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz ustawienia") }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, decimal: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
