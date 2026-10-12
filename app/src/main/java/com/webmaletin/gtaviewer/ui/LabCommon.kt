package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.fit.CompatLevel

fun levelColor(l: CompatLevel): Color = when (l) {
    CompatLevel.COMPATIBLE -> Good; CompatLevel.ADJUST -> Color(0xFF8BD450); CompatLevel.DOUBTFUL -> Warn; CompatLevel.INCOMPATIBLE -> Bad
}

@Composable
fun LevelChip(l: CompatLevel, modifier: Modifier = Modifier) {
    val c = levelColor(l)
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.18f), modifier = modifier) {
        Text(l.label, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("←", fontSize = 18.sp) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Fila de chips con desplazamiento horizontal: elección única (null = "Todos"). */
@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, allLabel: String? = "Todos", onSelect: (T?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allLabel != null) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel, fontSize = 12.sp) })
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(if (o == selected && allLabel != null) null else o) }, label = { Text(label(o), fontSize = 12.sp) }) }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String = "%.2f".format(value), onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row { Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); Text(valueText, fontSize = 12.sp) }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, onValueChangeFinished = { onDone?.invoke() }, modifier = Modifier.height(28.dp))
    }
}

/** Campo numérico que confirma al terminar de editar (Enter / pérdida de foco) y se actualiza cuando cambia el valor externo. */
@Composable
fun NumField(label: String, value: Float, modifier: Modifier = Modifier, enabled: Boolean = true, decimals: Int = 3, onCommit: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf("%.${decimals}f".format(java.util.Locale.US, value)) }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true, label = { Text(label, fontSize = 10.sp) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { text.replace(',', '.').toFloatOrNull()?.let(onCommit) }),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp), modifier = modifier,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "CONFIRMAR", danger: Boolean = false, extra: (@Composable () -> Unit)? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Column { Text(text, fontSize = 14.sp); extra?.invoke() } },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }, colors = if (danger) ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Color.Black) else ButtonDefaults.buttonColors()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })
}

@Composable
fun TextPromptDialog(title: String, initial: String, label: String = "Nombre", onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text(title) },
        text = { OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { Button(onClick = { onOk(t); onDismiss() }, enabled = t.isNotBlank()) { Text("ACEPTAR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })
}

/** Miniatura del catálogo: se genera de forma progresiva (software, sin GL) y se cachea en disco. */
@Composable
fun Thumb(lab: LabViewModel, id: String, size: Int = 96, modifier: Modifier = Modifier) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id, lab.libRev) { bmp = lab.thumb(id) }
    Box(modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF12151B)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
    }
}

@Composable
fun InfoBox(text: String, color: Color = Color(0xFF1C2230), modifier: Modifier = Modifier) {
    Surface(color = color, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth()) { Text(text, fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }
}

val Swatches = listOf(0xF2F2F2, 0xC9CDD2, 0x6B7280, 0x1C1C1E, 0xC62828, 0xE65100, 0xF9A825, 0x2E7D32, 0x00838F, 0x1565C0, 0x4527A0, 0xAD1457)

@Composable
fun SwatchRow(onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Swatches.forEach { c -> Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF000000 or c.toLong())).then(Modifier.clickableNoRipple { onPick(c) })) }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
