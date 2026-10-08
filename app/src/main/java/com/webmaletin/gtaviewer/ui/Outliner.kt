package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.catalog.WorkflowStatus
import com.webmaletin.gtaviewer.model.*

private fun TriState.toToggle() = when (this) { TriState.ALL -> ToggleableState.On; TriState.NONE -> ToggleableState.Off; TriState.SOME -> ToggleableState.Indeterminate }

/** Una entrada de la lista aplanada del Outliner (para poder desplazarse hasta la fila seleccionada). */
private sealed class ORow {
    class Group(val g: OutlinerGroup) : ORow()
    class Part(val r: OutlinerRow, val indent: Boolean) : ORow()
    class Sub(val sm: SubMesh) : ORow()
    class Header(val text: String) : ORow()
}

/**
 * Outliner de partes (estilo Blender): casilla = visibilidad = exportación. La visibilidad vive en el EditSession,
 * así que visor, contadores y exportación leen la misma fuente de verdad.
 */
@Composable
fun OutlinerPanel(vm: AppViewModel, onExport: () -> Unit) {
    val model = vm.model ?: return
    val st = vm.editState
    val visible = vm.renderState.visible
    val canGroup = remember(model, st.categories) { Outliner.canGroup(model, vm::categoryOf) }
    val tree = remember(model, st.categories, vm.outlinerGrouped) { Outliner.build(model, vm::categoryOf, vm.outlinerGrouped) }
    val expanded = remember(model) { mutableStateMapOf<Int, Boolean>() }
    val collapsedCats = remember(model) { mutableStateMapOf<PartCategory, Boolean>() }

    val rows = remember(tree, expanded.toMap(), collapsedCats.toMap()) {
        buildList<ORow> {
            if (tree.grouped) {
                tree.groups.forEach { g ->
                    add(ORow.Group(g))
                    if (collapsedCats[g.category] != true) g.rows.forEach { r ->
                        add(ORow.Part(r, true))
                        if (expanded[r.part.id] == true) r.part.subMeshes.forEach { add(ORow.Sub(it)) }
                    }
                }
                if (tree.loose.isNotEmpty()) { add(ORow.Header("SIN CLASIFICAR")); tree.loose.forEach { r ->
                    add(ORow.Part(r, false)); if (expanded[r.part.id] == true) r.part.subMeshes.forEach { add(ORow.Sub(it)) }
                } }
            } else tree.loose.forEach { r ->
                add(ORow.Part(r, false)); if (expanded[r.part.id] == true) r.part.subMeshes.forEach { add(ORow.Sub(it)) }
            }
        }
    }

    // Seleccionar en el visor => la fila correspondiente se resalta y se desplaza a la vista.
    val listState = rememberLazyListState()
    LaunchedEffect(st.selection) {
        val first = st.selection.firstOrNull() ?: return@LaunchedEffect
        val part = model.partOf(first) ?: return@LaunchedEffect
        if (tree.grouped && collapsedCats[vm.categoryOf(part)] == true) collapsedCats[vm.categoryOf(part)] = false
        val idx = rows.indexOfFirst { it is ORow.Part && it.r.part.id == part.id }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("PARTES DEL VEHÍCULO", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)
        val c = Outliner.counters(model, visible)
        Text("Partes visibles: ${c.partsVisible} / ${c.partsTotal}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Meshes visibles: ${c.meshesVisible} / ${c.meshesTotal}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.currentItem?.let { it ->
            val s = vm.statusOf(it.id)
            Text("Estado: ${s.symbol} ${s.label}", fontSize = 12.sp, color = when (s) { WorkflowStatus.READY -> Good; WorkflowStatus.ERROR -> Bad; WorkflowStatus.EDITED -> Warn; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }

        // ---- botones del Outliner
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            @Composable fun b(t: String, enabled: Boolean = true, f: () -> Unit) = AssistChip(onClick = f, enabled = enabled, label = { Text(t, fontSize = 11.sp) })
            b("Mostrar todo") { vm.showEverything() }
            b("Ocultar todo") { vm.hideEverything() }
            b("Invertir") { vm.invertVisibility() }
            b("Restaurar original") { vm.restoreVisibility() }
            b("Seleccionar todos") { vm.selectAllParts() }
            b("Deseleccionar todos") { vm.clearSelection() }
            b("Solo selección", st.selection.isNotEmpty()) { vm.soloSelection() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(selected = vm.multiSelect, onClick = { vm.multiSelect = !vm.multiSelect }, label = { Text("Multi-selección", fontSize = 11.sp) })
            FilterChip(selected = tree.grouped, enabled = canGroup, onClick = { vm.outlinerGrouped = !vm.outlinerGrouped },
                label = { Text(if (canGroup) (if (tree.grouped) "Agrupado" else "Lista plana") else "Lista plana (sin jerarquía)", fontSize = 11.sp) })
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows.size, key = { i ->
                when (val r = rows[i]) { is ORow.Group -> "g-${r.g.category}"; is ORow.Part -> "p-${r.r.part.id}"; is ORow.Sub -> "s-${r.sm.id}"; is ORow.Header -> "h-${r.text}" }
            }) { i ->
                when (val r = rows[i]) {
                    is ORow.Header -> Text(r.text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
                    is ORow.Group -> GroupRow(vm, r.g, visible, collapsedCats[r.g.category] == true) { collapsedCats[r.g.category] = !(collapsedCats[r.g.category] ?: false) }
                    is ORow.Part -> PartRowUi(vm, model, r.r, r.indent, visible, st.selection, expanded[r.r.part.id] == true) { expanded[r.r.part.id] = !(expanded[r.r.part.id] ?: false) }
                    is ORow.Sub -> SubRowUi(vm, r.sm, visible, st.selection)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (st.selection.isNotEmpty()) { SelectionInfo(vm, model); Spacer(Modifier.height(4.dp)) }

        // ---- acciones del flujo de preparación
        Button(onClick = { vm.prepareForGame() }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            Text("PREPARAR PARA MI JUEGO", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        val ready = vm.currentItem?.let { vm.statusOf(it.id) == WorkflowStatus.READY } == true
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.markReady() }, colors = ButtonDefaults.buttonColors(containerColor = if (ready) Good else PanelHi, contentColor = if (ready) Color.Black else Color.White),
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(if (ready) "✓ LISTO" else "MARCAR COMO LISTO", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Button(onClick = onExport, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("EXPORTAR ESTE VEHÍCULO", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2) }
        }
        OutlinedButton(onClick = { vm.resetVehicle() }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
            Text("RESTABLECER VEHÍCULO", fontSize = 11.sp)
        }
    }
}

@Composable
private fun GroupRow(vm: AppViewModel, g: OutlinerGroup, visible: Set<Int>, collapsed: Boolean, onCollapse: () -> Unit) {
    val ids = g.rows.flatMap { r -> r.part.subMeshes.map { it.id } }
    val tri = Outliner.triState(ids, visible)
    Row(Modifier.fillMaxWidth().clickable { vm.select(ids.toSet()) }, verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(state = tri.toToggle(), onClick = { vm.setVisible(ids.toSet(), tri == TriState.NONE || tri == TriState.SOME) })
        Text(g.category.label.uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("${g.rows.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onCollapse, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (collapsed) "▸" else "▾") }
    }
}

@Composable
private fun PartRowUi(vm: AppViewModel, model: VehicleModel, r: OutlinerRow, indent: Boolean, visible: Set<Int>, selection: Set<Int>, expanded: Boolean, onExpand: () -> Unit) {
    val ids = r.part.subMeshes.map { it.id }
    val tri = Outliner.triState(ids, visible)
    val selected = ids.any { it in selection }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(if (selected) Color(0x44FF9F1C) else Color.Transparent)
            .clickable { if (vm.multiSelect) vm.toggleSelect(ids.toSet()) else vm.select(ids.toSet()) }
            .padding(start = if (indent) 14.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = tri.toToggle(), onClick = { vm.setVisible(ids.toSet(), tri != TriState.ALL) })
        Column(Modifier.weight(1f)) {
            Text(r.displayName, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (tri == TriState.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val proposed = if (!r.part.recognized && r.category != PartCategory.OTHER) " · propuesto" else ""
            val manual = if (vm.editState.categories.containsKey(r.part.id)) " · manual" else ""
            Text("${if (r.displayName != r.part.name) r.part.name + " · " else ""}${r.category.label}$proposed$manual · ${r.part.subMeshes.size} mesh · ${r.part.subMeshes.sumOf { it.triangleCount }} tri" +
                (r.part.variantNote?.let { " · $it" } ?: "") + (r.part.extraSource?.let { " · pieza" } ?: ""),
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.part.subMeshes.size > 1) TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (expanded) "▾" else "▸") }
        Box {
            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text("Reclasificar como:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                PartCategory.values().forEach { c ->
                    DropdownMenuItem(text = { Text(c.label + if (c == r.category) "  ✓" else "") }, onClick = { vm.setCategory(r.part.id, c); menu = false })
                }
            }
        }
    }
}

@Composable
private fun SubRowUi(vm: AppViewModel, sm: SubMesh, visible: Set<Int>, selection: Set<Int>) {
    val selected = sm.id in selection
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0x33FF9F1C) else Color.Transparent).clickable { vm.select(setOf(sm.id)) }.padding(start = 44.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = sm.id in visible, onCheckedChange = { vm.setVisible(setOf(sm.id), it) })
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or ((sm.material.r shl 16) or (sm.material.g shl 8) or sm.material.b).toLong())))
        Spacer(Modifier.width(6.dp))
        Text("${sm.material.name} · ${sm.triangleCount} tri", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
