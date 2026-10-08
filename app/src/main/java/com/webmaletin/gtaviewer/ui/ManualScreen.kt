package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.ManualFile
import com.webmaletin.gtaviewer.zip.ArchiveEntry

/** Modo manual: cargar EXACTAMENTE la combinación DFF + TXD elegida (de cualquier carpeta del ZIP o del dispositivo). */
@Composable
fun ManualScreen(vm: AppViewModel, onImport: () -> Unit, onPickDff: () -> Unit, onPickTxd: () -> Unit) {
    var browse by remember { mutableStateOf<String?>(null) } // "dff" | "txd"
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("← Atrás") }
            Text("MODO MANUAL", fontWeight = FontWeight.ExtraBold, color = Accent, fontSize = 20.sp)
        }
        Text("Elija cualquier DFF y cualquier TXD, estén donde estén: no hace falta que compartan carpeta ni nombre.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        FilePickerCard("DFF seleccionado:", vm.manualDff, "dff", vm.hasArchives, onBrowse = { browse = "dff" }, onDevice = onPickDff, onClear = null)
        Spacer(Modifier.height(10.dp))
        FilePickerCard("TXD seleccionado:", vm.manualTxd, "txd", vm.hasArchives, onBrowse = { browse = "txd" }, onDevice = onPickTxd, onClear = { vm.clearManualTxd() })

        if (!vm.hasArchives) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("IMPORTAR ZIP para navegar su contenido") }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { vm.loadManual(true) }, enabled = vm.manualDff != null && vm.manualTxd != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("CARGAR VEHÍCULO", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.loadManual(false) }, enabled = vm.manualDff != null, modifier = Modifier.fillMaxWidth()) {
            Text("Cargar DFF sin TXD (inspeccionar geometría)")
        }
        TextButton(onClick = { vm.clearManual() }, enabled = vm.manualDff != null || vm.manualTxd != null) { Text("Limpiar selección") }
        Spacer(Modifier.height(24.dp))
    }
    browse?.let { ext ->
        ZipBrowserDialog(vm.archiveEntries(ext), ext, onDismiss = { browse = null }, onPick = { e ->
            if (ext == "dff") vm.setManualDffFromEntry(e) else vm.setManualTxdFromEntry(e)
            browse = null
        })
    }
}

@Composable
private fun FilePickerCard(title: String, file: ManualFile?, ext: String, hasZip: Boolean, onBrowse: () -> Unit, onDevice: () -> Unit, onClear: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(file?.label ?: "[ninguno]", color = if (file == null) MaterialTheme.colorScheme.onSurfaceVariant else Good, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBrowse, enabled = hasZip, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.weight(1f)) { Text("Del ZIP", fontSize = 12.sp) }
                OutlinedButton(onClick = onDevice, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.weight(1f)) { Text("Del dispositivo", fontSize = 12.sp) }
                if (onClear != null && file != null) TextButton(onClick = onClear) { Text("Quitar", fontSize = 12.sp) }
            }
        }
    }
}

/** Explorador del contenido de los ZIP importados (carpetas y archivos de la extensión pedida). */
@Composable
fun ZipBrowserDialog(entries: List<ArchiveEntry>, ext: String, onDismiss: () -> Unit, onPick: (ArchiveEntry) -> Unit) {
    var dir by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val q = search.trim().lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        title = { Text("Elegir .${ext.uppercase()} del ZIP") },
        text = {
            Column {
                OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true, placeholder = { Text("Buscar en todo el ZIP") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                if (q.isEmpty()) {
                    Text("/" + dir, fontSize = 12.sp, color = Accent)
                    val inDir = entries.filter { it.dir.equals(dir, true) || Paths.under(it.dir, dir) }
                    val subdirs = inDir.filter { !it.dir.equals(dir, true) }.map { Paths.child(it.dir, dir) }.distinct().sorted()
                    val files = inDir.filter { it.dir.equals(dir, true) }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        if (dir.isNotEmpty()) item { Text("⬑ ..", modifier = Modifier.fillMaxWidth().clickable { dir = dir.substringBeforeLast('/', "") }.padding(10.dp)) }
                        items(subdirs) { d -> Text("📁 ${d.substringAfterLast('/')}", modifier = Modifier.fillMaxWidth().clickable { dir = d }.padding(10.dp)) }
                        items(files) { e -> Text("📄 ${e.fileName}", modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(10.dp)) }
                        if (subdirs.isEmpty() && files.isEmpty()) item { Text("Sin archivos .$ext aquí", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp)) }
                    }
                } else {
                    val found = entries.filter { it.path.lowercase().contains(q) }.take(200)
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(found) { e -> Text(e.path, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(10.dp)) }
                    }
                }
            }
        },
    )
}

private object Paths {
    fun under(child: String, parent: String) = parent.isEmpty() || child.lowercase().startsWith(parent.lowercase() + "/")
    /** Primer nivel de [child] por debajo de [parent]. */
    fun child(child: String, parent: String): String {
        val rest = if (parent.isEmpty()) child else child.substring(parent.length + 1)
        val first = rest.substringBefore('/')
        return if (parent.isEmpty()) first else "$parent/$first"
    }
}
