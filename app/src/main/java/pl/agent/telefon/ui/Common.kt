package pl.agent.telefon.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** Wiersz przewijanych „chipów” do wyboru jednej opcji: pary (wartość, etykieta). */
@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        for ((value, label) in options) {
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

fun decodeJpeg(bytes: ByteArray?, sample: Int = 1): ImageBitmap? {
    if (bytes == null) return null
    val o = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.asImageBitmap()
}

fun decodeFile(path: String?, sample: Int = 1): ImageBitmap? {
    if (path.isNullOrEmpty()) return null
    if (!File(path).exists()) return null
    val o = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, o)?.asImageBitmap()
}

fun formatTime(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("d MMM, HH:mm", Locale("pl", "PL")).format(Date(ms))

fun clock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
