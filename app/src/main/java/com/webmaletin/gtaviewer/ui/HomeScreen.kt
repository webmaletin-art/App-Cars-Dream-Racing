package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.catalog.RelatedPart
import com.webmaletin.gtaviewer.catalog.VehicleEntry

@Composable
fun HomeScreen(vm: AppViewModel, onImport: () -> Unit) {
    val report = vm.report
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).statusBarsPadding()) {
        Spacer(Modifier.height(14.dp))
        Text("GTA VEHICLE VIEWER", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 1.sp)
        Text("GTA San Andreas · DFF + TXD → visor 3D → GLB / OBJ / FBX", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onImport, modifier = Modifier.weight(1f), enabled = vm.scanProgress == null) { Text("IMPORTAR ZIP", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { vm.navigate(Screen.MANUAL) }, modifier = Modifier.weight(1f)) { Text("SELECCIONAR DFF + TXD", fontSize = 12.sp, maxLines = 2) }
        }
        Spacer(Modifier.height(10.dp))

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

        if (report == null && vm.scanProgress == null) {
            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Cómo funciona", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("1. Importe uno o varios ZIP con vehículos de GTA SA (cualquier estructura de carpetas).\n" +
                        "2. La app busca los DFF/TXD, los empareja y arma el catálogo.\n" +
                        "3. Abra un vehículo, rótelo, edite piezas (ruedas, puertas, capó…) y expórtelo.\n\n" +
                        "Si un ZIP es raro o el emparejado falla, use «SELECCIONAR DFF + TXD» (modo manual).",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (report != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("CATÁLOGO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("${report.vehicles.size} vehículos detectados", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("DFF ${report.dffCount} · TXD ${report.txdCount} · sin TXD ${report.dffWithoutTxd} · TXD huérfanos ${report.orphanTxds.size}" +
                        (if (report.duplicatesCollapsed > 0) " · duplicados ${report.duplicatesCollapsed}" else ""),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.navigate(Screen.DIAGNOSTICS) }) { Text("Diagnóstico") }
            }
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it }, singleLine = true, placeholder = { Text("Buscar vehículo") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            val list = vm.filteredVehicles
            if (list.isEmpty()) {
                Text(if (report.vehicles.isEmpty()) "No se encontró ningún DFF en el ZIP. Use el MODO MANUAL si el contenido es inusual." else "Sin resultados para «${vm.query}».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.weight(1f)) {
                items(list, key = { it.id }) { v -> VehicleCard(v, onOpen = { vm.openVehicle(v) }) }
            }
        } else Spacer(Modifier.weight(1f))
    }
}

@Composable
fun VehicleCard(v: VehicleEntry, onOpen: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(PanelHi), contentAlignment = Alignment.Center) {
                    Text(v.displayName.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("DFF: ${v.dff.fileName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("TXD: ${v.txd?.fileName ?: "— (faltante)"}", fontSize = 12.sp,
                        color = if (v.txd == null) Warn else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("VER", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(v.status)
                if (v.relatedParts.isNotEmpty()) Text("${v.relatedParts.size} pieza(s) relacionada(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.duplicates.isNotEmpty()) Text("+${v.duplicates.size} duplicado(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("Ruta DFF: ${v.dff.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                v.txd?.let { Text("Ruta TXD: ${it.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                v.warnings.forEach { Text("⚠ $it", fontSize = 11.sp, color = Warn) }
                if (v.relatedParts.isNotEmpty()) {
                    SectionTitle("PIEZAS RELACIONADAS")
                    v.relatedParts.forEach { p: RelatedPart -> Text("• ${p.dff.fileName}  (${p.reason})", fontSize = 12.sp) }
                }
                v.duplicates.forEach { Text("= duplicado: ${it.path}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
