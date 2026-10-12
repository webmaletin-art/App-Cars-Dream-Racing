package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.GenCard
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.classify.VehicleTypes
import com.webmaletin.gtaviewer.model.PartCategory

private val modLabels = listOf("0 · Original sin modificar", "1 · Retoques leves", "2 · Modificado", "3 · Muy modificado")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GeneratorScreen(app: AppViewModel, lab: LabViewModel) {
    val (nv, np) = lab.counts()
    var showForm by remember { mutableStateOf(true) }
    var basePicker by remember { mutableStateOf(false) }
    val vehicles = remember(lab.libRev) { lab.store.vehicles().sortedBy { it.name.lowercase() } }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader("GENERADOR DE AUTOS", "$nv carrocerías · $np piezas en el catálogo", onBack = { app.back() }) {
            TextButton(onClick = { showForm = !showForm }) { Text(if (showForm) "OCULTAR AJUSTES" else "AJUSTES") }
        }
        if (nv == 0) { InfoBox("El catálogo no tiene vehículos. Impórtelos en CATÁLOGO: el generador solo usa lo que existe y le dirá lo que falte.", Color(0xFF3A2A00), Modifier.padding(12.dp)) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showForm) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("TIPO DE VEHÍCULO")
                    ChipRow(VehicleTypes.ALL, lab.genType, { it }, "Cualquiera") { lab.genType = it }
                    SectionTitle("ESTILO")
                    ChipRow(listOf(VehicleTypes.CLASSIC, VehicleTypes.MODERN), lab.genStyle, { it }, "Indistinto") { lab.genStyle = it }
                    SectionTitle("NIVEL DE MODIFICACIÓN")
                    LabeledSlider(modLabels[lab.genMod], lab.genMod.toFloat(), 0f..3f, valueText = "", onChange = { lab.genMod = it.toInt().coerceIn(0, 3) })
                    SectionTitle("PIEZAS")
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.genOriginal, { lab.genOriginal = it }); Spacer(Modifier.width(8.dp)); Text("Usar piezas originales", fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.genCustom, { lab.genCustom = it }); Spacer(Modifier.width(8.dp)); Text("Usar piezas personalizadas / de otros autos", fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.genWheels, { lab.genWheels = it }); Spacer(Modifier.width(8.dp)); Text("Cambiar ruedas", fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.genInterior, { lab.genInterior = it }); Spacer(Modifier.width(8.dp)); Text("Incluir interior", fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.genEmblems, { lab.genEmblems = it }); Spacer(Modifier.width(8.dp)); Text("Conservar emblemas", fontSize = 13.sp) }
                    Text("Accesorios permitidos:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(PartCategory.BUMPER, PartCategory.SPOILER, PartCategory.SKIRT, PartCategory.HOOD, PartCategory.ROOF, PartCategory.EXHAUST, PartCategory.GRILLE, PartCategory.FENDER, PartCategory.MIRROR, PartCategory.LIGHT, PartCategory.INTAKE).forEach { c ->
                            FilterChip(selected = c in lab.genCats, onClick = { lab.genCats = if (c in lab.genCats) lab.genCats - c else lab.genCats + c }, label = { Text(c.label, fontSize = 11.sp) })
                        }
                    }
                    SectionTitle("PINTURA")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = lab.genPaint == null, onClick = { lab.genPaint = null }, label = { Text("Sin cambio", fontSize = 11.sp) })
                        SwatchRow { lab.genPaint = it }
                    }
                    lab.genPaint?.let { Box(Modifier.size(22.dp).background(Color(0xFF000000 or it.toLong()), RoundedCornerShape(4.dp))) }
                    SectionTitle("CARROCERÍA BASE")
                    OutlinedButton(onClick = { basePicker = true }) { Text(lab.genBase?.let { id -> vehicles.firstOrNull { it.id == id }?.name } ?: "Automática (según el tipo pedido)") }
                    if (lab.genLocked.isNotEmpty()) {
                        Text("Piezas bloqueadas (se conservan al regenerar): ${lab.genLocked.size}", fontSize = 12.sp, color = Accent)
                        TextButton(onClick = { lab.genLocked = emptyMap() }) { Text("Desbloquear todas") }
                    }
                }
            }
            item {
                Button(onClick = { lab.generate(4); showForm = false }, enabled = !lab.genBusy && nv > 0, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (lab.genBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black); Spacer(Modifier.width(8.dp)) }
                    Text(if (lab.genCards.isEmpty()) "GENERAR 4 ALTERNATIVAS" else "GENERAR OTRAS 4", fontWeight = FontWeight.Bold)
                }
            }
            items(lab.genCards, key = { System.identityHashCode(it) }) { c -> GenCardView(lab, app, c) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (basePicker) AlertDialog(onDismissRequest = { basePicker = false }, containerColor = Panel, title = { Text("Carrocería base") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item { TextButton(onClick = { lab.genBase = null; basePicker = false }) { Text("Automática") } }
                items(vehicles, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(lab, v.id, 48); Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { lab.genBase = v.id; basePicker = false }) { Text(v.name, maxLines = 1) }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { basePicker = false }) { Text("CERRAR") } })
}

@Composable
private fun GenCardView(lab: LabViewModel, app: AppViewModel, c: GenCard) {
    val r = c.result
    var open by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(150.dp).background(Color(0xFF12151B), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    val b = c.preview
                    if (b != null) Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) else Text("sin vista previa", fontSize = 11.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.baseName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${r.picks.size} piezas · semilla ${r.seed}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.meshChecked) Text("✔ Revisado sobre mallas reales", fontSize = 11.sp, color = Good) else Text("Solo evaluado por medidas", fontSize = 11.sp, color = Warn)
                    if (r.missing.isNotEmpty()) Text("Faltan: ${r.missing.size}", fontSize = 11.sp, color = Warn)
                }
            }
            Button(onClick = { lab.openGenerated(c, false) { app.navigate(Screen.WORKSHOP) } }, modifier = Modifier.fillMaxWidth()) { Text("ABRIR EN EL TALLER", fontWeight = FontWeight.Bold) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { lab.openGenerated(c, true) { app.navigate(Screen.PROJECTS) }; lab.message = "Guardado como favorito (★) en PROYECTOS" }, modifier = Modifier.weight(1f)) { Text("★ FAVORITO", fontSize = 12.sp) }
                OutlinedButton(onClick = { open = !open }, modifier = Modifier.weight(1f)) { Text(if (open) "OCULTAR DETALLE" else "DETALLE", fontSize = 12.sp) }
            }
            if (open) {
                r.picks.forEach { p ->
                    val locked = lab.genLocked[p.slot] == p.pieceId
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.category.label}: ${p.pieceName}", fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { LevelChip(p.result.level); if (p.fromSameVehicle) Text("original de la base", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            p.result.reasons.firstOrNull()?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        FilterChip(selected = locked, onClick = { lab.lockSlot(p.slot, if (locked) null else p.pieceId) }, label = { Text(if (locked) "🔒 conservar" else "conservar", fontSize = 11.sp) })
                    }
                }
                if (r.missing.isNotEmpty()) InfoBox("Falta en el catálogo:\n" + r.missing.joinToString("\n") { "• $it" }, Color(0xFF3A2A00))
                r.notes.forEach { Text("Nota: $it", fontSize = 11.sp) }
                if (c.report.isNotEmpty()) InfoBox("Comprobación en mallas:\n" + c.report.joinToString("\n") { "• $it" })
            }
        }
    }
}
