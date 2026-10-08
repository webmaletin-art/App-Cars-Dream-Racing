package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.webmaletin.gtaviewer.CatalogFilter
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.catalog.CatalogItem
import com.webmaletin.gtaviewer.catalog.RelatedPart
import com.webmaletin.gtaviewer.catalog.WorkflowStatus
import com.webmaletin.gtaviewer.export.ExportFormat

@Composable
fun HomeScreen(vm: AppViewModel, onImport: () -> Unit) {
    val report = vm.report
    var showPrep by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).statusBarsPadding()) {
        Spacer(Modifier.height(14.dp))
        Text("GTA VEHICLE VIEWER", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 1.sp)
        Text("GTA San Andreas · DFF + TXD → Outliner → GLB / OBJ / FBX", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onImport, modifier = Modifier.weight(1f), enabled = vm.scanProgress == null) { Text("IMPORTAR ZIP", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { vm.navigate(Screen.MANUAL) }, modifier = Modifier.weight(1f)) { Text("SELECCIONAR DFF + TXD", fontSize = 12.sp, maxLines = 2) }
        }
        Spacer(Modifier.height(8.dp))

        vm.scanProgress?.let { p ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.stage, fontWeight = FontWeight.SemiBold)
                    Text("${p.percent}%", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    if (p.detail.isNotEmpty()) Text(p.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (vm.items.isEmpty() && vm.scanProgress == null) {
            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Flujo de trabajo", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("1. Importe uno o varios ZIP con vehículos de GTA SA (cualquier estructura de carpetas).\n" +
                        "2. Abra cada vehículo: el Outliner lista todas sus partes; la casilla = visible = se exporta.\n" +
                        "3. «Preparar para mi juego» desmarca las ruedas; revise y pulse «Marcar como listo».\n" +
                        "4. Vuelva al catálogo, seleccione los vehículos y «Exportar seleccionados» → un único ZIP de GLB.\n\n" +
                        "Si un ZIP es raro o el emparejado falla, use «SELECCIONAR DFF + TXD» (modo manual): el vehículo entra al catálogo con las mismas funciones.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (vm.items.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("CATÁLOGO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("${vm.items.size} vehículos detectados", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (report != null) Text("DFF ${report.dffCount} · TXD ${report.txdCount} · sin TXD ${report.dffWithoutTxd} · TXD huérfanos ${report.orphanTxds.size}" +
                        (if (report.duplicatesCollapsed > 0) " · duplicados ${report.duplicatesCollapsed}" else ""),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.navigate(Screen.DIAGNOSTICS) }) { Text("Diagnóstico") }
            }
            val ready = vm.items.count { vm.statusOf(it.id) == WorkflowStatus.READY }
            val edited = vm.items.count { vm.statusOf(it.id) == WorkflowStatus.EDITED }
            Text("✓ Listos: $ready  ·  ◐ Editados: $edited  ·  ○ Sin revisar: ${vm.items.size - ready - edited}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(value = vm.query, onValueChange = { vm.query = it }, singleLine = true, label = { Text("Buscar") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CatalogFilter.values().forEach { f -> FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(f.label, fontSize = 12.sp) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.selectAllItems() }, label = { Text("Seleccionar todos", fontSize = 12.sp) })
                AssistChip(onClick = { vm.deselectAllItems() }, label = { Text("Deseleccionar todos", fontSize = 12.sp) })
                AssistChip(onClick = { vm.invertItemSelection() }, label = { Text("Invertir selección", fontSize = 12.sp) })
                AssistChip(onClick = { vm.selectCompatibleItems() }, label = { Text("Seleccionar compatibles", fontSize = 12.sp) })
            }
            Text("${vm.selectedIds.size} seleccionados", fontWeight = FontWeight.Bold, color = if (vm.selectedIds.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
                modifier = Modifier.padding(vertical = 2.dp))

            val list = vm.visibleItems
            if (list.isEmpty()) Text(if (vm.items.isEmpty()) "" else "Sin resultados para este filtro/búsqueda.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp), modifier = Modifier.weight(1f)) {
                items(list, key = { it.id }) { v -> ItemCard(vm, v) }
            }

            // ---- barra de exportación masiva
            Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ZIP de:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ExportFormat.values().forEach { f -> FilterChip(selected = vm.batchFormat == f, onClick = { vm.batchFormat = f }, label = { Text(f.name, fontSize = 12.sp) }) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showPrep = true }) { Text("PREPARACIÓN\nPARA MI JUEGO", fontSize = 10.sp, lineHeight = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { vm.requestBatchSelected() }, enabled = vm.selectedIds.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                            Text("EXPORTAR SELECCIONADOS (${vm.selectedIds.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                        }
                        OutlinedButton(onClick = { vm.requestBatchAllCompatible() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                            Text("EXPORTAR TODOS LOS COMPATIBLES", fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        } else Spacer(Modifier.weight(1f))
    }
    if (showPrep) PrepDialog(vm, onDismiss = { showPrep = false })
}

@Composable
private fun WorkflowBadge(s: WorkflowStatus) {
    val c = when (s) { WorkflowStatus.READY -> Good; WorkflowStatus.EDITED -> Warn; WorkflowStatus.ERROR -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.16f)) {
        Text("${s.symbol} ${s.label}", color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
fun ItemCard(vm: AppViewModel, item: CatalogItem) {
    var expanded by remember { mutableStateOf(false) }
    val status = vm.statusOf(item.id)
    val v = item.entry
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.id in vm.selectedIds, onCheckedChange = { vm.toggleSelected(item.id) })
                Column(Modifier.weight(1f)) {
                    Text(item.displayName + if (item.manual) "  (manual)" else "", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("DFF: ${item.dffLabel}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("TXD: ${item.txdLabel ?: "— (faltante)"}", fontSize = 12.sp, color = if (item.txdLabel == null) Warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { vm.openItem(item) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("VER", fontWeight = FontWeight.Bold) }
            }
            Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkflowBadge(status)
                if (v != null) StatusChip(v.status)
                Text(if (item.compatibility.ok) "Compatible" else "No compatible: ${item.compatibility.reason}", fontSize = 11.sp,
                    color = if (item.compatibility.ok) Good else Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                Column(Modifier.padding(start = 12.dp, top = 6.dp)) {
                    vm.itemErrors[item.id]?.let { Text("✕ $it", fontSize = 11.sp, color = Bad) }
                    if (v != null) {
                        Text("Ruta DFF: ${v.dff.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        v.txd?.let { Text("Ruta TXD: ${it.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        v.warnings.forEach { Text("⚠ $it", fontSize = 11.sp, color = Warn) }
                        if (v.relatedParts.isNotEmpty()) {
                            SectionTitle("PIEZAS RELACIONADAS")
                            v.relatedParts.forEach { p: RelatedPart -> Text("• ${p.dff.fileName}  (${p.reason})", fontSize = 12.sp) }
                        }
                        v.duplicates.forEach { Text("= duplicado: ${it.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else Text("Cargado en modo manual.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
