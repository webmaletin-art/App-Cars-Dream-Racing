package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.EditTab
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.selection.PickMode
import com.webmaletin.gtaviewer.selection.SelectionTools

@Composable
fun EditPanel(vm: AppViewModel, onExport: () -> Unit) {
    val model = vm.model ?: return
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.46f).dp
    Surface(color = Color(0xF2181C23), modifier = Modifier.fillMaxWidth().heightIn(max = maxH)) {
        Column(Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = vm.editTab.ordinal, containerColor = Color.Transparent, contentColor = Accent) {
                listOf("Acciones" to EditTab.ACTIONS, "Materiales" to EditTab.MATERIALS).forEach { (t, tab) ->
                    Tab(selected = vm.editTab == tab, onClick = { vm.editTab = tab }, text = { Text(t, fontSize = 13.sp) })
                }
            }
            when (vm.editTab) {
                EditTab.ACTIONS -> ActionsTab(vm, model)
                EditTab.MATERIALS -> MaterialsTab(vm, model)
            }
        }
    }
}

@Composable
fun SelectionInfo(vm: AppViewModel, model: VehicleModel) {
    val sel = vm.editState.selection
    if (sel.isEmpty()) {
        Text("Toque una pieza del modelo para seleccionarla (o use el Árbol / categorías).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val subs = sel.mapNotNull { model.subMesh(it) }
    val parts = subs.mapNotNull { model.partOf(it.id) }.distinctBy { it.id }
    val title = if (parts.size == 1) parts[0].name else "${parts.size} piezas · ${subs.size} sub-mesh"
    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Accent)
    if (parts.size == 1 && parts[0].wheelSlot != null) Text(parts[0].wheelSlot!!.label, fontSize = 12.sp)
    val mats = subs.map { it.material }.distinctBy { it.key }
    val matTxt = if (mats.size == 1) mats[0].name else "${mats.size} materiales"
    val texTxt = mats.mapNotNull { it.textureName }.distinct().let { if (it.isEmpty()) "—" else if (it.size == 1) (if (mats[0].textureMissing) "${it[0]} (faltante)" else it[0]) else "${it.size} texturas" }
    val verts = subs.map { it.geometry.id to it }.distinctBy { it.second.id }.sumOf { it.second.vertexCount }
    Text("Mesh: ${if (parts.size == 1) parts[0].name else "varios"}   ·   Material: $matTxt   ·   Textura: $texTxt", fontSize = 12.sp)
    Text("Vértices: $verts   ·   Triángulos: ${subs.sumOf { it.triangleCount }}", fontSize = 12.sp)
}

@Composable
private fun ActionsTab(vm: AppViewModel, model: VehicleModel) {
    val hasSel = vm.editState.selection.isNotEmpty()
    Column(Modifier.verticalScrollCompat().padding(horizontal = 12.dp, vertical = 8.dp)) {
        SelectionInfo(vm, model)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PickMode.values().forEach { m ->
                FilterChip(selected = vm.pickMode == m, onClick = { vm.choosePickMode(m) }, label = { Text(m.label, fontSize = 12.sp) })
            }
            FilterChip(selected = vm.multiSelect, onClick = { vm.multiSelect = !vm.multiSelect }, label = { Text("Multi-selección", fontSize = 12.sp) })
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.hide() }, enabled = hasSel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("OCULTAR", fontSize = 12.sp) }
            Button(onClick = { vm.delete() }, enabled = hasSel, colors = ButtonDefaults.buttonColors(containerColor = Bad), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("ELIMINAR", fontSize = 12.sp) }
            Button(onClick = { vm.isolate() }, enabled = hasSel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("AISLAR", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.isolate() }, enabled = hasSel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("SOLO ESTA", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.selectSimilar() }, enabled = hasSel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("SELECC. PARECIDAS", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.clearSelection() }, enabled = hasSel, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("DESELEC.", fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.undo() }, enabled = vm.canUndo, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("DESHACER", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.redo() }, enabled = vm.canRedo, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("REHACER", fontSize = 11.sp) }
            OutlinedButton(onClick = { vm.showAll() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("MOSTRAR TODO", fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.restoreDeleted() }, enabled = vm.editState.deleted.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("DESHACER ELIMINACIÓN (${vm.editState.deleted.size})", fontSize = 10.sp, maxLines = 2) }
            OutlinedButton(onClick = { vm.resetVehicle() }, enabled = vm.isEdited, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("RESTABLECER", fontSize = 11.sp) }
        }
        SectionTitle("SELECCIONAR POR TIPO")
        val groups = SelectionTools.groupedParts(model, vm::categoryOf)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (cat, parts) ->
                AssistChip(onClick = { vm.selectCategory(cat) }, label = { Text("${cat.label} (${parts.size})", fontSize = 12.sp) })
            }
        }
        val wheels = model.parts.filter { it.wheelSlot != null && vm.categoryOf(it) == PartCategory.WHEEL }
        if (wheels.isNotEmpty()) {
            SectionTitle("RUEDAS DETECTADAS")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                wheels.forEach { w -> AssistChip(onClick = { vm.select(SelectionTools.partSubs(model, w.id)) }, label = { Text(w.wheelSlot!!.label, fontSize = 11.sp) }) }
                Button(onClick = { vm.selectCategory(PartCategory.WHEEL); vm.setVisible(vm.editState.selection, false) }, colors = ButtonDefaults.buttonColors(containerColor = Bad), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("OCULTAR RUEDAS", fontSize = 11.sp) }
            }
        } else if (model.parts.none { vm.categoryOf(it) == PartCategory.WHEEL }) {
            Text("No se identificaron ruedas automáticamente: toque cada rueda manualmente.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val related = vm.currentEntry?.relatedParts.orEmpty()
        if (related.isNotEmpty()) {
            SectionTitle("PIEZAS RELACIONADAS")
            related.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(p.dff.fileName, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val added = p.dff.path in vm.addedParts
                    TextButton(onClick = { vm.addRelatedPart(p) }, enabled = !added) { Text(if (added) "Añadida" else "Añadir a la escena", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun MaterialsTab(vm: AppViewModel, model: VehicleModel) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item { Text("Seleccionar por material / textura: actúa sobre todos los meshes que lo usan.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
        items(model.materials, key = { it.key }) { mat ->
            val count = model.subMeshes.count { it.material.key == mat.key }
            Card(colors = CardDefaults.cardColors(containerColor = PanelHi), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF000000 or ((mat.r shl 16) or (mat.g shl 8) or mat.b).toLong())))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mat.name + if (mat.textureMissing) "  (textura faltante)" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$count sub-mesh${if (mat.transparent) " · transparente" else ""}${if (mat.paintSlot != 0) " · pintura" else ""}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        OutlinedButton(onClick = { vm.selectMaterial(mat.key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("SELECCIONAR TODOS", fontSize = 9.sp, maxLines = 2) }
                        OutlinedButton(onClick = { vm.hideMaterial(mat.key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("OCULTAR TODOS", fontSize = 9.sp, maxLines = 2) }
                        OutlinedButton(onClick = { vm.deleteMaterial(mat.key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text("ELIMINAR TODOS", fontSize = 9.sp, maxLines = 2) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
