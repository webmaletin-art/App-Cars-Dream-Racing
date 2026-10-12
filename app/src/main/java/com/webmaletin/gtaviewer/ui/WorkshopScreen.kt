package com.webmaletin.gtaviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.Screen
import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.gl.GlViewerView
import com.webmaletin.gtaviewer.model.Bounds

private enum class Tab(val label: String) { PARTS("PIEZAS"), ADD("AÑADIR"), MOVE("MOVER"), MAT("MATERIAL"), FUSE("AJUSTAR Y FUSIONAR"), FACES("CARAS / LOGOS"), EXPORT("EXPORTAR") }

@Composable
fun WorkshopScreen(app: AppViewModel, lab: LabViewModel) {
    val snap = lab.ws
    if (snap == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No hay proyecto abierto"); }; return }
    var gl by remember { mutableStateOf<GlViewerView?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.PARTS) }
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var rename by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, gl) {
        val obs = LifecycleEventObserver { _, ev -> when (ev) { Lifecycle.Event.ON_PAUSE -> gl?.onPause(); Lifecycle.Event.ON_RESUME -> gl?.onResume(); else -> {} } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val bounds: () -> Bounds? = { snap.model.fullBounds() }
    // encuadre al abrir / al pedir (cameraTick)
    LaunchedEffect(gl, lab.cameraTick) { gl?.let { v -> synchronized(v.camera) { v.camera.setPreset(CameraPreset.THREE_QUARTER) }; v.frame(bounds()) } }
    LaunchedEffect(gl, lab.dragMove, lab.selection) { gl?.dragMode = lab.dragMove && lab.selection.isNotEmpty() }

    if (lab.missingResources.isNotEmpty()) {
        AlertDialog(onDismissRequest = { }, containerColor = Panel, title = { Text("Faltan recursos") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("El proyecto reabrió, pero estos recursos ya no están en el catálogo (se omiten, nada se inventa):", fontSize = 13.sp); lab.missingResources.forEach { Text("• $it", fontSize = 12.sp) } } },
            confirmButton = { Button(onClick = { lab.dismissMissing() }) { Text("ENTENDIDO") } })
    }
    lab.report?.let { r ->
        AlertDialog(onDismissRequest = { lab.report = null }, containerColor = Panel, title = { Text("Informe del generador") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(r, fontSize = 12.sp) } },
            confirmButton = { Button(onClick = { lab.report = null }) { Text("CERRAR") } })
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GlViewerView(ctx).also { v ->
                    v.onTap = { x, y, w, h -> lab.pickAt(synchronized(v.camera) { v.camera.rayFor(x, y, w, h) }) }
                    v.onDoubleTap = { v.frame(bounds()) }
                    v.onDrag = { x0, y0, x1, y1, w, h ->
                        val rays = synchronized(v.camera) { Triple(v.camera.rayFor(x0, y0, w, h), v.camera.rayFor(x1, y1, w, h), v.camera.rayFor(w / 2f, h / 2f, w, h)) }
                        lab.dragBy(rays.first, rays.second, rays.third)
                    }
                    v.onDragEnd = { lab.dragEnd() }
                    gl = v
                }
            },
            update = { it.update(lab.renderState) },
            modifier = Modifier.fillMaxSize(),
        )
        // barra superior
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0xCC0F1115)).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { lab.closeProject(); app.navigate(Screen.PROJECTS) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("←", fontSize = 18.sp) }
                Column(Modifier.weight(1f)) {
                    Text(snap.state.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${snap.state.parts.count { !it.deleted }} piezas · base: ${snap.state.baseVehicleId?.let { lab.store.get(it)?.name } ?: "— sin base —"} · guardado automático", fontSize = 10.sp, color = Accent, maxLines = 1)
                }
                TextButton(onClick = { lab.undo() }, enabled = snap.canUndo, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↶", fontSize = 20.sp) }
                TextButton(onClick = { lab.redo() }, enabled = snap.canRedo, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↷", fontSize = 20.sp) }
                TextButton(onClick = { app.navigate(Screen.TEXTURES) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("TEXTURAS", fontSize = 11.sp) }
                TextButton(onClick = { rename = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("RENOMBRAR", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth().background(Color(0x990F1115)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun cam(l: String, p: CameraPreset) = AssistChip(onClick = { gl?.let { v -> synchronized(v.camera) { v.camera.setPreset(p) }; v.frame(bounds()) } }, label = { Text(l, fontSize = 11.sp) })
                cam("Frontal", CameraPreset.FRONT); cam("Trasera", CameraPreset.BACK); cam("Lateral", CameraPreset.SIDE); cam("Superior", CameraPreset.TOP); cam("3/4", CameraPreset.THREE_QUARTER)
                AssistChip(onClick = { gl?.frame(bounds()) }, label = { Text("Encuadrar", fontSize = 11.sp) })
                AssistChip(onClick = { lab.selectionBounds()?.let { b -> gl?.frame(b) } ?: run { lab.message = "Seleccione una pieza para enfocarla" } }, label = { Text("Enfocar selección", fontSize = 11.sp) })
                FilterChip(selected = lab.multiSelect, onClick = { lab.multiSelect = !lab.multiSelect }, label = { Text("Multi-selección", fontSize = 11.sp) })
                FilterChip(selected = lab.dragMove, onClick = { lab.dragMove = !lab.dragMove }, label = { Text("Mover con el dedo", fontSize = 11.sp) })
            }
        }
        // panel inferior
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            val h = LocalConfiguration.current.screenHeightDp
            Surface(color = Color(0xF2181C23), shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { panelOpen = !panelOpen }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (panelOpen) "▼" else "▲", fontSize = 16.sp) }
                        Tab.values().forEach { t ->
                            TextButton(onClick = { tab = t; panelOpen = true; if (t == Tab.ADD) lab.refreshCandidates() }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(t.label, fontSize = 12.sp, fontWeight = if (t == tab) FontWeight.ExtraBold else FontWeight.Normal, color = if (t == tab) Accent else Color(0xFFCCD0D8))
                            }
                        }
                    }
                    if (panelOpen) Box(Modifier.fillMaxWidth().heightIn(max = (h * 0.46f).dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        when (tab) {
                            Tab.PARTS -> PartsPanel(lab, snap, gl)
                            Tab.ADD -> AddPanel(lab, snap)
                            Tab.MOVE -> MovePanel(lab, snap)
                            Tab.MAT -> MaterialPanel(lab, snap)
                            Tab.FUSE -> FusePanel(lab, snap)
                            Tab.FACES -> FacesPanel(lab, snap)
                            Tab.EXPORT -> ExportPanel(lab, snap)
                        }
                    }
                }
            }
        }
        // información de la selección
        if (lab.selection.isNotEmpty() && !panelOpen) {
            val p = lab.partOf(lab.selection.first())
            if (p != null) Surface(color = Color(0xE6181C23), shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.CenterStart).padding(10.dp)) {
                Text(if (lab.selection.size > 1) "${lab.selection.size} piezas" else p.name, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
    if (rename) TextPromptDialog("Nombre del proyecto", snap.state.name, onOk = { lab.renameProject(it) }, onDismiss = { rename = false })
}
