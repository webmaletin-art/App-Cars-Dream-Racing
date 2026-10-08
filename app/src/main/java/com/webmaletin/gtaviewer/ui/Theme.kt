package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.catalog.VehicleStatus

val Accent = Color(0xFFFF9F1C)
val Bg = Color(0xFF0F1115)
val Panel = Color(0xFF181C23)
val PanelHi = Color(0xFF222833)
val Good = Color(0xFF4CD08A)
val Warn = Color(0xFFFFC857)
val Bad = Color(0xFFFF6B6B)

private val scheme = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF1B1200), secondary = Color(0xFF7CC4FF), background = Bg, onBackground = Color(0xFFE8EAEE),
    surface = Panel, onSurface = Color(0xFFE8EAEE), surfaceVariant = PanelHi, onSurfaceVariant = Color(0xFFB4BAC6),
    error = Bad, outline = Color(0xFF3A4150),
)

@Composable
fun GtaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

fun statusColor(s: VehicleStatus): Color = when (s) {
    VehicleStatus.COMPLETE -> Good
    VehicleStatus.READ_ERROR -> Bad
    else -> Warn
}

@Composable
fun StatusChip(s: VehicleStatus, modifier: Modifier = Modifier) {
    val c = statusColor(s)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f), modifier = modifier) {
        Text("${s.symbol} ${s.label}", color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = modifier.padding(vertical = 6.dp))

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(0.38f))
        Text(v, fontSize = 13.sp, modifier = Modifier.weight(0.62f))
    }
}
