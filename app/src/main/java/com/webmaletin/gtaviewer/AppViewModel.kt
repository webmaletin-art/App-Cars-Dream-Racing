package com.webmaletin.gtaviewer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webmaletin.gtaviewer.camera.CameraPreset
import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.editing.EditSession
import com.webmaletin.gtaviewer.editing.EditState
import com.webmaletin.gtaviewer.editing.GamePrep
import com.webmaletin.gtaviewer.editing.PrepOptions
import com.webmaletin.gtaviewer.editing.VehicleConfig
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
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, VIEWER, MANUAL, DIAGNOSTICS }
enum class ExportScope { VISIBLE, FULL, SELECTION }
enum class EditTab { ACTIONS, MATERIALS }

class ManualFile(val label: String, val source: com.webmaletin.gtaviewer.zip.ByteSource)

class ExportResultUi(val file: File, val output: ExportOutput, val suggestedName: String)

class CameraCommand(val id: Int, val preset: CameraPreset?, val robust: Boolean)

enum class CatalogFilter(val label: String) {
    ALL("Todos"), COMPATIBLE("Compatibles"), WITH_TXD("Con TXD"), NO_TXD("Sin TXD"),
    ERRORS("Con errores"), SELECTED("Seleccionados"), READY("Listos"),
}

class BatchLine(val name: String, val state: BatchState, val message: String?)

class BatchUi(
    val total: Int, val done: Int, val lines: List<BatchLine>, val finished: Boolean,
    val result: BatchResult?, val zipFile: File?, val zipName: String, val format: ExportFormat,
)

class BatchConfirm(val items: List<CatalogItem>, val unreviewed: Int)

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

    // ---- catálogo (solo referencias y metadatos; la geometría se carga bajo demanda)
    var items by mutableStateOf<List<CatalogItem>>(emptyList()); private set
    private var manualItems = listOf<CatalogItem>()
    var filter by mutableStateOf(CatalogFilter.ALL)
    var selectedIds by mutableStateOf<Set<String>>(emptySet()); private set
    val workflow = mutableStateMapOf<String, WorkflowStatus>()
    val itemErrors = mutableStateMapOf<String, String>()
    /** Configuración de visibilidad de CADA vehículo (en memoria mientras la sesión/ZIP siga abierto). */
    private val configs = HashMap<String, VehicleConfig>()
    var prepOptions by mutableStateOf(PrepOptions())
    var batchFormat by mutableStateOf(ExportFormat.GLB)
    /** Los daños (*_dam) acompañan al vehículo por defecto. */
    var damageMode by mutableStateOf(DamageMode.EMBEDDED)
    var batchDamage by mutableStateOf(DamageMode.EMBEDDED)
    var batchConfirm by mutableStateOf<BatchConfirm?>(null)
    var batchUi by mutableStateOf<BatchUi?>(null); private set
    var pendingBatchSave by mutableStateOf<File?>(null)
    @Volatile private var batchCancel = false

    // ---- modelo cargado
    var model by mutableStateOf<VehicleModel?>(null); private set
    var currentItem by mutableStateOf<CatalogItem?>(null); private set
    val currentEntry: VehicleEntry? get() = currentItem?.entry
    var editState by mutableStateOf(EditState(emptySet(), emptySet(), emptySet())); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var isEdited by mutableStateOf(false); private set
    private var edit: EditSession? = null
    var editMode by mutableStateOf(false)
    var outlinerOpen by mutableStateOf(false)
    var outlinerGrouped by mutableStateOf(true)
    var multiSelect by mutableStateOf(false)
    var pickMode by mutableStateOf(PickMode.PART)
    var editTab by mutableStateOf(EditTab.ACTIONS)
    var paint by mutableStateOf(PaintScheme()); private set
    var normalizeScale by mutableStateOf(false); private set
    var renderState by mutableStateOf(RenderState(null, emptySet(), emptySet(), PaintScheme(), 1f)); private set
    var cameraCommand by mutableStateOf(CameraCommand(0, null, false)); private set
    var addedParts by mutableStateOf<Set<String>>(emptySet()); private set
    val sceneScale: Float get() = if (normalizeScale) (model?.validation?.suggestedScale ?: 1f) else 1f

    /** Contadores permanentes: partes y meshes visibles (se recalculan con cada cambio de visibilidad). */
    val counters: Counters? get() { val m = model ?: return null; return Outliner.counters(m, renderState.visible) }

    // ---- manual
    var manualDff by mutableStateOf<ManualFile?>(null); private set
    var manualTxd by mutableStateOf<ManualFile?>(null); private set

    // ---- exportación individual
    var exportResult by mutableStateOf<ExportResultUi?>(null)
    var exporting by mutableStateOf(false); private set
    var pendingSave by mutableStateOf<ExportResultUi?>(null)

    // ---- log
    private val log = ArrayList<String>()
    private fun log(s: String) {
        synchronized(log) {
            log += "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $s"
            if (log.size > 500) log.removeAt(0)
        }
    }

    fun navigate(s: Screen) { screen = s }

    fun back(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.VIEWER -> { closeVehicle(); true }
        else -> { screen = if (model != null && screen == Screen.DIAGNOSTICS) Screen.VIEWER else Screen.HOME; true }
    }

    // ------------------------------------------------------------------ catálogo

    private fun buildItems(rep: ScanReport?): List<CatalogItem> = rep?.vehicles.orEmpty().map { e ->
        CatalogItem(e.id, e.displayName, e.dff.fileName, e.txd?.fileName, CompatibilityRules.of(e), e, false) { parser.load(e) }
    }

    private fun rebuildItems() { items = buildItems(report) + manualItems }

    fun statusOf(id: String): WorkflowStatus = workflow[id] ?: WorkflowStatus.UNREVIEWED

    val visibleItems: List<CatalogItem>
        get() {
            val q = query.trim().lowercase()
            val qn = Names.normalize(q)
            return items.filter { it ->
                (q.isEmpty() || it.displayName.lowercase().contains(q) || it.dffLabel.lowercase().contains(q) || it.txdLabel?.lowercase()?.contains(q) == true ||
                    (qn.isNotEmpty() && Names.normalize(it.displayName).contains(qn))) &&
                    when (filter) {
                        CatalogFilter.ALL -> true
                        CatalogFilter.COMPATIBLE -> it.compatibility.ok
                        CatalogFilter.WITH_TXD -> it.hasTxd
                        CatalogFilter.NO_TXD -> !it.hasTxd
                        CatalogFilter.ERRORS -> statusOf(it.id) == WorkflowStatus.ERROR || it.entry?.status == VehicleStatus.READ_ERROR
                        CatalogFilter.SELECTED -> it.id in selectedIds
                        CatalogFilter.READY -> statusOf(it.id) == WorkflowStatus.READY
                    }
            }
        }

    fun toggleSelected(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun selectAllItems() { selectedIds = selectedIds + visibleItems.map { it.id } }
    fun deselectAllItems() { selectedIds = selectedIds - visibleItems.map { it.id }.toSet() }
    fun invertItemSelection() {
        val vis = visibleItems.map { it.id }.toSet()
        selectedIds = (selectedIds - vis) + (vis - selectedIds)
    }
    fun selectCompatibleItems() { selectedIds = selectedIds + visibleItems.filter { it.compatibility.ok }.map { it.id } }

    // ------------------------------------------------------------------ importación de ZIP

    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            closeVehicle()
            scanProgress = ScanProgress("Escaneando ZIP...", 0)
            try {
                val entries = withContext(Dispatchers.IO) {
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
                    all
                }
                archiveNames = archives.map { it.name }
                val rep = withContext(Dispatchers.Default) { parser.scan(entries, { p -> scanProgress = p }, { !isActive }) }
                // Nuevo ZIP = nueva sesión de configuración: nada se arrastra de otro ZIP.
                configs.clear(); workflow.clear(); itemErrors.clear(); selectedIds = emptySet(); manualItems = emptyList()
                report = rep
                rebuildItems()
                query = ""; filter = CatalogFilter.ALL
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

    /** Todos los DFF/TXD de los ZIP cargados (para el modo manual: elegir cualquier combinación). */
    fun archiveEntries(ext: String): List<ArchiveEntry> =
        archives.flatMap { it.entries }.filter { it.extension == ext }.sortedBy { it.path.lowercase() }

    val hasArchives: Boolean get() = archives.isNotEmpty()

    // ------------------------------------------------------------------ carga de vehículos (uno solo en memoria)

    fun openItem(item: CatalogItem) {
        viewModelScope.launch { loadModel("Cargando ${item.displayName}...", item) }
    }

    private suspend fun loadModel(text: String, item: CatalogItem) {
        busyText = text
        saveCurrentConfig()
        releaseModel() // libera por completo el modelo anterior antes de cargar otro
        try {
            val m = withContext(Dispatchers.Default) { item.load() }
            model = m; currentItem = item
            edit = VehicleConfig.sessionFor(m, configs[item.id]) // restaura EXACTAMENTE la configuración de este vehículo
            addedParts = emptySet()
            editMode = false; multiSelect = false; pickMode = PickMode.PART; editTab = EditTab.ACTIONS
            outlinerOpen = true; outlinerGrouped = true
            normalizeScale = false
            if (workflow[item.id] == WorkflowStatus.ERROR) workflow.remove(item.id)
            itemErrors.remove(item.id)
            refreshEdit()
            screen = Screen.VIEWER
            requestCamera(CameraPreset.THREE_QUARTER)
            log("Cargado '${m.name}': ${m.parts.size} piezas, ${m.totalVertices} vértices, ${m.totalTriangles} triángulos, avisos=${m.warnings.size}, revisión=${m.validation.needsReview}")
            if (m.validation.needsReview) message = "Modelo requiere revisión"
        } catch (e: VehicleLoadException) {
            fail(item, e.message ?: "error", e.diagnostics)
        } catch (e: OutOfMemoryError) {
            releaseModel(); fail(item, "Memoria insuficiente para cargar este modelo", "OutOfMemoryError")
        } catch (e: Throwable) {
            fail(item, "Error al cargar el modelo: ${e.message}", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            busyText = null
        }
    }

    private fun fail(item: CatalogItem, msg: String, diag: String) {
        workflow[item.id] = WorkflowStatus.ERROR
        itemErrors[item.id] = msg
        lastLoadError = diag
        log("ERROR cargando '${item.displayName}': $msg | $diag")
        message = "No se pudo cargar: $msg"
    }

    var lastLoadError by mutableStateOf<String?>(null); private set

    private fun releaseModel() {
        model = null; edit = null; currentItem = null
        renderState = RenderState(null, emptySet(), emptySet(), paint, 1f) // el renderer libera VBO/IBO/texturas
        editState = EditState(emptySet(), emptySet(), emptySet())
    }

    fun closeVehicle() {
        saveCurrentConfig()
        releaseModel()
        outlinerOpen = false; editMode = false
        screen = Screen.HOME
    }

    // ------------------------------------------------------------------ configuración y estado por vehículo

    private fun saveCurrentConfig() {
        val m = model ?: return; val e = edit ?: return; val it = currentItem ?: return
        configs[it.id] = VehicleConfig.capture(m, e.state)
    }

    /** Tras una edición del usuario: guarda la config propia y ajusta el estado (LISTO se pierde si se vuelve a tocar). */
    private fun onUserEdit() {
        val e = edit ?: return; val it = currentItem ?: return
        saveCurrentConfig()
        val cur = statusOf(it.id)
        workflow[it.id] = when {
            e.isEdited() -> WorkflowStatus.EDITED
            cur == WorkflowStatus.ERROR -> cur
            else -> WorkflowStatus.UNREVIEWED
        }
    }

    fun markReady() {
        val it = currentItem ?: return
        saveCurrentConfig()
        if (statusOf(it.id) == WorkflowStatus.READY) {
            workflow[it.id] = if (edit?.isEdited() == true) WorkflowStatus.EDITED else WorkflowStatus.UNREVIEWED
            message = "${it.displayName}: ya no está marcado como listo"
        } else {
            workflow[it.id] = WorkflowStatus.READY
            message = "${it.displayName}: ✓ LISTO PARA EXPORTAR"
            log("Marcado como listo: ${it.displayName}")
        }
    }

    // ------------------------------------------------------------------ edición (la visibilidad es la única fuente de verdad)

    private fun refreshEdit() {
        val e = edit ?: return
        editState = e.state; canUndo = e.canUndo; canRedo = e.canRedo; isEdited = e.isEdited()
        renderState = RenderState(e.model, e.visibleIds(), e.state.selection, paint, sceneScale)
    }

    private fun withEdit(userEdit: Boolean = true, block: EditSession.() -> Unit) {
        edit?.block(); refreshEdit()
        if (userEdit) onUserEdit()
    }

    fun hide() = withEdit { this.hide() }
    fun delete() = withEdit { this.delete() }
    fun isolate() = withEdit { this.isolate() }
    fun showAll() = withEdit { this.showAll() }
    fun restoreDeleted() = withEdit { this.restoreDeleted() }
    fun undo() = withEdit { this.undo() }
    fun redo() = withEdit { this.redo() }

    /** RESTABLECER VEHÍCULO: vuelve a mostrar todo, deshace reclasificaciones y deja el vehículo SIN REVISAR. */
    fun resetVehicle() {
        val it = currentItem ?: return
        edit?.resetAll(); refreshEdit()
        configs.remove(it.id); workflow[it.id] = WorkflowStatus.UNREVIEWED
        saveCurrentConfig()
        message = "Vehículo restablecido"
    }

    fun clearSelection() = withEdit(false) { this.clearSelection() }
    fun select(ids: Set<Int>, add: Boolean = false) = withEdit(false) { if (add) this.select(this.state.selection + ids) else this.select(ids) }
    fun toggleSelect(ids: Set<Int>) = withEdit(false) { this.toggle(ids) }
    fun selectAllParts() = withEdit(false) { this.selectAll() }

    /** Casilla del Outliner / grupo: marcado = visible = se exporta. */
    fun setVisible(ids: Set<Int>, visible: Boolean) = withEdit { this.setVisible(ids, visible) }
    fun showEverything() = withEdit { this.showEverything() }
    fun hideEverything() = withEdit { this.hideEverything() }
    fun invertVisibility() = withEdit { this.invertVisibility() }
    fun restoreVisibility() = withEdit { this.restoreVisibility() }
    fun soloSelection() = withEdit { this.isolate() }

    fun setCategory(partId: Int, c: PartCategory) = withEdit { this.setCategory(setOf(partId), c) }
    fun categoryOf(p: MeshPart): PartCategory = editState.categories[p.id] ?: p.category

    /** PREPARAR PARA MI JUEGO: identifica ruedas (según nombres/texturas/reclasificación manual) y las desmarca. */
    fun prepareForGame() {
        val e = edit ?: return
        val r = GamePrep.apply(e, prepOptions)
        refreshEdit(); onUserEdit()
        message = if (r.wheels.isEmpty() && prepOptions.removeWheels) r.notes.firstOrNull()
        else "Preparado: ${r.hiddenParts.size} parte(s) desmarcada(s) (${r.hiddenParts.take(4).joinToString { it.name }}${if (r.hiddenParts.size > 4) "…" else ""})"
        log("Preparar para mi juego en '${currentItem?.displayName}': ocultas=${r.hiddenParts.map { it.name }}")
    }

    fun selectSimilar() {
        val m = model ?: return
        val first = editState.selection.firstOrNull() ?: return
        select(SelectionTools.similar(m, first))
    }

    fun selectCategory(c: PartCategory) {
        val m = model ?: return
        select(SelectionTools.byCategory(m, c, ::categoryOf).filter { id -> id !in editState.deleted }.toSet())
    }

    fun selectMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)) } }
    fun hideMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)); hide() } }
    fun deleteMaterial(key: String) { model?.let { select(SelectionTools.byMaterial(it, key)); delete() } }

    /** Toque en el visor: selecciona la pieza y, por tanto, su fila en el Outliner (misma selección). */
    fun pick(rayProvider: () -> Ray) {
        val m = model ?: return
        val e = edit ?: return
        val ray = rayProvider()
        val scale = sceneScale
        viewModelScope.launch {
            val hit = withContext(Dispatchers.Default) { RayPicker.pick(m, ray, { sm -> e.isVisible(sm.id) }, Mat4.scale(scale)) }
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

    fun chooseNormalizeScale(on: Boolean) { normalizeScale = on; refreshEdit(); requestCamera(null) }

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
                    refreshEdit(); onUserEdit()
                    log("Pieza añadida: ${part.dff.path}")
                }
            } catch (ex: Throwable) {
                log("ERROR añadiendo pieza ${part.dff.path}: ${ex.message}")
                message = "No se pudo añadir la pieza: ${ex.message}"
            } finally { busyText = null }
        }
    }

    // ------------------------------------------------------------------ modo manual (mismo flujo que el catálogo)

    fun setManualDffFromEntry(e: ArchiveEntry) { manualDff = ManualFile(e.path + "  [${e.archiveName}]", e.source) }
    fun setManualTxdFromEntry(e: ArchiveEntry) { manualTxd = ManualFile(e.path + "  [${e.archiveName}]", e.source) }
    fun setManualDffFromUri(u: Uri) { val n = Storage.displayName(ctx, u); manualDff = ManualFile(n, UriSource(ctx, u, n)) }
    fun setManualTxdFromUri(u: Uri) { val n = Storage.displayName(ctx, u); manualTxd = ManualFile(n, UriSource(ctx, u, n)) }
    fun clearManualTxd() { manualTxd = null }
    fun clearManual() { manualDff = null; manualTxd = null }

    /** El vehículo manual se registra como un elemento más del catálogo: visor, Outliner, estado y exportación masiva. */
    fun loadManual(withTxd: Boolean) {
        val dff = manualDff ?: return
        val txd = if (withTxd) manualTxd else null
        val name = Names.pretty(dff.source.displayName.substringBeforeLast('.'))
        log("Modo manual: DFF=${dff.label} TXD=${txd?.label ?: "(ninguno)"}")
        val id = "manual:${dff.label}|${txd?.label ?: "-"}"
        val existing = manualItems.firstOrNull { it.id == id }
        val item = existing ?: CatalogItem(
            id, name, dff.source.displayName, txd?.source?.displayName,
            Compatibility(txd != null, if (txd == null) "TXD faltante" else null), null, true,
        ) { parser.loadManual(name, dff.source, txd?.source) }
        if (existing == null) { manualItems = manualItems + item; rebuildItems() }
        openItem(item)
    }

    // ------------------------------------------------------------------ exportación individual

    fun selectionLabel(): String? {
        val m = model ?: return null
        val parts = editState.selection.mapNotNull { m.partOf(it) }.distinctBy { it.id }
        if (parts.isEmpty()) return null
        if (parts.size == 1) return parts[0].name
        val cats = parts.map { categoryOf(it) }.distinct()
        if (cats.size == 1) return when (cats[0]) {
            PartCategory.WHEEL -> "ruedas"; PartCategory.DOOR -> "puertas"; PartCategory.HOOD -> "capo"; PartCategory.TRUNK -> "baul"
            PartCategory.MIRROR -> "espejos"; PartCategory.SPOILER -> "spoiler"; PartCategory.BUMPER -> "paragolpes"
            PartCategory.INTERIOR -> "interior"; PartCategory.ENGINE -> "motor"; PartCategory.EXHAUST -> "escape"
            PartCategory.SUSPENSION -> "suspension"; PartCategory.BRAKES -> "frenos"; PartCategory.LIGHT -> "luces"
            PartCategory.WINDOW -> "cristales"; PartCategory.FENDER -> "guardabarros"; PartCategory.BODY -> "carroceria"
            PartCategory.OTHER -> "piezas"
            else -> cats[0].label.lowercase()
        }
        return "seleccion"
    }

    /** VISIBLE = EXPORTAR: la escena se construye con los sub-meshes visibles del propio EditSession. */
    fun export(format: ExportFormat, scope: ExportScope) {
        val m = model ?: return
        val e = edit ?: return
        if (exporting) return
        val ids: Set<Int> = when (scope) {
            ExportScope.VISIBLE -> e.visibleIds()
            ExportScope.FULL -> m.parts.filter { it.defaultVisible }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
            ExportScope.SELECTION -> e.state.selection - e.state.deleted
        }
        if (ids.isEmpty()) { message = "No hay partes visibles para exportar"; return }
        val label = if (scope == ExportScope.SELECTION) selectionLabel() else null
        val edited = scope == ExportScope.VISIBLE && e.isEdited()
        val base = ExportNaming.baseName(m.name, edited, label)
        val paintNow = paint; val scale = sceneScale; val damageNow = damageMode
        viewModelScope.launch {
            exporting = true
            busyText = "Exportando ${format.label}..."
            try {
                val out = withContext(Dispatchers.Default) {
                    // exportar una selección (p. ej. solo ruedas) nunca arrastra daños; el vehículo sí
                    VehicleExport.export(m, ids, e.state.deleted, paintNow, scale, base, format, if (scope == ExportScope.SELECTION) DamageMode.NONE else damageNow)
                }
                val file = withContext(Dispatchers.IO) { ExportFiles.cacheFile(ctx, out.fileName).also { it.writeBytes(out.bytes) } }
                log("Exportado ${out.fileName} (${out.bytes.size / 1024} KB; ${out.entries.size} archivo(s))${out.limitations?.let { " | $it" } ?: ""}")
                val res = ExportResultUi(file, out, out.fileName)
                exportResult = res
                pendingSave = res
            } catch (ex: OutOfMemoryError) {
                message = "Memoria insuficiente al exportar; oculte partes e inténtelo de nuevo"
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
        val single = pendingSave ?: if (pendingBatchSave == null) exportResult else null
        val batchFile = pendingBatchSave
        val file = single?.file ?: batchFile ?: return
        val name = single?.suggestedName ?: batchUi?.zipName ?: file.name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ExportFiles.copyTo(ctx, file, uri) }
                message = "Guardado: $name"
                log("Guardado $name en $uri")
            } catch (e: Throwable) {
                message = "No se pudo guardar: ${e.message}"
                log("ERROR guardando: ${e.message}")
            } finally { pendingSave = null; pendingBatchSave = null }
        }
    }

    // ------------------------------------------------------------------ preparación masiva (valores iniciales globales)

    /**
     * Aplica la PREPARACIÓN PARA MI JUEGO global solo a los seleccionados SIN configuración propia. La configuración
     * individual tiene prioridad: los vehículos ya editados/listos no se tocan.
     */
    fun prepareSelected() {
        val targets = items.filter { it.id in selectedIds && configs[it.id] == null && statusOf(it.id) != WorkflowStatus.READY }
        val skipped = selectedIds.size - targets.size
        if (targets.isEmpty()) { message = "Nada que preparar: los seleccionados ya tienen configuración propia"; return }
        val opts = prepOptions
        viewModelScope.launch {
            var done = 0; var failed = 0
            for ((i, t) in targets.withIndex()) {
                busyText = "Preparando ${i + 1}/${targets.size}: ${t.displayName}"
                try {
                    withContext(Dispatchers.Default) {
                        val m = t.load()
                        val s = EditSession(m)
                        GamePrep.apply(s, opts)
                        configs[t.id] = VehicleConfig.capture(m, s.state)
                        workflow[t.id] = if (s.isEdited()) WorkflowStatus.EDITED else WorkflowStatus.UNREVIEWED
                    }
                    done++
                } catch (e: Throwable) {
                    failed++; workflow[t.id] = WorkflowStatus.ERROR; itemErrors[t.id] = e.message ?: "error"
                    log("ERROR preparando '${t.displayName}': ${e.message}")
                }
            }
            busyText = null
            message = "Preparados $done vehículo(s)" + (if (failed > 0) ", $failed con error" else "") + (if (skipped > 0) ", $skipped omitidos (ya tenían configuración propia)" else "")
        }
    }

    // ------------------------------------------------------------------ exportación masiva

    fun requestBatchSelected() = requestBatch(items.filter { it.id in selectedIds })

    fun requestBatchAllCompatible() {
        val comp = items.filter { it.compatibility.ok }
        selectedIds = comp.map { it.id }.toSet()
        requestBatch(comp)
    }

    private fun requestBatch(list: List<CatalogItem>) {
        if (list.isEmpty()) { message = "No hay vehículos para exportar"; return }
        val unrev = list.count { configs[it.id] == null && statusOf(it.id) != WorkflowStatus.READY }
        if (unrev > 0) batchConfirm = BatchConfirm(list, unrev) else startBatch(list)
    }

    fun confirmBatch() { val c = batchConfirm ?: return; batchConfirm = null; startBatch(c.items) }

    fun cancelBatch() { batchCancel = true }

    fun dismissBatch() { if (batchUi?.finished == true) { batchUi = null; pendingBatchSave = null } }

    private fun startBatch(list: List<CatalogItem>) {
        saveCurrentConfig()
        val format = batchFormat
        val zipName = "VEHICULOS_${format.name}.zip"
        // Cada trabajo lleva SU PROPIA configuración (snapshot); nunca una global.
        val jobs = list.map { BatchJob(it.id, it.displayName, it.load, configs[it.id]) }
        val paintNow = paint
        val dmgMode = batchDamage
        batchCancel = false
        batchUi = BatchUi(list.size, 0, emptyList(), false, null, null, zipName, format)
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { ExportFiles.cacheFile(ctx, zipName) }
            val lines = ArrayList<BatchLine>()
            val result = withContext(Dispatchers.Default) {
                file.outputStream().buffered().use { out ->
                    BatchExporter.run(jobs, format, out, paintNow, 1f, dmgMode, onProgress = { p ->
                        synchronized(lines) {
                            lines.removeAll { it.name == p.name && it.state == BatchState.PROCESSING }
                            lines += BatchLine(p.name, p.state, p.message)
                            val done = lines.count { it.state != BatchState.PROCESSING }
                            batchUi = BatchUi(p.total, done, lines.toList(), false, null, null, zipName, format)
                        }
                        if (p.state == BatchState.ERROR) { workflow[p.jobId] = WorkflowStatus.ERROR; itemErrors[p.jobId] = p.message ?: "error" }
                    }, isCancelled = { batchCancel })
                }
            }
            val ok = result.okCount > 0
            if (!ok) file.delete()
            log("Exportación masiva: ${result.okCount}/${result.total} correctos, ${result.errors.size} con error${if (result.cancelled) " (cancelada)" else ""}")
            result.errors.forEach { log("  ✕ ${it.first}: ${it.second}") }
            batchUi = BatchUi(result.total, result.okCount + result.errors.size, synchronized(lines) { lines.toList() }, true, result, if (ok) file else null, zipName, format)
            if (ok) pendingBatchSave = file
        }
    }

    // ------------------------------------------------------------------ diagnóstico

    fun diagnosticsText(): String = buildString {
        appendLine("=== GTA Vehicle Viewer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
        appendLine("Módulo: ${parser.module.displayName}")
        appendLine("ZIP cargados: ${archiveNames.joinToString().ifEmpty { "ninguno" }}")
        appendLine("Estados: " + WorkflowStatus.values().joinToString { s -> "${s.label}=${items.count { statusOf(it.id) == s }}" })
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
        if (itemErrors.isNotEmpty()) { appendLine("--- VEHÍCULOS CON ERROR ---"); itemErrors.forEach { (k, v) -> appendLine("${items.firstOrNull { it.id == k }?.displayName ?: k}: $v") } }
        appendLine("--- REGISTRO DE SESIÓN ---")
        synchronized(log) { log.forEach { appendLine(it) } }
    }

    override fun onCleared() {
        archives.forEach { it.close() }; archives.clear()
        Storage.clearImportCache(ctx)
    }
}
