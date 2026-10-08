package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.ExportResultUi
import com.webmaletin.gtaviewer.ExportScope
import com.webmaletin.gtaviewer.export.ExportFormat

@Composable
private fun FormatRow(selected: ExportFormat, onSelect: (ExportFormat) -> Unit) {
    Column {
        ExportFormat.values().forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(f) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == f, onClick = { onSelect(f) })
                Text(f.label)
            }
        }
    }
}

@Composable
fun ExportDialog(vm: AppViewModel, onDismiss: () -> Unit, onGo: (ExportFormat, ExportScope) -> Unit) {
    var fmtAll by remember { mutableStateOf(ExportFormat.GLB) }
    var fmtSel by remember { mutableStateOf(ExportFormat.GLB) }
    var full by remember { mutableStateOf(false) }
    val selCount = vm.editState.selection.size
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Exportar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("EXPORTAR VEHÍCULO")
                FormatRow(fmtAll) { fmtAll = it }
                Row(Modifier.fillMaxWidth().clickable { full = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !full, onClick = { full = false })
                    Text("Escena actual (lo que está visible${if (vm.isEdited) ", editada" else ""})", fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth().clickable { full = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = full, onClick = { full = true })
                    Text("Vehículo completo (ignora ediciones)", fontSize = 13.sp)
                }
                Button(onClick = { onGo(fmtAll, if (full) ExportScope.FULL else ExportScope.VISIBLE) }, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(6.dp))
                SectionTitle("EXPORTAR SELECCIÓN")
                if (selCount == 0) Text("Seleccione piezas en el modo edición (p. ej. las 4 ruedas) para exportarlas por separado.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("${vm.editState.selection.size} sub-mesh seleccionados → «${vm.selectionLabel()}»", fontSize = 12.sp)
                FormatRow(fmtSel) { fmtSel = it }
                Button(onClick = { onGo(fmtSel, ExportScope.SELECTION) }, enabled = selCount > 0, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR SELECCIÓN", fontWeight = FontWeight.Bold) }
                if (vm.normalizeScale) Text("Se aplicará la escala normalizada.", fontSize = 11.sp, color = Warn)
            }
        },
    )
}

@Composable
fun ExportResultDialog(res: ExportResultUi, onSave: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onSave) { Text("Guardar…") } },
        dismissButton = { Row { TextButton(onClick = onShare) { Text("Compartir") }; TextButton(onClick = onDismiss) { Text("Cerrar") } } },
        title = { Text("Exportación lista") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(res.suggestedName, fontWeight = FontWeight.Bold)
                Text("${res.output.bytes.size / 1024} KB · contiene: ${res.output.entries.joinToString()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                res.output.limitations?.let {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Color0x3A2A00) { Text("⚠ $it", color = Warn, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
                }
                if (res.output.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    res.output.warnings.forEach { Text("• $it", fontSize = 11.sp, color = Warn) }
                }
            }
        },
    )
}

private val Color0x3A2A00 = androidx.compose.ui.graphics.Color(0xFF3A2A00)

@Composable
fun InfoDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val m = vm.model ?: return
    val d = m.diagnostics
    val b = m.fullBounds()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Información del modelo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeyValue("Nombre", m.name)
                KeyValue("DFF", d.dffFileName)
                KeyValue("TXD", d.txdFileName ?: "— (sin TXD)")
                KeyValue("Parser", d.dffParser)
                KeyValue("Versión RW", d.rwVersion)
                KeyValue("Meshes", "${m.parts.size} piezas / ${m.subMeshes.size} sub-meshes")
                KeyValue("Vértices", "${m.totalVertices}")
                KeyValue("Triángulos", "${m.totalTriangles}")
                KeyValue("Materiales", "${m.materials.size}")
                KeyValue("Texturas", "${d.texturesFound.size} resueltas de ${d.texturesInTxd} en el TXD" + if (d.texturesMissing.isNotEmpty()) " (faltan: ${d.texturesMissing.joinToString()})" else "")
                KeyValue("Bounding Box", b?.let { "%.2f × %.2f × %.2f m (ancho × alto × largo)".format(it.size[0], it.size[1], it.size[2]) } ?: "—")
                KeyValue("Tamaño DFF", "${d.dffSize / 1024} KB")
                KeyValue("Tamaño TXD", if (d.txdFileName == null) "—" else "${d.txdSize / 1024} KB")
                KeyValue("Estado", vm.currentEntry?.status?.let { "${it.symbol} ${it.label}" } ?: "Carga manual")
                KeyValue("Validación", if (m.validation.needsReview) "Requiere revisión" else "Correcta")
                if (m.warnings.isNotEmpty()) {
                    SectionTitle("AVISOS")
                    m.warnings.distinct().take(25).forEach { Text("• $it", fontSize = 11.sp, color = Warn) }
                }
            }
        },
    )
}
