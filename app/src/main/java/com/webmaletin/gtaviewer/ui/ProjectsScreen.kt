package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.project.ProjectInfo
import java.text.DateFormat
import java.util.Date

@Composable
fun ProjectsScreen(app: AppViewModel, lab: LabViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<ProjectInfo?>(null) }
    var dup by remember { mutableStateOf<ProjectInfo?>(null) }
    val list = lab.projectList()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader("PROYECTOS", "Se guardan solos tras cada cambio y reabren tras reiniciar", onBack = { app.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) { Text("NUEVO PROYECTO", fontWeight = FontWeight.Bold) }
            if (lab.hasProject) OutlinedButton(onClick = { app.navigate(Screen.WORKSHOP) }, modifier = Modifier.fillMaxWidth()) { Text("CONTINUAR: ${lab.ws?.state?.name ?: ""}") }
        }
        if (list.isEmpty()) {
            Text("Todavía no hay proyectos. Cree uno eligiendo una carrocería base del catálogo.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { pr ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${pr.parts} piezas · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(pr.modified))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = { lab.openProject(pr.id) { app.navigate(Screen.WORKSHOP) } }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("ABRIR") }
                            OutlinedButton(onClick = { dup = pr }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("DUPLICAR", fontSize = 12.sp) }
                            OutlinedButton(onClick = { del = pr }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("ELIMINAR", fontSize = 12.sp, color = Bad) }
                        }
                    }
                }
            }
        }
    }
    if (showNew) NewProjectDialog(lab, onDismiss = { showNew = false }, onCreate = { name, base -> showNew = false; lab.newProject(name, base) { app.navigate(Screen.WORKSHOP) } })
    del?.let { d -> ConfirmDialog("Eliminar proyecto", "Se eliminará «${d.name}». Las piezas del catálogo y los archivos originales NO se tocan. Esta acción no se puede deshacer.", "ELIMINAR", danger = true, onConfirm = { lab.deleteProject(d.id) }, onDismiss = { del = null }) }
    dup?.let { d -> TextPromptDialog("Duplicar proyecto", d.name + " (copia)", onOk = { lab.duplicateProject(d.id, it) }, onDismiss = { dup = null }) }
}

@Composable
private fun NewProjectDialog(lab: LabViewModel, onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("Mi auto") }
    var base by remember { mutableStateOf<String?>(null) }
    val vehicles = remember(lab.libRev) { lab.store.vehicles().sortedBy { it.name.lowercase() } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text("Nuevo proyecto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") })
                Text("Carrocería base (queda fija, es la referencia):", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vehicles.isEmpty()) Text("El catálogo está vacío: importe vehículos en CATÁLOGO. Puede crear el proyecto sin base y elegirla después.", fontSize = 12.sp, color = Warn)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(vehicles, key = { it.id }) { v ->
                        Row(Modifier.fillMaxWidth().clickable { base = v.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = base == v.id, onClick = { base = v.id })
                            Thumb(lab, v.id, 44)
                            Spacer(Modifier.width(8.dp))
                            Column { Text(v.name, fontSize = 13.sp, maxLines = 1); Text(v.types.firstOrNull()?.label ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, base) }, enabled = name.isNotBlank()) { Text("CREAR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } })
}
