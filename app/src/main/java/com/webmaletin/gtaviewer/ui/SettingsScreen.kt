package com.webmaletin.gtaviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.BuildConfig
import com.webmaletin.gtaviewer.LabViewModel

@Composable
fun SettingsScreen(app: AppViewModel, lab: LabViewModel) {
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u -> if (u != null) lab.exportBackup(u) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) lab.importBackup(u) }
    val (v, p) = lab.counts()
    var confirmGc by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader("CONFIGURACIÓN", null, onBack = { app.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("ALMACENAMIENTO")
            KeyValue("Catálogo", "$v vehículos · $p piezas")
            KeyValue("Espacio usado", "%.1f MB".format(lab.storageBytes() / 1048576.0))
            KeyValue("Ubicación", "datos privados de la app (sobreviven a las actualizaciones; se borran solo al desinstalar)")
            Button(onClick = { backupOut.launch("GTA_CAR_LAB_catalogo.zip") }, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR COPIA DE SEGURIDAD") }
            OutlinedButton(onClick = { backupIn.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("IMPORTAR COPIA DE SEGURIDAD") }
            OutlinedButton(onClick = { confirmGc = true }, modifier = Modifier.fillMaxWidth()) { Text("LIBERAR ESPACIO (datos huérfanos)") }
            Text("La copia incluye vehículos, piezas, texturas, categorías propias, correcciones y ajustes aprendidos. Restaurarla no duplica lo que ya existe. Los proyectos se guardan aparte y no forman parte de la copia.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("EXPORTACIÓN POR DEFECTO")
            val profiles = lab.savedProfiles()
            ChipRow(profiles, profiles.firstOrNull { it.name == lab.defaultProfileName }, { it.name }, allLabel = null) { it?.let { p2 -> lab.defaultProfileName = p2.name } }
            profiles.firstOrNull { it.name == lab.defaultProfileName }?.let { Text(it.describe(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            SectionTitle("ACERCA DE")
            KeyValue("Versión", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            KeyValue("Actualización", "Instale el APK nuevo sobre el actual: misma firma, no hace falta desinstalar y se conserva el catálogo.")
            SectionTitle("LÍMITES CONOCIDOS (sin maquillar)")
            Text(
                "• «Fusionar» suelda vértices coincidentes y cose bordes; NO es una unión booleana de volúmenes.\n" +
                "• La clasificación de tipo de vehículo es heurística (medidas + nombres + similitud + sus correcciones). «Moderno» es la etiqueta más débil.\n" +
                "• La compatibilidad se estima por dimensiones, posición y familia; siempre puede probar una pieza, con aviso.\n" +
                "• La detección de logos es solo por palabras clave; el borrado de caras y el inpainting de textura son manuales.\n" +
                "• Los archivos RAR no se leen dentro de la app: extraiga a ZIP.\n" +
                "• Los DFF/TXD/ZIP originales nunca se modifican.",
                fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmGc) ConfirmDialog("Liberar espacio", "Se borrarán geometrías y texturas guardadas que ya ningún elemento del catálogo usa. No afecta a vehículos ni piezas visibles.", "LIBERAR", onConfirm = { lab.gc() }, onDismiss = { confirmGc = false })
}
