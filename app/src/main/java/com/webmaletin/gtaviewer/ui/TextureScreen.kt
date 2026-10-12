package com.webmaletin.gtaviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.TexTool
import com.webmaletin.gtaviewer.texture.BrushMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun TextureScreen(app: AppViewModel, lab: LabViewModel) {
    if (!lab.hasProject) {
        Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeader("EDITOR DE TEXTURAS", null, onBack = { app.back() })
            Text("Abra un proyecto para editar las texturas de sus piezas.", modifier = Modifier.padding(30.dp))
        }
        return
    }
    if (lab.texDoc == null) SlotPicker(app, lab) else Editor(app, lab)
}

@Composable
private fun SlotPicker(app: AppViewModel, lab: LabViewModel) {
    val slots = lab.texSlots()
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader("EDITOR DE TEXTURAS", "Elija la textura a editar. La original importada nunca se modifica: se edita una copia del proyecto.", onBack = { app.back() })
        if (slots.isEmpty()) Text("Las piezas del proyecto no tienen texturas (solo colores de material). Use el panel MATERIAL del taller.", modifier = Modifier.padding(20.dp), color = Warn)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(slots, key = { "${it.partId}/${it.subIndex}" }) { s ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.textureName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${s.partName} · ${s.width}×${s.height}${if (s.edited) " · ya editable" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { lab.openTexture(s.partId, s.subIndex) }) { Text("EDITAR") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(app: AppViewModel, lab: LabViewModel) {
    val doc = lab.texDoc ?: return
    val bmp = lab.texBitmap ?: return
    var scale by remember(doc) { mutableStateOf(1f) }
    var offset by remember(doc) { mutableStateOf(Offset.Zero) }
    var inited by remember(doc) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var preview by remember { mutableStateOf<Pair<Offset, Offset>?>(null) } // en píxeles de textura
    var confirmClose by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { u -> if (u != null) lab.texExportPng(u) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) lab.texLoadStamp(u) }
    val uvPath = remember(lab.uvWire) {
        lab.uvWire?.let { w -> Path().also { p -> var i = 0; while (i + 5 < w.size) { p.moveTo(w[i], w[i + 1]); p.lineTo(w[i + 2], w[i + 3]); p.lineTo(w[i + 4], w[i + 5]); p.close(); i += 6 } } }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        ScreenHeader(lab.texId?.let { doc.name } ?: "Textura", "${doc.width}×${doc.height}${if (lab.texDirty) " · cambios sin guardar en el proyecto" else ""}", onBack = { if (lab.texDirty) confirmClose = true else lab.texClose() }) {
            TextButton(onClick = { lab.texUndo() }, enabled = doc.canUndo, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↶", fontSize = 20.sp) }
            TextButton(onClick = { lab.texRedo() }, enabled = doc.canRedo, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("↷", fontSize = 20.sp) }
            Button(onClick = { lab.texCommit() }, enabled = lab.texDirty, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("GUARDAR", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        // lienzo
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0D11)).clipToBounds().onSizeChanged { size = it; if (!inited && it.width > 0) { val s = min(it.width.toFloat() / doc.width, it.height.toFloat() / doc.height) * 0.96f; scale = s; offset = Offset((it.width - doc.width * s) / 2, (it.height - doc.height * s) / 2); inited = true } }) {
            val tool = lab.texTool
            Canvas(Modifier.fillMaxSize().testTag("texCanvas").pointerInput(tool, doc) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun tex(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
                    val start = tex(down.position)
                    var last = start
                    var multi = false
                    var drawing = false
                    val free = tool == TexTool.BRUSH || tool == TexTool.ERASER || tool == TexTool.RESTORE
                    val mode = when (tool) { TexTool.ERASER -> BrushMode.ERASE; TexTool.RESTORE -> BrushMode.RESTORE; else -> BrushMode.PAINT }
                    val radius = max(0.5f, lab.texSize / 2f)
                    fun rectOf(a: Offset, b: Offset, pad: Float) = intArrayOf((min(a.x, b.x) - pad).toInt() - 1, (min(a.y, b.y) - pad).toInt() - 1, (max(a.x, b.x) + pad).toInt() + 2, (max(a.y, b.y) + pad).toInt() + 2)
                    if (free) {
                        lab.texBegin(tool.label); drawing = true
                        val c = lab.texColor; val op = lab.texOpacity; val hd = lab.texHardness
                        lab.texLive(rectOf(start, start, radius)) { d -> d.stroke(start.x, start.y, start.x, start.y, radius, c, op, hd, mode) }
                    }
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (ev.changes.size >= 2 && pressed >= 2) {
                            if (drawing) { lab.texEnd(); drawing = false }
                            multi = true; preview = null
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val cen = ev.calculateCentroid()
                            val ns = (scale * zoom).coerceIn(0.03f, 60f)
                            offset = cen - (cen - offset) * (ns / scale) + pan; scale = ns
                            ev.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = ev.changes.firstOrNull() ?: break
                            val cur = tex(ch.position)
                            if (free && drawing) {
                                val a = last; val c = lab.texColor; val op = lab.texOpacity; val hd = lab.texHardness
                                lab.texLive(rectOf(a, cur, radius)) { d -> d.stroke(a.x, a.y, cur.x, cur.y, radius, c, op, hd, mode) }
                            } else if (tool == TexTool.LINE || tool == TexTool.RECT || tool == TexTool.ELLIPSE || tool == TexTool.INPAINT) preview = start to cur
                            last = cur
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    if (drawing) lab.texEnd()
                    if (!multi && !free) {
                        val end = last; val c = lab.texColor; val op = lab.texOpacity
                        val x0 = start.x.toInt(); val y0 = start.y.toInt(); val x1 = end.x.toInt(); val y1 = end.y.toInt()
                        when (tool) {
                            TexTool.LINE -> lab.texOp("línea", rectOf(start, end, lab.texSize)) { d -> d.line(start.x, start.y, end.x, end.y, lab.texSize, c, op) }
                            TexTool.RECT -> lab.texOp("rectángulo", rectOf(start, end, 2f)) { d -> d.rect(x0, y0, x1, y1, c, op, lab.texFilled, max(1, lab.texSize.toInt() / 2)) }
                            TexTool.ELLIPSE -> lab.texOp("elipse", rectOf(start, end, lab.texSize)) { d -> d.ellipse((start.x + end.x) / 2, (start.y + end.y) / 2, max(1f, abs(end.x - start.x) / 2), max(1f, abs(end.y - start.y) / 2), c, op, lab.texFilled, max(1f, lab.texSize / 2)) }
                            TexTool.FILL -> lab.texOp("relleno", null) { d -> d.fill(x0, y0, c, op, 24) }
                            TexTool.INPAINT -> { val rx = min(x0, x1); val ry = min(y0, y1); val rw = abs(x1 - x0).coerceAtLeast(2); val rh = abs(y1 - y0).coerceAtLeast(2)
                                lab.texOp("quitar logo", intArrayOf(rx - 1, ry - 1, rx + rw + 1, ry + rh + 1)) { d -> d.inpaint(rx, ry, rw, rh) } }
                            TexTool.STAMP, TexTool.TEXT -> {
                                val s = if (tool == TexTool.TEXT) lab.texMakeText() else lab.texStamp
                                if (s == null) lab.message = "Cargue primero una imagen (IMAGEN…)"
                                else { val sc = lab.texStampScale; val rot = lab.texStampRot; val ext = (max(s.second, s.third) * sc).toInt()
                                    lab.texOp("sello", intArrayOf(x0 - ext, y0 - ext, x0 + ext, y0 + ext)) { d -> d.stamp(s.first, s.second, s.third, start.x, start.y, sc, rot, op) } }
                            }
                            TexTool.PICK -> lab.texPixel(x0, y0)?.let { lab.texColor = it }
                            else -> {}
                        }
                    }
                    preview = null
                }
            }) {
                lab.texRev
                withTransform({ translate(offset.x, offset.y); scale(scale, scale, Offset.Zero) }) {
                    // damero para distinguir transparencia
                    drawRect(Color(0xFF2B2F38), Offset.Zero, Size(doc.width.toFloat(), doc.height.toFloat()))
                    val cs = max(8f, min(doc.width, doc.height) / 32f)
                    var yy = 0f; var row = 0
                    while (yy < doc.height) { var xx = if (row % 2 == 0) 0f else cs; while (xx < doc.width) { drawRect(Color(0xFF3A3F4A), Offset(xx, yy), Size(min(cs, doc.width - xx), min(cs, doc.height - yy))); xx += cs * 2 }; yy += cs; row++ }
                    drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(doc.width, doc.height).let { it }, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                    if (lab.showUv && uvPath != null) {
                        withTransform({ scale(doc.width.toFloat(), doc.height.toFloat(), Offset.Zero) }) { drawPath(uvPath, Color(0x99FFC857), style = Stroke(width = 1f / (scale * doc.width))) }
                    }
                    preview?.let { (a, b) ->
                        when (tool) {
                            TexTool.LINE -> drawLine(Color(lab.texColor or 0xFF000000.toInt()), a, b, strokeWidth = lab.texSize)
                            TexTool.ELLIPSE -> drawOval(Color(0xAAFFFFFF), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(2f / scale))
                            else -> drawRect(if (tool == TexTool.INPAINT) Color(0x88FF3B30) else Color(0xAAFFFFFF), Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(2f / scale))
                        }
                    }
                }
            }
        }
        ToolPanel(lab, pick = { pick.launch("image/*") }, onExport = { png.launch("${doc.name}.png") }, onRestore = { confirmRestore = true })
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, containerColor = Panel, title = { Text("Cambios sin guardar") },
        text = { Text("Hay cambios en la textura que aún no están guardados en el proyecto.") },
        confirmButton = { Button(onClick = { lab.texCommit(); lab.texClose(); confirmClose = false }) { Text("GUARDAR Y SALIR") } },
        dismissButton = { Row { TextButton(onClick = { lab.texClose(); confirmClose = false }) { Text("DESCARTAR", color = Bad) }; TextButton(onClick = { confirmClose = false }) { Text("SEGUIR") } } })
    if (confirmRestore) ConfirmDialog("Restaurar textura original", "Se descartan todos los cambios de esta textura en el proyecto y vuelve la importada.", "RESTAURAR", danger = true, onConfirm = { lab.texRestoreOriginal() }, onDismiss = { confirmRestore = false })
}

@Composable
private fun ToolPanel(lab: LabViewModel, pick: () -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    val doc = lab.texDoc ?: return
    var tab by remember { mutableStateOf(0) }
    var hex by remember { mutableStateOf(false) }
    var islandIdx by remember { mutableStateOf(0) }
    Surface(color = Color(0xF2181C23)) {
        Column(Modifier.heightIn(max = 260.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("HERRAMIENTAS", "CAPAS", "UV", "ARCHIVO").forEachIndexed { i, t -> TextButton(onClick = { tab = i }) { Text(t, fontSize = 12.sp, fontWeight = if (tab == i) FontWeight.ExtraBold else FontWeight.Normal, color = if (tab == i) Accent else Color(0xFFCCD0D8)) } }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (tab) {
                    0 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TexTool.values().forEach { t -> FilterChip(selected = lab.texTool == t, onClick = { lab.texTool = t }, label = { Text(t.label, fontSize = 11.sp) }) }
                        }
                        when (lab.texTool) {
                            TexTool.INPAINT -> Text("Dibuje un rectángulo sobre el logo: se rellena SOLO esa zona con el color del entorno (el resto del diseño no cambia).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TexTool.RESTORE -> Text("Devuelve el píxel original importado donde pinte.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TexTool.STAMP -> {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = pick, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("IMAGEN… (PNG/JPG)", fontSize = 12.sp) }
                                    Text(lab.texStamp?.let { "cargada ${it.second}×${it.third}" } ?: "sin imagen", fontSize = 11.sp)
                                }
                                LabeledSlider("Tamaño del sello", lab.texStampScale, 0.05f..4f, "×${"%.2f".format(lab.texStampScale)}", { lab.texStampScale = it })
                                LabeledSlider("Rotación", lab.texStampRot, -180f..180f, "${lab.texStampRot.toInt()}°", { lab.texStampRot = it })
                            }
                            TexTool.TEXT -> {
                                OutlinedTextField(value = lab.texText, onValueChange = { lab.texText = it }, singleLine = true, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth())
                                LabeledSlider("Tamaño", lab.texStampScale, 0.1f..4f, "×${"%.2f".format(lab.texStampScale)}", { lab.texStampScale = it })
                                LabeledSlider("Rotación", lab.texStampRot, -180f..180f, "${lab.texStampRot.toInt()}°", { lab.texStampRot = it })
                            }
                            else -> {}
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(30.dp).background(Color(0xFF000000 or lab.texColor.toLong())).then(Modifier.clickableNoRipple { hex = true }))
                            SwatchRow { lab.texColor = it }
                        }
                        LabeledSlider("Tamaño", lab.texSize, 1f..300f, "${lab.texSize.toInt()} px", { lab.texSize = it })
                        LabeledSlider("Opacidad", lab.texOpacity, 0.05f..1f, "${(lab.texOpacity * 100).toInt()} %", { lab.texOpacity = it })
                        if (lab.texTool == TexTool.BRUSH || lab.texTool == TexTool.ERASER || lab.texTool == TexTool.RESTORE) LabeledSlider("Dureza", lab.texHardness, 0f..1f, "${(lab.texHardness * 100).toInt()} %", { lab.texHardness = it })
                        if (lab.texTool == TexTool.RECT || lab.texTool == TexTool.ELLIPSE) Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.texFilled, { lab.texFilled = it }); Spacer(Modifier.width(8.dp)); Text("Relleno", fontSize = 12.sp) }
                    }
                    1 -> {
                        Text("Capa activa: ${doc.layers.getOrNull(doc.active)?.name}. La capa 0 es la textura base.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        lab.texRev
                        doc.layers.forEachIndexed { i, l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = doc.active == i, onClick = { lab.texActiveLayer(i) })
                                Text(l.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { lab.texLayerVisible(i, !l.visible) }) { Text(if (l.visible) "👁" else "🚫") }
                                if (i > 0) TextButton(onClick = { lab.texRemoveLayer(i) }) { Text("✕", color = Bad) }
                            }
                            Slider(value = l.opacity, onValueChange = { lab.texLayerOpacity(i, it) }, modifier = Modifier.height(24.dp))
                        }
                        Button(onClick = { lab.texAddLayer("Capa ${doc.layers.size}") }) { Text("＋ CAPA NUEVA", fontSize = 12.sp) }
                    }
                    2 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(lab.showUv, { lab.showUv = it }); Spacer(Modifier.width(8.dp)); Text("Mostrar mapa UV sobre la textura", fontSize = 12.sp) }
                        val islands = remember(lab.uvWire) { lab.uvIslands() }
                        Text("${islands.size} islas UV en esta parte.", fontSize = 12.sp)
                        lab.uvStretchText()?.let { Text("Estiramiento: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (islands.isNotEmpty()) {
                            LabeledSlider("Isla", islandIdx.toFloat(), 0f..(islands.size - 1).coerceAtLeast(1).toFloat(), "#$islandIdx", { islandIdx = it.toInt().coerceIn(0, islands.size - 1) })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, -0.01f, 0f, 1f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("←") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0.01f, 0f, 1f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("→") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0f, -0.01f, 1f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("↑") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0f, 0.01f, 1f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("↓") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0f, 0f, 1.05f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("＋ escala") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0f, 0f, 0.95f, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("− escala") }
                                OutlinedButton(onClick = { lab.uvTransform(islandIdx, 0f, 0f, 1f, 15f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⟳ 15°") }
                            }
                            Text("Edición básica por isla; crea una copia de la geometría (restaurable con «Restaurar geometría» en AJUSTAR Y FUSIONAR). No es un desenvolvedor de UV.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    3 -> {
                        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("EXPORTAR PNG", fontSize = 12.sp) }
                        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("RESTAURAR TEXTURA ORIGINAL", fontSize = 12.sp) }
                        OutlinedButton(onClick = { lab.texClose() }, modifier = Modifier.fillMaxWidth(), enabled = !lab.texDirty) { Text("ELEGIR OTRA TEXTURA", fontSize = 12.sp) }
                        Text("GUARDAR escribe los píxeles en la textura del proyecto (el visor 3D se actualiza y el GLB incluye el cambio). Las variantes de pintura se guardan en MATERIAL.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (hex) {
        var t by remember { mutableStateOf("%06X".format(lab.texColor and 0xFFFFFF)) }
        AlertDialog(onDismissRequest = { hex = false }, containerColor = Panel, title = { Text("Color (hex RRGGBB)") },
            text = { OutlinedTextField(value = t, onValueChange = { t = it.take(6) }, singleLine = true) },
            confirmButton = { Button(onClick = { t.toIntOrNull(16)?.let { lab.texColor = it and 0xFFFFFF }; hex = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { hex = false }) { Text("CANCELAR") } })
    }
}
