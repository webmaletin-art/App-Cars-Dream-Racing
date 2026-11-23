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
import com.webmaletin.gtaviewer.export.DamageMode
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

val FormatHelp = mapOf(
    ExportFormat.GLB to "ARCHIVO ÚNICO con geometría, materiales (con metálico/rugosidad) y texturas. Es el formato moderno y el mejor para tu juego: " +
        "Godot, Blender, Unity (con paquete glTF), visores web y el Visor 3D de Windows lo abren directo. Las ruedas salen como piezas independientes. Recomendado.",
    ExportFormat.OBJ to "ZIP con .obj + .mtl + carpeta de texturas PNG. Formato viejo y muy compatible: casi cualquier programa 3D lo abre (Blender, Maya, 3ds Max, MeshLab…). " +
        "Sirve para retocar la malla a mano o si tu herramienta no acepta GLB. No guarda jerarquía ni materiales avanzados (solo color y textura simples).",
    ExportFormat.FBX to "ZIP con .fbx (texto ASCII) + texturas PNG. Formato de Autodesk que piden Unity, Unreal, 3ds Max y Maya. Útil si tu flujo de trabajo es de esos programas. " +
        "Aquí sale como lista plana de mallas con color y textura base (sin normal/specular); pesa más que el GLB.",
)

@Composable
private fun DamageRow(selected: DamageMode, format: ExportFormat, onSelect: (DamageMode) -> Unit) {
    Column {
        DamageMode.values().forEach { d ->
            // el GLB con daños dentro solo existe en GLB; OBJ/FBX usan el ZIP con archivos separados
            val enabled = !(d == DamageMode.EMBEDDED && format != ExportFormat.GLB)
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(d) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == d && enabled, onClick = { onSelect(d) }, enabled = enabled)
                Column { Text(d.label, fontSize = 14.sp); Text(if (enabled) d.help else "No disponible en ${format.name}: use el ZIP con archivos separados.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun ExportDialog(vm: AppViewModel, onDismiss: () -> Unit, onGo: (ExportFormat, ExportScope) -> Unit) {
    var fmtAll by remember { mutableStateOf(ExportFormat.GLB) }
    var fmtSel by remember { mutableStateOf(ExportFormat.GLB) }
    val selCount = vm.editState.selection.size
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Exportar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("EXPORTAR VEHÍCULO")
                FormatRow(fmtAll) { fmtAll = it; if (it != ExportFormat.GLB && vm.damageMode == DamageMode.EMBEDDED) vm.damageMode = DamageMode.SEPARATE_ZIP }
                Text(FormatHelp.getValue(fmtAll), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionTitle("DAÑOS DEL VEHÍCULO")
                DamageRow(vm.damageMode, fmtAll) { vm.damageMode = it }
                Text("Los daños (*_dam) se exportan junto con el vehículo, no como piezas sueltas. Si falta la pieza intacta (por ejemplo, quitó una puerta), su daño tampoco sale.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.currentBeam != null) {
                    SectionTitle("QUÉ LLEVA EL ZIP (BeamNG)")
                    @Composable fun opt(checked: Boolean, label: String, on: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, on); Text(label, fontSize = 13.sp) }
                    opt(vm.beamModel, "Modelo 3D (piezas separadas, con daños si el mod los trae)") { vm.beamModel = it }
                    opt(vm.beamPhysics, "Física: nodos y vigas, masa, ruedas (JSON)") { vm.beamPhysics = it }
                    opt(vm.beamAudio, "Audio del mod (motor, escape, turbo, arranque + configuración de sonido)") { vm.beamAudio = it }
                    opt(vm.beamConfigs, "Todas las configuraciones (.pc) y sus fichas") { vm.beamConfigs = it }
                    Text("Con más de una opción todo va en UN solo ZIP. El audio del mod no indica qué sonido usa cada configuración, así que van todos los de art/sound.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { vm.beamModel = false; vm.beamPhysics = false; vm.beamAudio = true; vm.beamConfigs = false; onGo(fmtAll, ExportScope.VISIBLE) }, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR SOLO AUDIO", fontWeight = FontWeight.Bold) }
                    Text("BeamNG no guarda mallas dañadas: simula los daños deformando vigas. Solo salen las variantes sueltas que traiga el mod (p. ej. parabrisas roto). Godot no ejecuta la física tal cual: sirve para derivar masa, ruedas y centro de masa.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text("Se exporta exactamente lo que está visible${if (vm.isEdited) " (vehículo editado)" else ""}: partes marcadas = incluidas, desmarcadas = fuera.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.counters?.let { Text("Partes visibles: ${it.partsVisible} / ${it.partsTotal}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = { onGo(fmtAll, ExportScope.VISIBLE) }, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR ESTE VEHÍCULO", fontWeight = FontWeight.Bold) }
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


@Composable
fun PrepDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val o = vm.prepOptions
    @Composable fun row(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChange); Text(label, fontSize = 14.sp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Preparación para mi juego") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Valores iniciales para preparar vehículos. Después cada vehículo se ajusta en su Outliner y su configuración individual tiene prioridad.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                row("Eliminar ruedas", o.removeWheels) { vm.prepOptions = o.copy(removeWheels = it) }
                row("Eliminar suspensión rígida", o.removeSuspension) { vm.prepOptions = o.copy(removeSuspension = it) }
                row("Eliminar discos/frenos", o.removeBrakes) { vm.prepOptions = o.copy(removeBrakes = it) }
                row("Eliminar otras piezas (sin clasificar)", o.removeOther) { vm.prepOptions = o.copy(removeOther = it) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onDismiss(); vm.prepareSelected() }, enabled = vm.selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("APLICAR A LOS ${vm.selectedIds.size} SELECCIONADOS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text("Solo afecta a vehículos sin configuración propia (no revisados). Los editados o listos no se tocan.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
fun BatchConfirmDialog(c: com.webmaletin.gtaviewer.BatchConfirm, format: ExportFormat, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        confirmButton = { TextButton(onClick = onYes) { Text("EXPORTAR DE TODOS MODOS") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } },
        title = { Text("Exportar ${c.items.size} vehículos (${format.name})") },
        text = { Text("${c.unreviewed} vehículo(s) están SIN REVISAR y se exportarán con todas sus partes visibles (incluidas las ruedas originales), porque no tienen configuración propia. " +
            "Puede abrirlos y usar «Preparar para mi juego» antes.", fontSize = 13.sp) },
    )
}

@Composable
fun BatchDialog(vm: AppViewModel, onShare: (java.io.File, String, String) -> Unit) {
    val b = vm.batchUi ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissBatch() },
        title = { Text(if (b.finished) "EXPORTACIÓN COMPLETADA" else "EXPORTANDO VEHÍCULOS") },
        confirmButton = {
            if (b.finished) { if (b.zipFile != null) TextButton(onClick = { vm.pendingBatchSave = b.zipFile }) { Text("Guardar ${b.zipName}") } }
            else TextButton(onClick = { vm.cancelBatch() }) { Text("Cancelar") }
        },
        dismissButton = {
            if (b.finished) Row {
                if (b.zipFile != null) TextButton(onClick = { onShare(b.zipFile, "application/zip", b.zipName) }) { Text("Compartir") }
                TextButton(onClick = { vm.dismissBatch() }) { Text("Cerrar") }
            }
        },
        text = {
            Column {
                val done = b.lines.count { it.state != com.webmaletin.gtaviewer.export.BatchState.PROCESSING }
                Text("$done / ${b.total}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
                LinearProgressIndicator(progress = { if (b.total == 0) 0f else done.toFloat() / b.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Accent)
                if (b.finished && b.result != null) {
                    val r = b.result
                    Text("${r.total} seleccionados\n${r.okCount} correctos\n${r.errors.size} errores" + if (r.cancelled) "\n(cancelada por el usuario)" else "", fontWeight = FontWeight.SemiBold)
                    if (b.zipFile == null) Text("No se generó ZIP: ningún vehículo se exportó correctamente.", color = Bad, fontSize = 12.sp)
                    else Text("${b.zipName} contiene solo los ${r.okCount} ${b.format.name} correctos.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(b.lines.size) { i ->
                        val l = b.lines[i]
                        val (sym, col) = when (l.state) {
                            com.webmaletin.gtaviewer.export.BatchState.OK -> "✓ Exportado" to Good
                            com.webmaletin.gtaviewer.export.BatchState.WARNING -> "✓ Exportado con avisos" to Warn
                            com.webmaletin.gtaviewer.export.BatchState.ERROR -> "✕" to Bad
                            com.webmaletin.gtaviewer.export.BatchState.CANCELLED -> "— cancelado" to Warn
                            com.webmaletin.gtaviewer.export.BatchState.PROCESSING -> "⏳ Procesando..." to Accent
                        }
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text(l.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (l.state == com.webmaletin.gtaviewer.export.BatchState.ERROR) "✕ ${l.name} — ${l.message}" else sym + (l.message?.let { " ($it)" } ?: ""), color = col, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
    )
}
