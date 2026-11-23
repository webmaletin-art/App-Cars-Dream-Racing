package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.beamng.BeamVehicleEntry

@Composable
fun BeamScreen(vm: AppViewModel, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).statusBarsPadding().navigationBarsPadding()) {
        TextButton(onClick = { vm.back() }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("← MENÚ PRINCIPAL", fontSize = 12.sp) }
        Text("BEAMNG CARS", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 1.sp)
        Text("Mods de BeamNG.drive · .dae + .jbeam + materiales → 3D → GLB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Button(onClick = onImport, enabled = vm.beamProgress == null, modifier = Modifier.fillMaxWidth()) { Text("IMPORTAR ZIP DE BEAMNG", fontWeight = FontWeight.Bold) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Calidad de texturas:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            listOf(512, 1024, 2048).forEach { s -> FilterChip(selected = vm.beamTexSize == s, onClick = { vm.beamTexSize = s }, label = { Text("$s", fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        Text("Más calidad = más memoria. En el teléfono 1024 suele ser lo adecuado.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.beamProgress?.let { Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Column(Modifier.padding(14.dp)) { Text(it, fontSize = 13.sp); Spacer(Modifier.height(6.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) } } }
        if (vm.beamVehicles.isEmpty() && vm.beamProgress == null) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Cómo usarlo", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("1. Importa el .zip del mod (el que se copia a Documents/BeamNG.drive/mods). Si el ZIP contiene otro ZIP dentro, la app lo extrae sola.\n" +
                        "2. Elige la configuración (motor, tracción, piezas) y toca VER.\n" +
                        "3. Mira el coche con sus texturas, oculta o aísla piezas en el Outliner y exporta a GLB: el archivo sale con el mismo nombre que el que importaste.\n\n" +
                        "Si el mod viene en RAR (varias partes), primero descomprímelo con tu administrador de archivos hasta obtener el .zip.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.weight(1f)) {
            items(vm.beamVehicles, key = { it.id }) { v -> BeamCard(vm, v) }
        }
    }
}

@Composable
private fun BeamCard(vm: AppViewModel, v: BeamVehicleEntry) {
    var menu by remember { mutableStateOf(false) }
    val chosen = vm.beamConfigChoice[v.id] ?: v.defaultConfig
    val cfg = v.configs.firstOrNull { it.name == chosen }
    val years = (v.info["Years"] as? Map<*, *>)?.let { "${(it["min"] as? Number)?.toInt() ?: ""}–${(it["max"] as? Number)?.toInt() ?: ""}" }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(v.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(v.info["Body Style"]?.toString(), years, v.info["Country"]?.toString(), v.info["Author"]?.toString()?.let { "por $it" }).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Archivo: ${v.sourceName} · carpeta ${v.folder} · ${v.configs.size} configuraciones", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            v.info["Description"]?.toString()?.let { Text(it, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            if (v.configs.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text(cfg?.label ?: "Configuración", fontSize = 13.sp, fontWeight = FontWeight.SemiBold); if (!cfg?.summary.isNullOrBlank()) Text(cfg!!.summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        v.configs.forEach { c -> DropdownMenuItem(text = { Column { Text(c.label, fontSize = 13.sp); if (c.summary.isNotBlank()) Text(c.summary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { vm.chooseBeamConfig(v, c.name); menu = false }) }
                    }
                }
            }
            Button(onClick = { vm.openBeam(v) }, modifier = Modifier.fillMaxWidth()) { Text("VER", fontWeight = FontWeight.Bold) }
        }
    }
}
