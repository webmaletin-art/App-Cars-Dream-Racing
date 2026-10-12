package com.webmaletin.gtaviewer.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.WsSnapshot
import com.webmaletin.gtaviewer.export.ExportFormat
import com.webmaletin.gtaviewer.export.ExportProfile
import com.webmaletin.gtaviewer.gl.GlViewerView
import com.webmaletin.gtaviewer.model.PartCategory
import com.webmaletin.gtaviewer.project.MatDef
import com.webmaletin.gtaviewer.project.PPart
import com.webmaletin.gtaviewer.project.Trs

private fun originLabel(p: PPart) = when (p.origin) { "base" -> "carrocería base"; "library" -> "del catálogo"; "edited" -> "editada"; "copy" -> "copia"; else -> p.origin }

// ================================================================================================ PIEZAS (outliner)

@Composable
fun PartsPanel(lab: LabViewModel, snap: WsSnapshot, gl: GlViewerView?) {
    var showDeleted by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PPart?>(null) }
    var confirmDel by remember { mutableStateOf<List<Int>?>(null) }
    val parts = snap.state.parts.filter { showDeleted || !it.deleted }.sortedWith(compareBy({ PartCategory.fromName(it.category)?.order ?: 99 }, { it.name }))
    val active = snap.state.parts.filter { !it.deleted }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Visibles ${active.count { it.visible }}/${active.size} · se exportan ${active.count { it.export }}", fontSize = 11.sp, color = Accent)
            AssistChip(onClick = { lab.setVisible(active.map { it.id }, true) }, label = { Text("Mostrar todo", fontSize = 11.sp) })
            AssistChip(onClick = { lab.setVisible(active.map { it.id }, false) }, label = { Text("Ocultar todo", fontSize = 11.sp) })
            FilterChip(selected = showDeleted, onClick = { showDeleted = !showDeleted }, label = { Text("Ver eliminadas", fontSize = 11.sp) })
            AssistChip(onClick = { showSummary = true }, label = { Text("Resumen de exportación", fontSize = 11.sp) })
        }
        Text("👁 = verlo en pantalla · EXP = incluirlo al exportar (son cosas distintas) · 🔒 = bloquear", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(parts, key = { it.id }) { p ->
                val sel = p.id in lab.selection
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().background(if (sel) Color(0xFF3A2A00) else Color.Transparent).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { lab.setVisible(listOf(p.id), !p.visible) }, enabled = !p.deleted, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.widthIn(min = 38.dp)) { Text(if (p.visible) "👁" else "🚫", fontSize = 16.sp) }
                    Column(Modifier.weight(1f).clickable { if (p.deleted) {} else if (lab.multiSelect) lab.toggleSelect(p.id) else lab.select(setOf(p.id)) }) {
                        Text(p.name, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (p.deleted) Color.Gray else Color.Unspecified)
                        Text("${PartCategory.fromName(p.category)?.label ?: p.category} · ${originLabel(p)}${if (p.deleted) " · ELIMINADA" else ""}${if (!p.trs.isIdentity) " · movida" else ""}${p.note?.let { " · $it" } ?: ""}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("EXP", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Checkbox(checked = p.export, onCheckedChange = { lab.setExport(listOf(p.id), it) }, enabled = !p.deleted)
                    TextButton(onClick = { lab.setLocked(listOf(p.id), !p.locked) }, enabled = !p.deleted, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.widthIn(min = 34.dp)) { Text(if (p.locked) "🔒" else "🔓", fontSize = 15.sp) }
                    Box {
                        TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.widthIn(min = 30.dp)) { Text("⋮", fontSize = 18.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (p.deleted) DropdownMenuItem(text = { Text("Recuperar") }, onClick = { menu = false; lab.restoreParts(listOf(p.id)) })
                            else {
                                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; renaming = p })
                                DropdownMenuItem(text = { Text("Duplicar") }, onClick = { menu = false; lab.duplicatePart(p.id) })
                                DropdownMenuItem(text = { Text("Copia en espejo (izq.↔der.)") }, onClick = { menu = false; lab.mirrorPart(p.id) })
                                DropdownMenuItem(text = { Text("Enfocar cámara") }, onClick = { menu = false; lab.select(setOf(p.id)); lab.selectionBounds()?.let { gl?.frame(it) } })
                                DropdownMenuItem(text = { Text("Solo esta (ocultar el resto)") }, onClick = { menu = false; lab.setVisible(active.map { it.id }, false); lab.setVisible(listOf(p.id), true) })
                                DropdownMenuItem(text = { Text("Eliminar…") }, onClick = { menu = false; confirmDel = listOf(p.id) })
                            }
                        }
                    }
                }
            }
        }
        if (lab.selection.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${lab.selection.size} seleccionadas:", fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { lab.setVisible(lab.selection, false) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ocultar", fontSize = 11.sp) }
            OutlinedButton(onClick = { lab.setExport(lab.selection, false) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("No exportar", fontSize = 11.sp) }
            OutlinedButton(onClick = { confirmDel = lab.selection.toList() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Eliminar", fontSize = 11.sp, color = Bad) }
        }
    }
    renaming?.let { r -> TextPromptDialog("Renombrar pieza", r.name, onOk = { lab.renamePart(r.id, it) }, onDismiss = { renaming = null }) }
    confirmDel?.let { ids -> ConfirmDialog("Eliminar ${ids.size} pieza(s)", "Se quitan del proyecto (y de la exportación). No se borra nada del catálogo y puede recuperarlas con «Ver eliminadas» o con Deshacer.", "ELIMINAR", danger = true, onConfirm = { lab.deleteParts(ids) }, onDismiss = { confirmDel = null }) }
    if (showSummary) {
        val s = snap.summary
        AlertDialog(onDismissRequest = { showSummary = false }, containerColor = Panel, title = { Text("Resumen de exportación") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("INCLUIDAS (${s.included.size})", color = Good, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    s.included.forEach { Text("• $it${if (it in s.hiddenButIncluded) "  (oculta en pantalla, SÍ se exporta)" else ""}", fontSize = 12.sp) }
                    Text("OMITIDAS (${s.omitted.size})", color = Warn, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    s.omitted.forEach { Text("• ${it.first} — ${it.second}", fontSize = 12.sp) }
                }
            }, confirmButton = { Button(onClick = { showSummary = false }) { Text("CERRAR") } })
    }
}

// ================================================================================================ AÑADIR (catálogo + compatibilidad)

@Composable
fun AddPanel(lab: LabViewModel, snap: WsSnapshot) {
    val baseId = snap.state.baseVehicleId
    var picking by remember { mutableStateOf(baseId == null) }
    LaunchedEffect(lab.candCategory, lab.candText, lab.candOnlyFits, baseId, lab.libRev) { lab.refreshCandidates() }
    if (picking) {
        val vehicles = remember(lab.libRev) { lab.store.vehicles().sortedBy { it.name.lowercase() } }
        Column {
            Text("Elija la CARROCERÍA BASE (queda fija: es la referencia y nunca se deforma sola).", fontSize = 12.sp)
            if (vehicles.isEmpty()) Text("El catálogo no tiene vehículos: importe ZIP en CATÁLOGO.", color = Warn, fontSize = 12.sp)
            LazyColumn {
                items(vehicles, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().clickable { lab.setBase(v.id); picking = false }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(lab, v.id, 48); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(v.name, fontSize = 13.sp, maxLines = 1); Text(v.types.firstOrNull()?.label ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        return
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Base: ${baseId?.let { lab.store.get(it)?.name } ?: "—"}", fontSize = 11.sp, color = Accent, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { picking = true }) { Text("CAMBIAR BASE", fontSize = 11.sp) }
        }
        ChipRow(PartCategory.values().filter { it != PartCategory.OTHER } .map { it.name } + lab.store.customCategories(), lab.candCategory, { lab.categoryLabel(it) }, "Todas") { lab.candCategory = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = lab.candText, onValueChange = { lab.candText = it }, singleLine = true, label = { Text("Buscar pieza", fontSize = 10.sp) }, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
            Spacer(Modifier.width(6.dp)); FilterChip(selected = lab.candOnlyFits, onClick = { lab.candOnlyFits = !lab.candOnlyFits }, label = { Text("Solo compatibles", fontSize = 11.sp) })
        }
        if (lab.candBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        if (lab.candidates.isEmpty() && !lab.candBusy) Text("No hay piezas con esos filtros.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(lab.candidates, key = { it.meta.id }) { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.clickable { expanded = if (expanded == c.meta.id) null else c.meta.id }, verticalAlignment = Alignment.CenterVertically) {
                        Thumb(lab, c.meta.id, 52); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.meta.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { LevelChip(c.result.level); if (c.result.learned) Text("aprendido", fontSize = 10.sp, color = Good) }
                            Text("${lab.categoryLabel(c.meta.category)} · ${"%.2f".format(c.meta.dims.l)}×${"%.2f".format(c.meta.dims.w)}×${"%.2f".format(c.meta.dims.h)} m · ${c.meta.source}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    if (expanded == c.meta.id) {
                        c.result.reasons.forEach { Text("• $it", fontSize = 11.sp, color = Color(0xFFC8CCD6)) }
                        if (c.result.replaces != null) Text("Reemplazaría: ${c.result.replaces}", fontSize = 11.sp, color = Accent)
                        if (c.result.level.rank == 0) Text("⚠ Marcada incompatible: puede probarla igualmente (se avisa).", fontSize = 11.sp, color = Warn)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { lab.addPiece(c, true) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (c.meta.category == PartCategory.WHEEL.name) "PONER JUEGO DE RUEDAS" else "AÑADIR AJUSTADA", fontSize = 11.sp) }
                            if (c.meta.category != PartCategory.WHEEL.name) {
                                OutlinedButton(onClick = { lab.addPiece(c, false) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("A PRUEBA (sin ajuste)", fontSize = 11.sp) }
                                OutlinedButton(onClick = { lab.replaceSelected(c, true) }, enabled = lab.selection.size == 1, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("SUSTITUIR SELECCIONADA", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF2A303C))
            }
        }
    }
}

// ================================================================================================ MOVER

@Composable
fun MovePanel(lab: LabViewModel, snap: WsSnapshot) {
    val sel = lab.selection.toList()
    if (sel.isEmpty()) { Text("Seleccione una pieza (tóquela en el visor o en PIEZAS).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val parts = sel.mapNotNull { lab.partOf(it) }
    val one = parts.singleOrNull()
    val locked = parts.any { it.locked }
    var step by remember { mutableStateOf(0.05f) }
    var rstep by remember { mutableStateOf(5f) }
    val t = one?.trs ?: Trs()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (one != null) one.name else "${parts.size} piezas (los botones mueven todas a la vez)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (locked) InfoBox("🔒 Bloqueada: desbloquéela (en PIEZAS) para moverla. La carrocería base viene bloqueada a propósito: es la referencia.", Color(0xFF3A2A00))
        Text("Posición (m) · ejes del juego: X lateral, Y longitudinal, Z altura", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0.01f, 0.05f, 0.1f, 0.5f).forEach { s -> FilterChip(selected = step == s, onClick = { step = s }, label = { Text("$s m", fontSize = 11.sp) }) } }
        listOf("X" to 0, "Y" to 1, "Z" to 2).forEach { (n, i) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { lab.nudge(sel, FloatArray(3) { if (it == i) -step else 0f }) }, enabled = !locked, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("−") }
                NumField("Pos $n", t.pos[i], Modifier.weight(1f).padding(horizontal = 4.dp), enabled = one != null && !locked) { v -> one?.let { lab.setTrs(it.id, t.copy(pos = t.pos.toMutableList().also { l -> l[i] = v })) } }
                OutlinedButton(onClick = { lab.nudge(sel, FloatArray(3) { if (it == i) step else 0f }) }, enabled = !locked, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("＋") }
            }
        }
        Text("Rotación (grados)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1f, 5f, 15f, 45f, 90f).forEach { s -> FilterChip(selected = rstep == s, onClick = { rstep = s }, label = { Text("${s.toInt()}°", fontSize = 11.sp) }) } }
        listOf("X" to 0, "Y" to 1, "Z" to 2).forEach { (n, i) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { lab.nudge(sel, dRot = FloatArray(3) { if (it == i) -rstep else 0f }) }, enabled = !locked, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("−") }
                NumField("Rot $n °", t.rot[i], Modifier.weight(1f).padding(horizontal = 4.dp), enabled = one != null && !locked, decimals = 1) { v -> one?.let { lab.setTrs(it.id, t.copy(rot = t.rot.toMutableList().also { l -> l[i] = v })) } }
                OutlinedButton(onClick = { lab.nudge(sel, dRot = FloatArray(3) { if (it == i) rstep else 0f }) }, enabled = !locked, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("＋") }
            }
        }
        Text("Escala", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { lab.nudge(sel, scale = 0.95f) }, enabled = !locked, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−5 %") }
            Spacer(Modifier.width(6.dp))
            listOf("X" to 0, "Y" to 1, "Z" to 2).forEach { (n, i) -> NumField("Esc $n", t.scale[i], Modifier.weight(1f).padding(horizontal = 2.dp), enabled = one != null && !locked) { v -> one?.let { lab.setTrs(it.id, t.copy(scale = t.scale.toMutableList().also { l -> l[i] = v.coerceAtLeast(0.01f) })) } } }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { lab.nudge(sel, scale = 1.05f) }, enabled = !locked, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("＋5 %") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lab.resetTrs(sel) }, enabled = !locked) { Text("RESTABLECER", fontSize = 12.sp) }
            Button(onClick = { lab.dragMove = !lab.dragMove }, colors = if (lab.dragMove) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = PanelHi, contentColor = Color.White)) { Text(if (lab.dragMove) "MOVER CON EL DEDO: ACTIVO" else "MOVER CON EL DEDO", fontSize = 12.sp) }
        }
        if (lab.dragMove) Text("Con el modo activo, un dedo arrastra la pieza seleccionada en el plano de la pantalla; dos dedos giran/acercan la cámara.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ================================================================================================ MATERIAL

private val presets: List<Pair<String, (MatDef) -> MatDef>> = listOf(
    "Pintura metálica" to { m -> m.copy(metallic = 0.85f, roughness = 0.28f, glass = false, a = 255) },
    "Pintura mate" to { m -> m.copy(metallic = 0.05f, roughness = 0.9f, glass = false, a = 255) },
    "Cromo" to { m -> m.copy(r = 220, g = 222, b = 228, metallic = 1f, roughness = 0.08f, texMode = "NONE", glass = false, a = 255) },
    "Aluminio" to { m -> m.copy(r = 190, g = 194, b = 200, metallic = 0.9f, roughness = 0.35f, texMode = "NONE", glass = false, a = 255) },
    "Plástico negro" to { m -> m.copy(r = 24, g = 24, b = 26, metallic = 0f, roughness = 0.6f, texMode = "NONE", glass = false, a = 255) },
    "Goma" to { m -> m.copy(r = 18, g = 18, b = 18, metallic = 0f, roughness = 0.95f, texMode = "NONE", glass = false, a = 255) },
    "Vidrio" to { m -> m.copy(r = 150, g = 175, b = 190, a = 110, metallic = 0.1f, roughness = 0.05f, glass = true, texMode = "NONE") },
)

@Composable
fun MaterialPanel(lab: LabViewModel, snap: WsSnapshot) {
    val partId = lab.selection.singleOrNull()
    var naming by remember { mutableStateOf<Int?>(null) }
    var variantName by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("PINTURA DE LA CARROCERÍA")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Primario", fontSize = 11.sp); SwatchRow { lab.setPaint(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Secundario", fontSize = 11.sp); SwatchRow { lab.setPaint2(it) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { variantName = true }, label = { Text("Guardar variante", fontSize = 11.sp) })
            snap.state.variants.forEach { v -> AssistChip(onClick = { lab.applyVariant(v.name) }, label = { Text("▶ ${v.name}", fontSize = 11.sp) }) }
        }
        Text("Los materiales marcados «pintura» siguen este color; el vidrio, la goma y las luces NO (se editan aparte).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("MATERIAL DE LA PIEZA SELECCIONADA")
        if (partId == null) { Text("Seleccione UNA pieza.", fontSize = 12.sp); return@Column }
        val part = snap.model.parts.firstOrNull { it.id == partId } ?: return@Column
        var subIdx by remember(partId) { mutableStateOf(0) }
        if (subIdx >= part.subMeshes.size) subIdx = 0
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            part.subMeshes.forEachIndexed { i, sm -> FilterChip(selected = i == subIdx, onClick = { subIdx = i }, label = { Text(sm.material.textureName ?: sm.material.name, fontSize = 11.sp) }) }
        }
        val def = lab.materialDef(partId, subIdx)
        if (def == null) { Text("Sin material editable.", fontSize = 12.sp); return@Column }
        // los controles mantienen un valor local mientras se arrastran y confirman al soltar (un único paso de deshacer)
        var r by remember(def) { mutableStateOf(def.r.toFloat()) }; var g by remember(def) { mutableStateOf(def.g.toFloat()) }; var b by remember(def) { mutableStateOf(def.b.toFloat()) }
        var a by remember(def) { mutableStateOf(def.a.toFloat()) }; var me by remember(def) { mutableStateOf(def.metallic) }; var ro by remember(def) { mutableStateOf(def.roughness) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(Color(r.toInt(), g.toInt(), b.toInt()), RoundedCornerShape(6.dp))); Spacer(Modifier.width(8.dp))
            Text("${def.name}${if (def.glass) " · vidrio" else ""}${if (def.paintSlot != 0) " · pintura" else ""}", fontSize = 12.sp)
        }
        val commit = { lab.editMaterial(partId, subIdx) { it.copy(r = r.toInt(), g = g.toInt(), b = b.toInt(), a = a.toInt(), metallic = me, roughness = ro) } }
        LabeledSlider("Rojo", r, 0f..255f, r.toInt().toString(), { r = it }, { commit() })
        LabeledSlider("Verde", g, 0f..255f, g.toInt().toString(), { g = it }, { commit() })
        LabeledSlider("Azul", b, 0f..255f, b.toInt().toString(), { b = it }, { commit() })
        LabeledSlider("Opacidad", a, 0f..255f, "${(a / 2.55f).toInt()} %", { a = it }, { commit() })
        LabeledSlider("Metálico", me, 0f..1f, onChange = { me = it }, onDone = { commit() })
        LabeledSlider("Rugosidad", ro, 0f..1f, onChange = { ro = it }, onDone = { commit() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(def.glass, { v -> lab.editMaterial(partId, subIdx) { it.copy(glass = v, a = if (v && it.a == 255) 120 else it.a) } }); Spacer(Modifier.width(8.dp)); Text("Es vidrio (independiente de la pintura)", fontSize = 12.sp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Textura:", fontSize = 11.sp)
            FilterChip(selected = def.texMode == "ORIGINAL", onClick = { lab.editMaterial(partId, subIdx) { it.copy(texMode = "ORIGINAL") } }, label = { Text("Original", fontSize = 11.sp) })
            FilterChip(selected = def.texMode == "NONE", onClick = { lab.editMaterial(partId, subIdx) { it.copy(texMode = "NONE") } }, label = { Text("Ninguna (solo color)", fontSize = 11.sp) })
        }
        Text("Biblioteca de materiales:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (n, f) -> AssistChip(onClick = { lab.editMaterial(partId, subIdx, f) }, label = { Text(n, fontSize = 11.sp) }) }
        }
        if (snap.state.materials.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Del proyecto:", fontSize = 11.sp)
            snap.state.materials.forEach { m -> AssistChip(onClick = { lab.assignMaterial(partId, subIdx, m.id) }, label = { Text(m.name, fontSize = 11.sp) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lab.restoreMaterial(partId, subIdx) }) { Text("RESTAURAR ORIGINAL", fontSize = 11.sp) }
            OutlinedButton(onClick = { naming = subIdx }) { Text("GUARDAR COMO MATERIAL", fontSize = 11.sp) }
        }
    }
    naming?.let { si -> partId?.let { pid -> TextPromptDialog("Nombre del material", "Material nuevo", onOk = { lab.saveAsMaterial(pid, si, it) }, onDismiss = { naming = null }) } }
    if (variantName) TextPromptDialog("Nombre de la variante", "Variante ${snap.state.variants.size + 1}", onOk = { lab.saveVariant(it) }, onDismiss = { variantName = false })
}

// ================================================================================================ AJUSTAR Y FUSIONAR

@Composable
fun FusePanel(lab: LabViewModel, snap: WsSnapshot) {
    var weld by remember(snap.state.fusion) { mutableStateOf(snap.state.fusion.weldTolerance * 1000f) }
    var stitch by remember(snap.state.fusion) { mutableStateOf(snap.state.fusion.stitchTolerance * 100f) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Seleccionadas: ${lab.selection.size}. La carrocería base nunca se deforma sola.", fontSize = 12.sp)
        SectionTitle("1 · AJUSTAR")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { lab.fitSelected() }, enabled = lab.selection.size == 1) { Text("AJUSTAR A LA BASE", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.confirmFit() }, enabled = lab.selection.size == 1) { Text("GUARDAR AJUSTE", fontSize = 12.sp) }
        }
        Text("«Ajustar» aplica la escala y posición sugeridas por el motor de compatibilidad (medidas, zona y familia). «Guardar ajuste» lo recuerda para futuros proyectos con la misma base.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("2 · UNIR (tres niveles distintos)")
        InfoBox("ESCENA: las piezas ya conviven en el proyecto (no se toca la geometría).\nCOMBINAR: junta mallas en una sola pieza (siguen las costuras).\nFUSIONAR SUPERFICIES: suelda vértices coincidentes (misma posición y UV) y unifica normales. NO es una unión booleana: no recorta ni rellena volúmenes.", Color(0xFF1C2230))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lab.combineSelected() }, enabled = lab.selection.size >= 2) { Text("COMBINAR", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.fuseSelected() }, enabled = lab.selection.size >= 2) { Text("FUSIONAR SUPERFICIES", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.stitchSelected() }, enabled = lab.selection.size == 2) { Text("COSER BORDES (1.º = base)", fontSize = 12.sp) }
        }
        LabeledSlider("Tolerancia de soldadura", weld, 0.1f..5f, "${"%.1f".format(weld)} mm", { weld = it }, { lab.setFusionTolerance(weld / 1000f, stitch / 100f) })
        LabeledSlider("Tolerancia de cosido", stitch, 0.5f..10f, "${"%.1f".format(stitch)} cm", { stitch = it }, { lab.setFusionTolerance(weld / 1000f, stitch / 100f) })
        Text("Todo crea piezas nuevas o copias: las originales se conservan y puede deshacer.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("3 · REVISAR Y REPARAR")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lab.analyzeSelected() }, enabled = lab.selection.size == 1) { Text("ANALIZAR MALLA", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.repairSelected() }, enabled = lab.selection.size == 1) { Text("REPARAR (validado)", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.splitSelected() }, enabled = lab.selection.size == 1) { Text("SEPARAR COMPONENTES", fontSize = 12.sp) }
            OutlinedButton(onClick = { lab.restoreGeometrySelected() }, enabled = lab.selection.size == 1) { Text("RESTAURAR GEOMETRÍA", fontSize = 12.sp) }
        }
        lab.analysisText?.let { InfoBox(it) }
    }
}

// ================================================================================================ CARAS / LOGOS

@Composable
fun FacesPanel(lab: LabViewModel, snap: WsSnapshot) {
    var emblems by remember { mutableStateOf<List<String>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("QUITAR LOGOS / MARCAS")
        InfoBox("Opciones, de menos a más destructiva: 1) ocultar o excluir la pieza (PIEZAS) · 2) borrar caras (aquí, con vista previa y deshacer) · 3) limpiar la textura (EDITOR DE TEXTURAS, «Quitar logo»: rellena solo la zona con el entorno, no toda la textura) · 4) reemplazar el emblema por otra pieza o imagen.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(lab.pickFaces, { lab.pickFaces = it; if (!it) lab.clearFaces() }); Spacer(Modifier.width(8.dp)); Text("Modo CARAS: toque la pieza para elegir caras", fontSize = 12.sp)
        }
        if (lab.pickFaces) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabViewModel.FaceMode.values().forEach { m -> FilterChip(selected = lab.faceMode == m, onClick = { lab.faceMode = m }, label = { Text(m.label, fontSize = 11.sp) }) }
            }
            Text("${lab.faceSel.size} caras elegidas (rojo en el visor). Toque de nuevo una zona elegida para quitarla de la selección.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirm = true }, enabled = lab.faceSel.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Color.Black)) { Text("ELIMINAR CARAS", fontSize = 12.sp) }
                OutlinedButton(onClick = { lab.separateFaces() }, enabled = lab.faceSel.isNotEmpty()) { Text("SEPARAR EN PIEZA", fontSize = 12.sp) }
                OutlinedButton(onClick = { lab.clearFaces() }) { Text("LIMPIAR", fontSize = 12.sp) }
            }
        }
        SectionTitle("BUSCAR POR PALABRAS CLAVE")
        OutlinedButton(onClick = { emblems = lab.findEmblems() }) { Text("BUSCAR logo / emblema / badge…", fontSize = 12.sp) }
        emblems?.let { list ->
            if (list.isEmpty()) Text("No se encontró ninguna pieza ni material con esas palabras. No significa que no haya logos: pueden estar pintados en la textura (use el editor de texturas).", fontSize = 11.sp, color = Warn)
            else list.forEach { Text("• $it", fontSize = 12.sp) }
        }
        Text("La búsqueda solo mira nombres; no detecta logos por imagen.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirm) ConfirmDialog("Eliminar ${lab.faceSel.size} caras", "Se crea una versión editada de la pieza sin esas caras (UV, normales y materiales del resto intactos). La geometría original se conserva: puede deshacer o usar «Restaurar geometría».", "ELIMINAR", danger = true, onConfirm = { lab.deleteFaces() }, onDismiss = { confirm = false })
}

// ================================================================================================ EXPORTAR

@Composable
fun ExportPanel(lab: LabViewModel, snap: WsSnapshot) {
    val ctx = LocalContext.current
    var format by remember { mutableStateOf(ExportFormat.GLB) }
    var verify by remember { mutableStateOf(true) }
    var scope by remember { mutableStateOf("FULL") }
    val profiles = lab.savedProfiles()
    var profile by remember(snap.state.exportProfile) { mutableStateOf(profiles.firstOrNull { it == snap.state.exportProfile } ?: snap.state.exportProfile) }
    var saveName by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) lab.saveExportTo(u) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("QUÉ EXPORTAR")
        val scopes = listOf("FULL" to "Auto completo", "BODY" to "Solo carrocería", "NO_WHEELS" to "Sin ruedas", "NO_INTERIOR" to "Sin interior", "SELECTION" to "Selección", "SEPARATE" to "Componentes separados")
        ChipRow(scopes, scopes.first { it.first == scope }, { it.second }, allLabel = null) { scope = it?.first ?: "FULL" }
        Text("Solo se exporta lo marcado EXP en PIEZAS. Ocultar en pantalla no excluye.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("PERFIL (unidades, escala, orientación)")
        ChipRow(profiles, profile, { it.name }, allLabel = null) { it?.let { p -> profile = p; lab.setProfile(p) } }
        Text(profile.describe(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumField("Escala", profile.scale, Modifier.fillMaxWidth(), decimals = 4) { v -> if (v > 0f) { profile = profile.copy(scale = v); lab.setProfile(profile) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = profile.frontAxis == "MINUS_Z", onClick = { profile = profile.copy(frontAxis = if (profile.frontAxis == "MINUS_Z") "PLUS_Z" else "MINUS_Z"); lab.setProfile(profile) }, label = { Text("Frente −Z (Godot)", fontSize = 11.sp) })
            FilterChip(selected = profile.originMode == "GROUND_CENTER", onClick = { profile = profile.copy(originMode = if (profile.originMode == "GROUND_CENTER") "ORIGINAL" else "GROUND_CENTER"); lab.setProfile(profile) }, label = { Text("Origen en suelo/centro", fontSize = 11.sp) })
            FilterChip(selected = profile.combineMeshes, onClick = { profile = profile.copy(combineMeshes = !profile.combineMeshes); lab.setProfile(profile) }, label = { Text("Malla combinada", fontSize = 11.sp) })
            FilterChip(selected = profile.keepWheelsSeparate, onClick = { profile = profile.copy(keepWheelsSeparate = !profile.keepWheelsSeparate); lab.setProfile(profile) }, label = { Text("Ruedas independientes", fontSize = 11.sp) })
            AssistChip(onClick = { saveName = true }, label = { Text("Guardar perfil…", fontSize = 11.sp) })
        }
        SectionTitle("FORMATO")
        ChipRow(ExportFormat.values().toList(), format, { it.label }, allLabel = null) { format = it ?: ExportFormat.GLB }
        if (format == ExportFormat.GLB) Row(verticalAlignment = Alignment.CenterVertically) { Switch(verify, { verify = it }); Spacer(Modifier.width(8.dp)); Text("Re-importar y verificar el GLB después", fontSize = 12.sp) }
        Button(onClick = { lab.exportProject(format, LabViewModel.ExportOptions(scope, profile), verify) }, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("EXPORTAR ${format.label}", fontWeight = FontWeight.Bold) }
        Text("Incluidas: ${snap.summary.included.size} · Omitidas: ${snap.summary.omitted.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (saveName) TextPromptDialog("Nombre del perfil", profile.name + " (mío)", onOk = { lab.saveProfile(profile.copy(name = it)) }, onDismiss = { saveName = false })
    lab.exported?.let { e ->
        AlertDialog(onDismissRequest = { lab.exported = null }, containerColor = Panel, title = { Text("Exportado: ${e.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${e.file.length() / 1024} KB", fontSize = 12.sp)
                    e.verification?.let { InfoBox("VERIFICACIÓN (re-importado del archivo):\n$it", if (it.contains("✕")) Color(0xFF3A1A1A) else Color(0xFF14301F)) }
                    e.limitations?.let { InfoBox("Limitaciones del formato:\n$it", Color(0xFF3A2A00)) }
                    e.warnings.forEach { Text("⚠ $it", fontSize = 11.sp, color = Warn) }
                }
            },
            confirmButton = { Button(onClick = { save.launch(e.name) }) { Text("GUARDAR…") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", e.file)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = e.mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir ${e.name}"))
                    }) { Text("COMPARTIR") }
                    TextButton(onClick = { lab.exported = null }) { Text("CERRAR") }
                }
            })
    }
}
