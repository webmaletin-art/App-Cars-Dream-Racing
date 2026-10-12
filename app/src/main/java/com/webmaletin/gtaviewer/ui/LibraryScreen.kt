package com.webmaletin.gtaviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.classify.VehicleTypes
import com.webmaletin.gtaviewer.library.PieceMeta
import com.webmaletin.gtaviewer.library.SortKey

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(app: AppViewModel, lab: LabViewModel) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> lab.importZips(uris) }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u -> if (u != null) lab.exportBackup(u) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) lab.importBackup(u) }
    val items = lab.items()
    var detail by remember { mutableStateOf<PieceMeta?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf<PieceMeta?>(null) }
    val sel = lab.libSel
    val (nv, np) = lab.counts()

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader("CATÁLOGO", "$nv vehículos · $np piezas · se guarda y reabre tras reiniciar", onBack = { app.back() }) {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Categorías propias") }, onClick = { menu = false; showCats = true })
                    DropdownMenuItem(text = { Text("Exportar copia de seguridad") }, onClick = { menu = false; backupOut.launch("GTA_CAR_LAB_catalogo.zip") })
                    DropdownMenuItem(text = { Text("Importar copia de seguridad") }, onClick = { menu = false; backupIn.launch(arrayOf("*/*")) })
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) { Text("IMPORTAR ZIP", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                OutlinedButton(onClick = { app.navigate(Screen.HOME) }, modifier = Modifier.weight(1f)) { Text("DESDE EL VISOR", fontSize = 12.sp) }
            }
            OutlinedTextField(value = lab.qText, onValueChange = { lab.qText = it }, singleLine = true, label = { Text("Buscar (nombre, origen, categoría, tipo…)") }, modifier = Modifier.fillMaxWidth())
            ChipRow(listOf("VEHICLE" to "Vehículos", "COMPONENT" to "Piezas"), lab.qKind?.let { k -> if (k == "VEHICLE") "VEHICLE" to "Vehículos" else "COMPONENT" to "Piezas" }, { it.second }, allLabel = "Todo") { lab.qKind = it?.first; lab.qCategory = null }
            if (lab.qKind != "VEHICLE") ChipRow(lab.categories(), lab.qCategory, { lab.categoryLabel(it) }, "Todas las categorías") { lab.qCategory = it }
            if (lab.qKind != "COMPONENT") ChipRow(VehicleTypes.ALL, lab.qType, { it }, "Todos los tipos") { lab.qType = it }
            val sources = lab.sources()
            if (sources.size > 1) ChipRow(sources, lab.qSource, { it }, "Todos los orígenes") { lab.qSource = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = lab.qFav, onClick = { lab.qFav = !lab.qFav }, label = { Text("★ Favoritos", fontSize = 12.sp) })
                Text("Orden:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SortKey.values().forEach { k -> FilterChip(selected = lab.qSort == k, onClick = { lab.qSort = k }, label = { Text(k.label, fontSize = 11.sp) }) }
                FilterChip(selected = lab.qDesc, onClick = { lab.qDesc = !lab.qDesc }, label = { Text(if (lab.qDesc) "↓ desc." else "↑ asc.", fontSize = 11.sp) })
            }
        }
        if (sel.isNotEmpty()) SelectionBar(lab, app, items, onDelete = { confirmDelete = true }, onNewProject = { newProject = it })
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (nv + np == 0) "El catálogo está vacío.\nImporte un ZIP de vehículos: se guardan una sola vez, sin duplicar." else "Ningún elemento coincide con los filtros.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(30.dp))
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(items, key = { it.id }) { m ->
                    val selected = m.id in sel
                    Card(colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF3A2A00) else Panel), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { if (sel.isNotEmpty()) lab.libSel = if (selected) sel - m.id else sel + m.id else detail = m }, onLongClick = { lab.libSel = if (selected) sel - m.id else sel + m.id })) {
                        Column(Modifier.padding(8.dp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Thumb(lab, m.id, 110)
                                Text(if (m.favorite) "★" else "☆", color = if (m.favorite) Accent else Color.Gray, fontSize = 20.sp, modifier = Modifier.align(Alignment.TopEnd).combinedClickable(onClick = { lab.favorite(m.id, !m.favorite) }))
                                if (selected) Text("✔", color = Accent, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopStart))
                            }
                            Text(m.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text("${"%.2f".format(m.dims.l)} × ${"%.2f".format(m.dims.w)} × ${"%.2f".format(m.dims.h)} m", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (m.kind == "VEHICLE") (m.types.firstOrNull()?.let { "${it.label} ${(it.confidence * 100).toInt()}%" } ?: "sin clasificar") else lab.categoryLabel(m.category), fontSize = 10.sp, color = Accent, maxLines = 1)
                            Text(m.source, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    detail?.let { d -> DetailDialog(lab, app, d, onDismiss = { detail = null }, onNewProject = { newProject = d; detail = null }) }
    if (confirmDelete) {
        var cascade by remember { mutableStateOf(false) }
        val hasVeh = sel.any { lab.store.get(it)?.kind == "VEHICLE" }
        ConfirmDialog("Eliminar del catálogo", "Se eliminarán ${sel.size} elementos del catálogo de la app. Los ZIP/DFF/TXD originales NO se tocan, pero esto no se puede deshacer (puede reimportarlos).", "ELIMINAR", danger = true,
            extra = if (hasVeh) ({ Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) { Checkbox(cascade, { cascade = it }); Text("También eliminar las piezas obtenidas de esos vehículos", fontSize = 12.sp) } }) else null,
            onConfirm = { lab.delete(sel, cascade) }, onDismiss = { confirmDelete = false })
    }
    if (showCats) CategoriesDialog(lab) { showCats = false }
    newProject?.let { base ->
        TextPromptDialog("Nuevo proyecto sobre «${base.name}»", "Proyecto ${base.name}", onOk = { n -> lab.newProject(n, if (base.kind == "VEHICLE") base.id else null) { app.navigate(Screen.WORKSHOP) } }, onDismiss = { newProject = null })
    }
}

@Composable
private fun SelectionBar(lab: LabViewModel, app: AppViewModel, items: List<PieceMeta>, onDelete: () -> Unit, onNewProject: (PieceMeta) -> Unit) {
    val sel = lab.libSel
    var catMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color(0xFF2A2210)).horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${sel.size} elegidos", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        OutlinedButton(onClick = { lab.libSel = items.map { it.id }.toSet() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Todos", fontSize = 12.sp) }
        OutlinedButton(onClick = { lab.libSel = emptySet() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Ninguno", fontSize = 12.sp) }
        Box {
            OutlinedButton(onClick = { catMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Categoría…", fontSize = 12.sp) }
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                lab.categories().forEach { c -> DropdownMenuItem(text = { Text(lab.categoryLabel(c)) }, onClick = { catMenu = false; sel.forEach { lab.setCategory(it, c) }; lab.libSel = emptySet() }) }
            }
        }
        val one = sel.singleOrNull()?.let { lab.store.get(it) }
        if (one != null && one.kind == "VEHICLE") Button(onClick = { onNewProject(one) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Proyecto con esta base", fontSize = 12.sp) }
        if (one != null && one.kind == "COMPONENT" && lab.hasProject) Button(onClick = { lab.addToCurrentProject(one.id); app.navigate(Screen.WORKSHOP); lab.libSel = emptySet() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Añadir al proyecto abierto", fontSize = 12.sp) }
        OutlinedButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Eliminar", fontSize = 12.sp, color = Bad) }
    }
}

@Composable
fun ImportDialog(lab: LabViewModel, ui: com.webmaletin.gtaviewer.ImportUi) {
    AlertDialog(onDismissRequest = { lab.dismissImport() }, containerColor = Panel, title = { Text(ui.text, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!ui.finished) {
                    if (ui.total > 0) LinearProgressIndicator(progress = { ui.done.toFloat() / ui.total }, modifier = Modifier.fillMaxWidth(), color = Accent) else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    if (ui.total > 0) Text("${ui.done} de ${ui.total}", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text(ui.summary ?: "", fontSize = 13.sp)
                    if (ui.errors.isNotEmpty()) { Text("Errores (no detienen la importación):", color = Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)); ui.errors.take(30).forEach { Text("• $it", fontSize = 11.sp) } }
                }
            }
        },
        confirmButton = { if (ui.finished) Button(onClick = { lab.dismissImport() }) { Text("ACEPTAR") } },
        dismissButton = { if (!ui.finished) TextButton(onClick = { lab.cancelImport() }) { Text("CANCELAR") } })
}

@Composable
private fun CategoriesDialog(lab: LabViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text("Categorías propias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nueva categoría") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp)); Button(onClick = { lab.addCategory(name); name = "" }, enabled = name.isNotBlank()) { Text("＋") }
                }
                lab.store.customCategories().forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(c, Modifier.weight(1f)); TextButton(onClick = { lab.removeCategory(c) }) { Text("Quitar", color = Bad) } }
                }
                if (lab.store.customCategories().isEmpty()) Text("Aún no hay categorías propias. Asígnelas a piezas desde la selección (Categoría…).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("LISTO") } })
}

@Composable
private fun DetailDialog(lab: LabViewModel, app: AppViewModel, m: PieceMeta, onDismiss: () -> Unit, onNewProject: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf(false) }
    var notes by remember(m.id) { mutableStateOf(m.notes) }
    val cur = lab.store.get(m.id) ?: m
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text(cur.name, fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Thumb(lab, cur.id, 150) }
                KeyValue("Tipo", if (cur.kind == "VEHICLE") "Vehículo" else "Pieza · ${lab.categoryLabel(cur.category)}")
                KeyValue("Medidas", "${"%.3f".format(cur.dims.l)} (largo) × ${"%.3f".format(cur.dims.w)} (ancho) × ${"%.3f".format(cur.dims.h)} (alto) m")
                KeyValue("Geometría", "${cur.triangles} triángulos · ${cur.vertices} vértices")
                KeyValue("Origen", "${cur.source} · ${cur.origin}")
                KeyValue("Huella", "geometría ${cur.dffHash.take(10)}" + (cur.txdHash?.let { " · textura ${it.take(10)}" } ?: ""))
                if (cur.materials.isNotEmpty()) KeyValue("Materiales", cur.materials.take(12).joinToString())
                if (cur.textures.isNotEmpty()) KeyValue("Texturas", cur.textures.take(12).joinToString())
                if (cur.kind == "VEHICLE") {
                    KeyValue("Ruedas", "${cur.wheelCount}")
                    KeyValue("Clasificación", if (cur.types.isEmpty()) "sin clasificar" else cur.types.joinToString("\n") { "${it.label} — ${(it.confidence * 100).toInt()}% (${it.source})" })
                    if (cur.typesCorrected) Text("✔ Corregido por usted: se aprende para vehículos similares", fontSize = 11.sp, color = Good)
                    OutlinedButton(onClick = { correcting = true }) { Text("CORREGIR TIPO", fontSize = 12.sp) }
                } else {
                    if (cur.side != null || cur.longitudinal != null) KeyValue("Posición", listOfNotNull(cur.side, cur.longitudinal).joinToString(" · "))
                    if (cur.alsoFoundIn.isNotEmpty()) KeyValue("También en", cur.alsoFoundIn.take(6).joinToString())
                    cur.similarTo?.let { sid -> lab.store.get(sid)?.let { KeyValue("Parecida a", it.name) } }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notas") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cur.kind == "VEHICLE") Button(onClick = onNewProject, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("USAR COMO BASE", fontSize = 12.sp) }
                else if (lab.hasProject) Button(onClick = { lab.addToCurrentProject(cur.id); app.navigate(Screen.WORKSHOP); onDismiss() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("AÑADIR AL PROYECTO", fontSize = 12.sp) }
                TextButton(onClick = { lab.notes(cur.id, notes); onDismiss() }) { Text("CERRAR") }
            }
        },
        dismissButton = { TextButton(onClick = { rename = true }) { Text("RENOMBRAR") } })
    if (rename) TextPromptDialog("Renombrar", cur.name, onOk = { lab.rename(cur.id, it) }, onDismiss = { rename = false })
    if (correcting) {
        var chosen by remember { mutableStateOf(cur.types.take(2).map { it.label }.toSet()) }
        AlertDialog(onDismissRequest = { correcting = false }, containerColor = Panel, title = { Text("Corregir tipo") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Elija una o varias etiquetas. Se guardan y el clasificador las usa para vehículos parecidos.", fontSize = 12.sp)
                    VehicleTypes.ALL.forEach { t -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(t in chosen, { chosen = if (it) chosen + t else chosen - t }); Text(t, fontSize = 13.sp) } }
                }
            },
            confirmButton = { Button(onClick = { lab.correctTypes(cur.id, VehicleTypes.ALL.filter { it in chosen }); correcting = false }, enabled = chosen.isNotEmpty()) { Text("GUARDAR") } },
            dismissButton = { TextButton(onClick = { correcting = false }) { Text("CANCELAR") } })
    }
}
