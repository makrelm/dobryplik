package pl.agent.telefon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import pl.agent.telefon.device.AgentForegroundService
import pl.agent.telefon.ui.AgentScreen
import pl.agent.telefon.ui.AgentTheme
import pl.agent.telefon.ui.DiagnosticsScreen
import pl.agent.telefon.ui.HistoryScreen
import pl.agent.telefon.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                AgentForegroundService.startCapture(this, res.resultCode, data)
            } else {
                Toast.makeText(this, "Przechwytywanie ekranu: brak zgody.", Toast.LENGTH_LONG).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AgentTheme { AppRoot(onRequestCapture = { requestCapture() }) }
        }
    }

    /** Zgoda na przechwytywanie ekranu; od Androida 14 wymuszamy pełny ekran (bez wyboru jednej aplikacji). */
    private fun requestCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}

@Composable
private fun AppRoot(onRequestCapture: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("Agent", "Historia", "Ustawienia", "Diagnostyka")
    val glyphs = listOf("▶", "≡", "⚙", "✚")
    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(glyphs[i]) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when (tab) {
                0 -> AgentScreen()
                1 -> HistoryScreen()
                2 -> SettingsScreen()
                else -> DiagnosticsScreen(onRequestCapture)
            }
        }
    }
}
