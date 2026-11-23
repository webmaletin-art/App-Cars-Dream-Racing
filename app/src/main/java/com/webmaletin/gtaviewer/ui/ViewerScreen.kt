package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.catalog.VehicleStatus
import com.webmaletin.gtaviewer.gl.GlViewerView

@Composable
fun ViewerScreen(vm: AppViewModel) {
    val model = vm.model ?: return
    var gl by remember { mutableStateOf<GlViewerView?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showCfg by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner, gl) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) { Lifecycle.Event.ON_PAUSE -> gl?.onPause(); Lifecycle.Event.ON_RESUME -> gl?.onResume(); else -> {} }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Comandos de cámara (presets / auto encuadre) procedentes del ViewModel
    val cmd = vm.cameraCommand
    LaunchedEffect(cmd.id, gl) {
        val v = gl ?: return@LaunchedEffect
        cmd.preset?.let { p -> synchronized(v.camera) { v.camera.setPreset(p) } }
        v.frame(vm.frameBounds(cmd.robust))
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GlViewerView(ctx).also { v ->
                    v.onTap = { x, y, w, h ->
                        vm.pick { synchronized(v.camera) { v.camera.rayFor(x, y, w, h) } }
                    }
                    v.onDoubleTap = { vm.requestCamera(null) }
                    gl = v
                }
            },
            update = { it.update(vm.renderState) },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- barra superior
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0xCC0F1115)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.closeVehicle() }) { Text("←") }
                Column(Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val c = vm.counters
                    Text(if (c == null) "" else "Partes visibles: ${c.partsVisible} / ${c.partsTotal} · Meshes visibles: ${c.meshesVisible} / ${c.meshesTotal}", fontSize = 11.sp, color = Accent)
                    vm.currentItem?.let { Text("${it.manual.let { m -> if (m) "Manual · " else "" }}${vm.statusOf(it.id).symbol} ${vm.statusOf(it.id).label}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (vm.currentBeam != null) TextButton(onClick = { showCfg = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("CONFIG.", fontSize = 12.sp) }
                TextButton(onClick = { showInfo = true }) { Text("INFO") }
                TextButton(onClick = { vm.navigate(Screen.DIAGNOSTICS) }) { Text("DIAG.") }
            }
            if (model.validation.needsReview) ReviewBanner(vm)
            else if (vm.currentEntry?.status == VehicleStatus.TXD_MISSING || model.diagnostics.texturesMissing.isNotEmpty() || model.diagnostics.txdFileName == null) {
                val txt = if (model.diagnostics.txdFileName == null) "Sin TXD: se muestran colores de material"
                else "Texturas faltantes: ${model.diagnostics.texturesMissing.joinToString()}"
                Text("⚠ $txt", color = Warn, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }

        // ---- controles inferiores
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            CameraBar(vm)
            if (vm.editMode) EditPanel(vm, onExport = { showExport = true })
            Row(Modifier.fillMaxWidth().background(Color(0xE6121418)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val on = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF1B1200))
                val off = ButtonDefaults.buttonColors(containerColor = PanelHi, contentColor = Color.White)
                Button(onClick = { vm.outlinerOpen = !vm.outlinerOpen; if (vm.outlinerOpen) vm.editMode = false }, colors = if (vm.outlinerOpen) on else off, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text("OUTLINER", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Button(onClick = { vm.editMode = !vm.editMode; if (vm.editMode) vm.outlinerOpen = false }, colors = if (vm.editMode) on else off, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text("EDITAR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Button(onClick = { showExport = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("EXPORTAR", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }

        // ---- información de la pieza tocada (también sin abrir paneles)
        if (!vm.outlinerOpen && !vm.editMode && vm.editState.selection.isNotEmpty()) {
            Surface(color = Color(0xE6181C23), shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.CenterStart).padding(10.dp).widthIn(max = 280.dp)) {
                Column(Modifier.padding(10.dp)) { SelectionInfo(vm, model) }
            }
        }

        // ---- Outliner lateral
        if (vm.outlinerOpen) {
            val w = minOf(360, (LocalConfiguration.current.screenWidthDp * 0.88f).toInt()).dp
            Surface(color = Color(0xF2181C23), modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding().padding(top = 56.dp, bottom = 112.dp)
                .fillMaxHeight().width(w), shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)) {
                OutlinerPanel(vm, onExport = { showExport = true })
            }
        }
    }
    if (showExport) ExportDialog(vm, onDismiss = { showExport = false }, onGo = { f, s -> showExport = false; vm.export(f, s) })
    if (showInfo) InfoDialog(vm, onDismiss = { showInfo = false })
    if (showCfg) vm.currentBeamEntry?.let { v ->
        AlertDialog(onDismissRequest = { showCfg = false }, confirmButton = { TextButton(onClick = { showCfg = false }) { Text("Cerrar") } }, title = { Text("Configuración · ${v.displayName}") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(v.configs, key = { it.name }) { c ->
                        val cur = c.name == vm.currentBeam?.config
                        Column(Modifier.fillMaxWidth().clickable { showCfg = false; if (!cur) vm.reloadBeamConfig(c.name) }.padding(vertical = 8.dp)) {
                            Text((if (cur) "✔ " else "") + c.label, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal)
                            if (c.summary.isNotBlank()) Text(c.summary, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            })
    }
}

@Composable
private fun ReviewBanner(vm: AppViewModel) {
    val m = vm.model ?: return
    Surface(color = Color(0xE63A2A00), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("⚠ Modelo requiere revisión", color = Warn, fontWeight = FontWeight.Bold)
            m.validation.reasons.take(3).forEach { Text("• $it", fontSize = 11.sp, color = Color(0xFFFFE0A0)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.requestCamera(null, robust = true) }, label = { Text("Encuadre por geometría") })
                FilterChip(selected = vm.normalizeScale, onClick = { vm.chooseNormalizeScale(!vm.normalizeScale) }, label = { Text("Normalizar escala (×%.3g)".format(m.validation.suggestedScale)) })
                AssistChip(onClick = { vm.outlinerOpen = true; vm.editMode = false }, label = { Text("Mostrar estructura") })
            }
        }
    }
}

@Composable
private fun CameraBar(vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().background(Color(0x990F1115)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun cam(label: String, p: CameraPreset?) = AssistChip(onClick = { vm.requestCamera(p) }, label = { Text(label, fontSize = 12.sp) })
        cam("Frontal", CameraPreset.FRONT); cam("Trasera", CameraPreset.BACK); cam("Lateral", CameraPreset.SIDE)
        cam("Superior", CameraPreset.TOP); cam("3/4", CameraPreset.THREE_QUARTER)
        AssistChip(onClick = { vm.requestCamera(CameraPreset.THREE_QUARTER) }, label = { Text("Reset", fontSize = 12.sp) })
        AssistChip(onClick = { vm.requestCamera(null) }, label = { Text("Auto encuadrar", fontSize = 12.sp) })
        Spacer(Modifier.width(6.dp))
        Text("Pintura:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(0xC9CDD2, 0xF2F2F2, 0x1C1C1E, 0xC62828, 0x1565C0, 0x2E7D32, 0xF9A825).forEach { c ->
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong())).clickable { vm.setPaint(c) })
        }
    }
}
