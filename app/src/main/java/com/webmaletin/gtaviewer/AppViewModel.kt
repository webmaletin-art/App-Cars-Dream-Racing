package com.webmaletin.gtaviewer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.editing.EditSession
import com.webmaletin.gtaviewer.editing.EditState
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.gl.RenderState
import com.webmaletin.gtaviewer.gta.Names
import com.webmaletin.gtaviewer.gta.VehicleLoadException
import com.webmaletin.gtaviewer.io.ExportFiles
import com.webmaletin.gtaviewer.io.ImportedArchive
import com.webmaletin.gtaviewer.io.Storage
import com.webmaletin.gtaviewer.io.UriSource
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.selection.*
import com.webmaletin.gtaviewer.zip.ArchiveEntry
import com.webmaletin.gtaviewer.zip.ByteSource
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, VIEWER, MANUAL, DIAGNOSTICS }
enum class ExportScope { VISIBLE, FULL, SELECTION }
enum class EditTab { ACTIONS, TREE, MATERIALS }

class ManualFile(val label: String, val source: ByteSource)

class ExportResultUi(val file: File, val output: ExportOutput, val suggestedName: String)

class CameraCommand(val id: Int, val preset: CameraPreset?, val robust: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val parser: VehicleFormatParser = FormatRegistry.default
    private val ctx get() = getApplication<Application>()

    // ---- navegación / importación
    var screen by mutableStateOf(Screen.HOME); private set
    var scanProgress by mutableStateOf<ScanProgress?>(null); private set
    var report by mutableStateOf<ScanReport?>(null); private set
    var archiveNames by mutableStateOf<List<String>>(emptyList()); private set
    var query by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var busyText by mutableStateOf<String?>(null); private set
    private val archives = ArrayList<ImportedArchive>()
    private var archiveCounter = 0
    private var scanJob: Job? = null

    // ---- modelo cargado
    var model by mutableStateOf<VehicleModel?>(null); private set
    var currentEntry by mutableStateOf<VehicleEntry?>(null); private set
    var editState by mutableStateOf(EditState(emptySet(), emptySet(), emptySet())); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var isEdited by mutableStateOf(false); private set
    private var edit: EditSession? = null
    var editMode by mutableStateOf(false)
    var multiSelect by mutableStateOf(false)
    var pickMode by mutableStateOf(PickMode.PART)
    var editTab by mutableStateOf(EditTab.ACTIONS)
    var paint by mutableStateOf(PaintScheme()); private set
    var normalizeScale by mutableStateOf(false); private set
    var renderState by mutableStateOf(RenderState(null, emptySet(), emptySet(), PaintScheme(), 1f)); private set
    var cameraCommand by mutableStateOf(CameraCommand(0, null, false)); private set
    var addedParts by mutableStateOf<Set<String>>(emptySet()); private set
    val sceneScale: Float get() = if (normalizeScale) (model?.validation?.suggestedScale ?: 1f) else 1f

    // ---- manual
    var manualDff by mutableStateOf<ManualFile?>(null); private set
    var manualTxd by mutableStateOf<ManualFile?>(null); private set

    // ---- exportación
    var exportResult by mutableStateOf<ExportResultUi?>(null)
    var exporting by mutableStateOf(false); private set
    var pendingSave by mutableStateOf<ExportResultUi?>(null)

    // ---- log
    private val log = ArrayList<String>()
    fun logLines(): List<String> = log.toList()
    private fun log(s: String) {
        log += "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $s"
        if (log.size > 500) log.removeAt(0)
    }

    fun navigate(s: Screen) { screen = s }

    fun back(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.VIEWER -> { closeVehicle(); true }
        else -> { screen = if (model != null && screen == Screen.DIAGNOSTICS) Screen.VIEWER else Screen.HOME; true }
    }

    // ------------------------------------------------------------------ importación de ZIP

    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            closeVehicle()
            scanProgress = ScanProgress("Escaneando ZIP...", 0)
            try {
                val (entries, names) = withContext(Dispatchers.IO) {
                    archives.forEach { it.close() }; archives.clear()
                    Storage.clearImportCache(ctx)
                    val all = ArrayList<ArchiveEntry>()
                    uris.forEachIndexed { i, u ->
                        scanProgress = ScanProgress("Escaneando ZIP...", 2 + 3 * i / uris.size, Storage.displayName(ctx, u))
                        val a = Storage.openZip(ctx, u, archiveCounter++)
                        archives += a
                        all += a.entries
                        log("ZIP abierto: ${a.name} (${a.entries.size} archivos${if (a.copied) ", copiado a caché" else ""})")
                    }
                    all to archives.map { it.name }
                }
                archiveNames = names
                val rep = withContext(Dispatchers.Default) {
                    parser.scan(entries, { p -> scanProgress = p }, { !isActive })
                }
                report = rep
                query = ""
                log("Catálogo: ${rep.vehicles.size} vehículos, ${rep.dffCount} DFF, ${rep.txdCount} TXD, ${rep.orphanTxds.size} TXD sin DFF, ${rep.errors} errores")
                message = "${rep.vehicles.size} vehículos detectados"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("ERROR importando ZIP: ${e.message}")
                message = "No se pudo importar: ${e.message ?: e.javaClass.simpleName}. Pruebe el MODO MANUAL."
            } finally {
                scanProgress = null
            }
        }
    }

    val filteredVehicles: List<VehicleEntry>
        get() {
            val all = report?.vehicles ?: return emptyList()
            val q = query.trim().lowercase()
            if (q.isEmpty()) return all
            val qn = Names.normalize(q)
            return all.filter {
                it.displayName.lowercase().contains(q) || it.dff.path.lowercase().contains(q) || it.txd?.path?.lowercase()?.contains(q) == true ||
                    (qn.isNotEmpty() && Names.normalize(it.displayName).contains(qn))
            }
        }

    /** Todos los DFF/TXD de los ZIP cargados (para el modo manual: elegir cualquier combinación). */
    fun archiveEntries(ext: String): List<ArchiveEntry> =
        archives.flatMap { it.entries }.filter { it.extension == ext }.sortedBy { it.path.lowercase() }

    val hasArchives: Boolean get() = archives.isNotEmpty()

    // ------------------------------------------------------------------ carga de vehículos (uno solo en memoria)

    fun openVehicle(entry: VehicleEntry) {
        viewModelScope.launch { loadModel("Cargando ${entry.displayName}...", entry) { parser.load(entry) } }
    }

    private suspend fun loadModel(text: String, entry: VehicleEntry?, block: () -> VehicleModel) {
        busyText = text
        // Libera por completo el modelo anterior antes de cargar otro.
        releaseModel()
        try {
            val m = withContext(Dispatchers.Default) { block() }
            model = m; currentEntry = entry
            edit = EditSession(m)
            addedParts = emptySet()
            editMode = false; multiSelect = false; pickMode = PickMode.PART; editTab = EditTab.ACTIONS
            normalizeScale = false
            refreshEdit()
            screen = Screen.VIEWER
            requestCamera(CameraPreset.THREE_QUARTER)
            log("Cargado '${m.name}': ${m.parts.size} piezas, ${m.totalVertices} vértices, ${m.totalTriangles} triángulos, avisos=${m.warnings.size}, revisión=${m.validation.needsReview}")
            if (m.validation.needsReview) message = "Modelo requiere revisión"
        } catch (e: VehicleLoadException) {
            log("ERROR cargando: ${e.message} | ${e.diagnostics}")
            lastLoadError = e.diagnostics
            message = "No se pudo cargar: ${e.message}"
        } catch (e: OutOfMemoryError) {
            releaseModel()
            log("ERROR memoria insuficiente cargando el modelo")
            message = "Memoria insuficiente para cargar este modelo"
        } catch (e: Throwable) {
            log("ERROR cargando: ${e.javaClass.simpleName}: ${e.message}")
            lastLoadError = "${e.javaClass.simpleName}: ${e.message}"
            message = "Error al cargar el modelo: ${e.message}"
        } finally {
            busyText = null
        }
    }

    var lastLoadError by mutableStateOf<String?>(null); private set

    private fun releaseModel() {
        model = null; edit = null; currentEntry = null
        renderState = RenderState(null, emptySet(), emptySet(), paint, 1f) // el renderer libera VBO/IBO/texturas
        editState = EditState(emptySet(), emptySet(), emptySet())
    }

    fun closeVehicle() {
        releaseModel()
        screen = Screen.HOME
    }

    // ------------------------------------------------------------------ edición

    private fun refreshEdit() {
        val e = edit ?: return
        editState = e.state; canUndo = e.canUndo; canRedo = e.canRedo; isEdited = e.isEdited()
        renderState = RenderState(e.model, e.visibleIds(), e.state.selection, paint, sceneScale)
    }

    private fun withEdit(block: EditSession.() -> Unit) { edit?.block(); refreshEdit() }

    fun hide() = withEdit { hide() }
    fun delete() = withEdit { delete() }
    fun isolate() = withEdit { isolate() }
    fun showAll() = withEdit { showAll() }
    fun restoreDeleted() = withEdit { restoreDeleted() }
    fun undo() = withEdit { undo() }
    fun redo() = withEdit { redo() }
    fun resetAll() = withEdit { resetAll() }
    fun clearSelection() = withEdit { clearSelection() }
    fun select(ids: Set<Int>, add: Boolean = false) = withEdit { if (add) select(state.selection + ids) else select(ids) }
    fun toggleSelect(ids: Set<Int>) = withEdit { toggle(ids) }
    fun setVisible(ids: Set<Int>, visible: Boolean) = withEdit { if (visible) show(ids) else hide(ids) }

    fun selectSimilar() {
        val m = model ?: return
        val first = editState.selection.firstOrNull() ?: return
        select(SelectionTools.similar(m, first))
    }

    fun selectCategory(c: PartCategory) { model?.let { select(SelectionTools.byCategory(it, c).filter { id -> edit?.state?.deleted?.contains(id) != true }.toSet()) } }

    fun selectMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)) } }

    fun hideMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)); hide() } }
    fun deleteMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)); delete() } }

    fun pick(x: Float, y: Float, w: Int, h: Int, rayProvider: () -> com.webmaletin.gtaviewer.selection.Ray) {
        val m = model ?: return
        val e = edit ?: return
        val ray = rayProvider()
        val scale = sceneScale
        viewModelScope.launch {
            val hit = withContext(Dispatchers.Default) {
                RayPicker.pick(m, ray, { sm -> e.isVisible(sm.id) }, Mat4.scale(scale))
            }
            if (model !== m) return@launch
            if (hit == null) { if (!multiSelect) clearSelection(); return@launch }
            val ids = SelectionTools.expand(m, hit.subMeshId, pickMode)
            if (multiSelect) toggleSelect(ids) else if (editState.selection == ids) clearSelection() else select(ids)
        }
    }

    fun choosePickMode(mode: PickMode) { pickMode = mode }

    // ------------------------------------------------------------------ cámara / escena

    fun requestCamera(preset: CameraPreset?, robust: Boolean = false) {
        cameraCommand = CameraCommand(cameraCommand.id + 1, preset, robust)
    }

    /** Caja (con escala de escena) de lo que se ve; con [robust] ignora piezas muy lejanas ("encuadre por geometría"). */
    fun frameBounds(robust: Boolean): Bounds? {
        val m = model ?: return null
        val e = edit ?: return null
        val b: Bounds? = if (robust && m.validation.robustBounds != null) m.validation.robustBounds
        else {
            val partIds = m.parts.filter { p -> p.subMeshes.any { e.isVisible(it.id) } }.map { it.id }.toSet()
            m.boundsOf(partIds) ?: m.fullBounds()
        }
        b ?: return null
        val s = sceneScale
        return Bounds(FloatArray(3) { b.min[it] * s }, FloatArray(3) { b.max[it] * s })
    }

    fun chooseNormalizeScale(on: Boolean) {
        normalizeScale = on; refreshEdit(); requestCamera(null)
    }

    fun setPaint(primary: Int) { paint = paint.copy(primary = primary); refreshEdit() }

    // ------------------------------------------------------------------ piezas relacionadas

    fun addRelatedPart(part: RelatedPart) {
        val m = model ?: return
        val e = edit ?: return
        viewModelScope.launch {
            busyText = "Añadiendo ${part.dff.fileName}..."
            try {
                val newModel = withContext(Dispatchers.Default) { parser.addRelatedPart(m, part) }
                if (model === m) {
                    edit = e.rebase(newModel); model = newModel
                    addedParts = addedParts + part.dff.path
                    refreshEdit()
                    log("Pieza añadida: ${part.dff.path}")
                }
            } catch (ex: Throwable) {
                log("ERROR añadiendo pieza ${part.dff.path}: ${ex.message}")
                message = "No se pudo añadir la pieza: ${ex.message}"
            } finally { busyText = null }
        }
    }

    // ------------------------------------------------------------------ modo manual

    fun setManualDffFromEntry(e: ArchiveEntry) { manualDff = ManualFile(e.path + "  [${e.archiveName}]", e.source) }
    fun setManualTxdFromEntry(e: ArchiveEntry) { manualTxd = ManualFile(e.path + "  [${e.archiveName}]", e.source) }
    fun setManualDffFromUri(u: Uri) { val n = Storage.displayName(ctx, u); manualDff = ManualFile(n, UriSource(ctx, u, n)) }
    fun setManualTxdFromUri(u: Uri) { val n = Storage.displayName(ctx, u); manualTxd = ManualFile(n, UriSource(ctx, u, n)) }
    fun clearManualTxd() { manualTxd = null }
    fun clearManual() { manualDff = null; manualTxd = null }

    fun loadManual(withTxd: Boolean) {
        val dff = manualDff ?: return
        val txd = if (withTxd) manualTxd else null
        val name = Names.pretty(dff.source.displayName.substringBeforeLast('.'))
        log("Modo manual: DFF=${dff.label} TXD=${txd?.label ?: "(ninguno)"}")
        viewModelScope.launch { loadModel("Cargando $name...", null) { parser.loadManual(name, dff.source, txd?.source) } }
    }

    // ------------------------------------------------------------------ exportación

    fun selectionLabel(): String? {
        val m = model ?: return null
        val parts = editState.selection.mapNotNull { m.partOf(it) }.distinctBy { it.id }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0].name
        val cats = parts.map { it.category }.distinct()
        if (cats.size == 1) return when (cats[0]) {
            PartCategory.WHEEL -> "ruedas"; PartCategory.DOOR -> "puertas"; PartCategory.HOOD -> "capo"; PartCategory.TRUNK -> "baul"
            PartCategory.MIRROR -> "espejos"; PartCategory.SPOILER -> "spoiler"; PartCategory.BUMPER -> "paragolpes"
            PartCategory.INTERIOR -> "interior"; PartCategory.ENGINE -> "motor"; PartCategory.EXHAUST -> "escape"
            PartCategory.SUSPENSION -> "suspension"; PartCategory.LIGHT -> "luces"; PartCategory.WINDOW -> "cristales"
            PartCategory.FENDER -> "guardabarros"; PartCategory.BODY -> "carroceria"; PartCategory.OTHER -> "piezas"
        }
        return "seleccion"
    }

    fun export(format: ExportFormat, scope: ExportScope) {
        val m = model ?: return
        val e = edit ?: return
        if (exporting) return
        val ids: Set<Int> = when (scope) {
            ExportScope.VISIBLE -> e.visibleIds()
            ExportScope.FULL -> m.parts.filter { it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
            ExportScope.SELECTION -> e.state.selection - e.state.deleted
        }
        if (ids.isEmpty()) { message = "No hay piezas para exportar"; return }
        val label = if (scope == ExportScope.SELECTION) selectionLabel() else null
        val edited = scope == ExportScope.VISIBLE && e.isEdited()
        val base = ExportNaming.baseName(m.name, edited, label)
        val paintNow = paint; val scale = sceneScale
        viewModelScope.launch {
            exporting = true
            busyText = "Exportando ${format.label}..."
            try {
                val out = withContext(Dispatchers.Default) {
                    val scene = ExportSceneBuilder.build(m, ids, paintNow, scale, base)
                    Exporters.forFormat(format).export(scene, base)
                }
                val file = withContext(Dispatchers.IO) { ExportFiles.cacheFile(ctx, out.fileName).also { it.writeBytes(out.bytes) } }
                log("Exportado ${out.fileName} (${out.bytes.size / 1024} KB; ${out.entries.size} archivo(s))${out.limitations?.let { " | $it" } ?: ""}")
                val res = ExportResultUi(file, out, out.fileName)
                exportResult = res
                pendingSave = res
            } catch (ex: OutOfMemoryError) {
                message = "Memoria insuficiente al exportar; oculte o elimine piezas e inténtelo de nuevo"
                log("ERROR exportando: memoria insuficiente")
            } catch (ex: Throwable) {
                message = "Error al exportar: ${ex.message}"
                log("ERROR exportando: ${ex.javaClass.simpleName}: ${ex.message}")
            } finally {
                exporting = false; busyText = null
            }
        }
    }

    fun saveExportTo(uri: Uri) {
        val res = pendingSave ?: exportResult ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ExportFiles.copyTo(ctx, res.file, uri) }
                message = "Guardado: ${res.suggestedName}"
                log("Guardado ${res.suggestedName} en $uri")
            } catch (e: Throwable) {
                message = "No se pudo guardar: ${e.message}"
                log("ERROR guardando: ${e.message}")
            } finally { pendingSave = null }
        }
    }

    // ------------------------------------------------------------------ diagnóstico

    fun diagnosticsText(): String = buildString {
        appendLine("=== GTA Vehicle Viewer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
        appendLine("Módulo: ${parser.module.displayName}")
        appendLine("ZIP cargados: ${archiveNames.joinToString().ifEmpty { "ninguno" }}")
        appendLine()
        report?.let { appendLine("--- ESCANEO ---"); appendLine(it.toLogText()) } ?: appendLine("(Sin escaneo de ZIP)")
        model?.let { m ->
            appendLine("--- MODELO: ${m.name} ---")
            appendLine(m.diagnostics.toText())
            appendLine("Piezas: ${m.parts.size}; vértices: ${m.totalVertices}; triángulos: ${m.totalTriangles}; materiales: ${m.materials.size}")
            appendLine("Validación: ${if (m.validation.needsReview) "REQUIERE REVISIÓN" else "correcta"}")
            m.validation.reasons.forEach { appendLine("  - $it") }
            if (m.warnings.isNotEmpty()) { appendLine("Avisos:"); m.warnings.forEach { appendLine("  - $it") } }
        }
        lastLoadError?.let { appendLine("--- ÚLTIMO ERROR DE CARGA ---"); appendLine(it) }
        appendLine("--- REGISTRO DE SESIÓN ---")
        log.forEach { appendLine(it) }
    }

    override fun onCleared() {
        archives.forEach { it.close() }; archives.clear()
        Storage.clearImportCache(ctx)
    }
}
