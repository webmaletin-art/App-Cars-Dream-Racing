package com.webmaletin.gtaviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webmaletin.gtaviewer.catalog.FormatRegistry
import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.fit.*
import com.webmaletin.gtaviewer.gl.RenderState
import com.webmaletin.gtaviewer.glb.GlbVerifier
import com.webmaletin.gtaviewer.io.ImportedArchive
import com.webmaletin.gtaviewer.io.Storage
import com.webmaletin.gtaviewer.library.*
import com.webmaletin.gtaviewer.mesh.MeshAnalysis
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.project.*
import com.webmaletin.gtaviewer.selection.Ray
import com.webmaletin.gtaviewer.selection.RayPicker
import com.webmaletin.gtaviewer.texture.BrushMode
import com.webmaletin.gtaviewer.texture.PaintDocument
import com.webmaletin.gtaviewer.txd.TextureImage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Progreso de una importación al catálogo permanente. */
class ImportUi(val text: String, val done: Int, val total: Int, val finished: Boolean, val summary: String?, val errors: List<String> = emptyList())

/** Instantánea inmutable del taller: lo que ve la interfaz. */
class WsSnapshot(
    val state: ProjectState, val model: VehicleModel, val renderVisible: Set<Int>,
    val canUndo: Boolean, val canRedo: Boolean, val revision: Int, val summary: ExportSummary,
)

class Candidate(val meta: PieceMeta, val result: CompatResult)
class TexSlot(val partId: Int, val partName: String, val subIndex: Int, val textureName: String, val width: Int, val height: Int, val edited: Boolean)
class GenCard(val result: GenResult, val preview: ImageBitmap?, val report: List<String>)

enum class TexTool(val label: String) {
    BRUSH("Pincel"), ERASER("Borrador"), RESTORE("Restaurar"), LINE("Línea"), RECT("Rectángulo"), ELLIPSE("Elipse"),
    FILL("Relleno"), STAMP("Sello / logo"), TEXT("Texto"), INPAINT("Quitar logo"), PICK("Cuentagotas"),
}

/**
 * Módulo B (generador / taller de autos). Comparte motor con el visor (core) pero tiene su propio estado: nada de lo que se haga aquí
 * toca el catálogo del visor ni los DFF/TXD/ZIP originales.
 */
class LabViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val store = LibraryStore(File(ctx.filesDir, "library"))
    val projects = ProjectStore(store)
    private val prefs = ctx.getSharedPreferences("lab", 0)

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null); private set
    var libRev by mutableStateOf(0); private set
    var projRev by mutableStateOf(0); private set

    // ============================================================================ catálogo permanente

    var qText by mutableStateOf("")
    var qKind by mutableStateOf<String?>("VEHICLE")
    var qCategory by mutableStateOf<String?>(null)
    var qType by mutableStateOf<String?>(null)
    var qSource by mutableStateOf<String?>(null)
    var qFav by mutableStateOf(false)
    var qSort by mutableStateOf(SortKey.NAME)
    var qDesc by mutableStateOf(false)
    var libSel by mutableStateOf<Set<String>>(emptySet())

    fun items(): List<PieceMeta> {
        libRev
        return LibraryQuery(qText, qKind, qCategory, qType, qSource, qFav, qSort, qDesc).apply(store.all())
    }
    fun counts(): Pair<Int, Int> { libRev; return store.vehicles().size to store.components().size }
    fun sources(): List<String> { libRev; return store.all().map { it.source }.distinct().sorted() }
    fun categories(): List<String> { libRev; return (PartCategory.values().map { it.name } + store.customCategories()).distinct() }
    fun categoryLabel(c: String) = PartCategory.fromName(c)?.label ?: c
    fun bump() { libRev++ }

    private val thumbs = LruCache<String, ImageBitmap>(320)
    private val thumbGate = Semaphore(2)
    suspend fun thumb(id: String): ImageBitmap? {
        thumbs.get(id)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            thumbGate.withPermit {
                val png = try { store.thumbnailPng(id) } catch (e: Throwable) { null } ?: return@withPermit null
                BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
        }
        if (bmp != null) thumbs.put(id, bmp)
        return bmp
    }

    fun rename(id: String, name: String) { store.rename(id, name); bump() }
    fun favorite(id: String, v: Boolean) { store.setFavorite(id, v); bump() }
    fun setCategory(id: String, c: String) { store.setCategory(id, c); bump() }
    fun notes(id: String, n: String) { store.setNotes(id, n); bump() }
    fun addCategory(name: String) { store.addCustomCategory(name); bump() }
    fun removeCategory(name: String) { store.removeCustomCategory(name); bump() }
    fun correctTypes(id: String, labels: List<String>) { store.correctVehicleTypes(id, labels); bump(); message = "Corrección guardada: el clasificador la aprende para vehículos similares" }
    fun delete(ids: Set<String>, cascade: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = store.delete(ids, cascade)
            withContext(Dispatchers.Main) { libSel = libSel - ids; bump(); message = "$n elementos eliminados del catálogo (los archivos originales no se tocan)" }
        }
    }

    // ---- importación al catálogo

    var importUi by mutableStateOf<ImportUi?>(null); private set
    private var importJob: Job? = null

    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch(Dispatchers.IO) {
            val archives = ArrayList<ImportedArchive>()
            try {
                importUi = ImportUi("Abriendo ZIP…", 0, 0, false, null)
                val entries = ArrayList<com.webmaletin.gtaviewer.zip.ArchiveEntry>()
                uris.forEachIndexed { i, u ->
                    importUi = ImportUi("Abriendo ${Storage.displayName(ctx, u)}…", i, uris.size, false, null)
                    val a = Storage.openZip(ctx, u, 50_000 + i); archives += a; entries += a.entries
                }
                val rep = FormatRegistry.default.scan(entries, { p -> importUi = ImportUi("Escaneando: ${p.stage} ${p.percent}%", 0, 0, false, null) }, { !isActive })
                val items = rep.vehicles.map(LibraryImporter::itemOf)
                runImport(items)
            } catch (e: CancellationException) {
                importUi = ImportUi("Importación cancelada", 0, 0, true, "Cancelada. Lo ya importado queda guardado.")
            } catch (e: Throwable) {
                importUi = ImportUi("Error", 0, 0, true, "No se pudo importar: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                archives.forEach { it.close() }
                withContext(NonCancellable + Dispatchers.Main) { bump() }
            }
        }
    }

    /** Añade vehículos ya detectados en el visor (cualquier origen, incluido el modo manual). */
    fun importItems(items: List<LibraryImporter.Item>) {
        if (items.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try { runImport(items) } catch (e: CancellationException) {
                importUi = ImportUi("Importación cancelada", 0, 0, true, "Cancelada. Lo ya importado queda guardado.")
            } catch (e: Throwable) {
                importUi = ImportUi("Error", 0, 0, true, "No se pudo importar: ${e.message ?: e.javaClass.simpleName}")
            } finally { withContext(NonCancellable + Dispatchers.Main) { bump() } }
        }
    }

    private fun runImport(items: List<LibraryImporter.Item>) {
        val job = importJob
        val sum = LibraryImporter(store).importAll(items, { p ->
            importUi = ImportUi("${p.index}/${p.total} · ${p.name}", p.index, p.total, false, null)
        }, { job?.isActive != true })
        val text = "Vehículos nuevos: ${sum.added} · ya existentes (sin duplicar): ${sum.duplicates} · piezas nuevas: ${sum.components} · " +
            "piezas ya conocidas fusionadas: ${sum.mergedComponents}" + if (sum.errors.isNotEmpty()) " · con error: ${sum.errors.size}" else "" + if (sum.cancelled) " · CANCELADO" else ""
        importUi = ImportUi(if (sum.cancelled) "Cancelado" else "Importación terminada", items.size, items.size, true, text, sum.errors.map { "${it.first}: ${it.second}" })
    }

    fun cancelImport() { importJob?.cancel() }
    fun dismissImport() { if (importUi?.finished == true) importUi = null }

    // ---- copia de seguridad

    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = "Exportando copia de seguridad…"
            try {
                ctx.contentResolver.openOutputStream(uri, "w")?.use { store.exportBackup(it) } ?: error("No se pudo escribir")
                message = "Copia de seguridad guardada (${store.all().size} elementos)"
            } catch (e: Throwable) { message = "Error en la copia: ${e.message}" } finally { busy = null }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy = "Restaurando copia…"
            try {
                val r = ctx.contentResolver.openInputStream(uri)?.use { store.importBackup(it) } ?: error("No se pudo leer")
                message = "Copia restaurada: ${r.addedPieces} elementos nuevos, ${r.skippedPieces} ya existían (sin duplicar)"
            } catch (e: Throwable) { message = "Error restaurando: ${e.message}" } finally { busy = null; withContext(Dispatchers.Main) { bump() } }
        }
    }

    fun gc() {
        viewModelScope.launch(Dispatchers.IO) {
            busy = "Limpiando datos huérfanos…"
            try { val n = store.gc(); message = "Se liberaron $n archivos que ningún elemento usaba" } finally { busy = null; withContext(Dispatchers.Main) { bump() } }
        }
    }

    // ============================================================================ proyectos

    fun projectList(): List<ProjectInfo> { libRev; projRev; return projects.list().sortedByDescending { it.modified } }

    fun newProject(name: String, baseVehicleId: String?, onOpen: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            busy = "Creando proyecto…"
            try {
                val p = projects.create(name.ifBlank { "Proyecto" })
                if (baseVehicleId != null) p.setBaseVehicle(baseVehicleId)
                projects.save(p); projRev++
                attach(p, emptyList())
                withContext(Dispatchers.Main) { onOpen() }
            } catch (e: Throwable) { message = "No se pudo crear el proyecto: ${e.message}" } finally { busy = null }
        }
    }

    fun openProject(id: String, onOpen: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            busy = "Abriendo proyecto…"
            try {
                val (p, missing) = projects.open(id)
                attach(p, missing)
                withContext(Dispatchers.Main) { onOpen() }
            } catch (e: Throwable) { message = "No se pudo abrir el proyecto: ${e.message}" } finally { busy = null }
        }
    }

    fun duplicateProject(id: String, name: String) { viewModelScope.launch(Dispatchers.IO) { projects.duplicate(id, name); withContext(Dispatchers.Main) { projRev++ } } }
    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            projects.delete(id)
            withContext(Dispatchers.Main) { if (project?.id == id) closeProject(); projRev++ }
        }
    }

    // ============================================================================ taller

    private var project: CarProject? = null
    val hasProject get() = project != null
    var ws by mutableStateOf<WsSnapshot?>(null); private set
    var missingResources by mutableStateOf<List<String>>(emptyList()); private set
    var selection by mutableStateOf<Set<Int>>(emptySet()); private set
    var renderState by mutableStateOf(RenderState(null, emptySet(), emptySet(), PaintScheme(), 1f)); private set
    var dragMove by mutableStateOf(false)
    var cameraTick by mutableStateOf(0); private set
    var verifyText by mutableStateOf<String?>(null)
    var report by mutableStateOf<String?>(null)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var commitQueued = false

    private fun attach(p: CarProject, missing: List<String>) {
        project = p; missingResources = missing; selection = emptySet(); faceSel = emptySet(); facePart = null
        candCache = null; baseInfo = null; texDoc = null
        commit(p, save = false)
        cameraTick++
    }

    fun dismissMissing() { missingResources = emptyList() }
    fun renameProject(n: String) = op { setName(n) }

    /** Caja (espacio de visor) de las piezas seleccionadas, para centrar la cámara en ellas. */
    fun selectionBounds(): Bounds? {
        val p = project ?: return null
        val bs = selection.mapNotNull { id -> p.part(id)?.let { p.partBoundsView(it) } }
        if (bs.isEmpty()) return null
        return Bounds(FloatArray(3) { i -> bs.minOf { it[i] } }, FloatArray(3) { i -> bs.maxOf { it[i + 3] } })
    }

    fun closeProject() {
        project?.let { runCatching { projects.save(it) } }
        projRev++
        project = null; ws = null; selection = emptySet(); texDoc = null; texBitmap = null
        renderState = RenderState(null, emptySet(), emptySet(), PaintScheme(), 1f)
    }

    /** Reconstruye modelo + estado de render y guarda el proyecto (guardado automático: reabre tras reiniciar). */
    private fun commit(p: CarProject, save: Boolean = true) {
        val model = p.materialize()
        val vis = p.renderVisible(model)
        val snap = WsSnapshot(p.state, model, vis, p.canUndo, p.canRedo, p.revision, p.exportSummary())
        if (save) runCatching { projects.save(p) }
        ws = snap
        selection = selection.filter { id -> p.part(id)?.deleted == false }.toSet()
        pushRender()
    }

    private fun pushRender() {
        val s = ws ?: return
        val selSubs = s.model.parts.filter { it.id in selection }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()
        val ov = facePart?.let { pid -> if (faceSel.isEmpty()) null else project?.faceOverlay(pid, faceSel) }
        renderState = RenderState(s.model, s.renderVisible, selSubs, PaintScheme(s.state.paintPrimary, s.state.paintSecondary), 1f, project, true, ov)
    }

    /** Ejecuta una operación del proyecto de forma serializada (nunca dos a la vez), con deshacer y guardado automático. */
    private fun op(label: String? = null, block: CarProject.() -> Unit) {
        val p = project ?: return
        viewModelScope.launch(serial) {
            try { block(p) } catch (e: OutOfMemoryError) { message = "Memoria insuficiente en esta operación" }
            catch (e: Throwable) { message = "No se pudo completar${label?.let { " «$it»" } ?: ""}: ${e.message ?: e.javaClass.simpleName}" }
            if (project === p) commit(p)
        }
    }

    private fun opMsg(block: CarProject.() -> String?) {
        val p = project ?: return
        viewModelScope.launch(serial) {
            try { block(p)?.let { message = it } } catch (e: Throwable) { message = "No se pudo completar: ${e.message ?: e.javaClass.simpleName}" }
            if (project === p) commit(p)
        }
    }

    fun undo() = op { undo() }
    fun redo() = op { redo() }

    fun partOf(id: Int): PPart? = ws?.state?.parts?.firstOrNull { it.id == id }

    // ---- selección

    fun select(ids: Set<Int>) { selection = ids; pushRender() }
    fun toggleSelect(id: Int) { select(if (id in selection) selection - id else selection + id) }
    fun clearSelection() { select(emptySet()); faceSel = emptySet(); facePart = null }

    /** Toque en el visor: selecciona la pieza (o, en modo caras, una región de caras). */
    fun pickAt(ray: Ray) {
        val s = ws ?: return; val p = project ?: return
        val faces = pickFaces
        val multi = multiSelect
        viewModelScope.launch(serial) {
            val vis = s.renderVisible
            val hit = RayPicker.pick(s.model, ray, { sm -> sm.id in vis }, Mat4.identity())
            if (hit == null) { if (!faces && !multi) { selection = emptySet(); pushRender() }; return@launch }
            val part = s.model.partOf(hit.subMeshId) ?: return@launch
            if (faces) {
                if (hit.triangle < 0) return@launch
                val subIndex = hit.subMeshId % 1000
                val flat = p.flatTriangle(part.id, subIndex, hit.triangle)
                val region = when (faceMode) {
                    FaceMode.ONE -> setOf(flat)
                    FaceMode.SMOOTH -> p.growFaceRegion(part.id, flat, 35f, true)
                    FaceMode.ANGLE -> p.growFaceRegion(part.id, flat, 80f, false)
                }
                if (facePart != part.id) faceSel = emptySet()
                facePart = part.id
                faceSel = if (flat in faceSel) faceSel - region else faceSel + region
                selection = setOf(part.id)
                pushRender()
            } else {
                selection = if (multi) (if (part.id in selection) selection - part.id else selection + part.id) else if (selection == setOf(part.id)) emptySet() else setOf(part.id)
                pushRender()
            }
        }
    }

    var multiSelect by mutableStateOf(false)
    var pickFaces by mutableStateOf(false)
    var faceMode by mutableStateOf(FaceMode.SMOOTH)
    var faceSel by mutableStateOf<Set<Int>>(emptySet()); private set
    var facePart by mutableStateOf<Int?>(null); private set
    enum class FaceMode(val label: String) { ONE("1 cara"), SMOOTH("Región suave"), ANGLE("Por ángulo") }

    fun clearFaces() { faceSel = emptySet(); facePart = null; pushRender() }

    // ---- estados de pieza

    fun setVisible(ids: Collection<Int>, v: Boolean) = op { setVisible(ids, v) }
    fun setExport(ids: Collection<Int>, v: Boolean) = op { setExport(ids, v) }
    fun setLocked(ids: Collection<Int>, v: Boolean) = op { setLocked(ids, v) }
    fun renamePart(id: Int, n: String) = op { rename(id, n) }
    fun setPartCategory(ids: Collection<Int>, c: PartCategory) = op { setCategory(ids, c) }
    fun deleteParts(ids: Collection<Int>) = opMsg { val locked = ids.filter { part(it)?.locked == true }; if (locked.isNotEmpty()) "Hay piezas bloqueadas: desbloquéelas para eliminarlas" else { delete(ids); null } }
    fun restoreParts(ids: Collection<Int>) = op { restore(ids) }
    fun duplicatePart(id: Int) = op { duplicate(id)?.let { n -> selection = setOf(n) } }
    fun mirrorPart(id: Int) = op { mirror(id)?.let { n -> selection = setOf(n) } }

    // ---- transformaciones

    fun setTrs(id: Int, t: Trs) = opMsg { setTrs(id, t) }
    fun nudge(ids: Collection<Int>, dPos: FloatArray = floatArrayOf(0f, 0f, 0f), dRot: FloatArray = floatArrayOf(0f, 0f, 0f), scale: Float = 1f) = opMsg { nudge(ids, dPos, dRot, scale) }
    fun resetTrs(ids: Collection<Int>) = opMsg { resetTrs(ids) }

    private var gestureActive = false
    private var dragRemainder = floatArrayOf(0f, 0f, 0f)

    /** Arrastre con el dedo: mueve las piezas elegidas en el plano de la pantalla (un único paso de deshacer). */
    fun dragBy(ray0: Ray, ray1: Ray, forward: Ray) {
        val p = project ?: return; val s = ws ?: return
        val ids = selection.filter { id -> p.part(id)?.locked == false }
        if (ids.isEmpty()) { if (selection.isNotEmpty()) message = "La pieza está bloqueada: desbloquéela para moverla"; return }
        // plano que pasa por el centro de la selección, perpendicular a la dirección de vista
        val b = ids.mapNotNull { id -> p.part(id)?.let { p.partBoundsView(it) } }
        if (b.isEmpty()) return
        val c = FloatArray(3) { i -> b.map { (it[i] + it[i + 3]) / 2 }.average().toFloat() }
        val n = forward.dir
        fun hit(r: Ray): FloatArray? {
            val denom = n[0] * r.dir[0] + n[1] * r.dir[1] + n[2] * r.dir[2]
            if (kotlin.math.abs(denom) < 1e-4f) return null
            val t = ((c[0] - r.origin[0]) * n[0] + (c[1] - r.origin[1]) * n[1] + (c[2] - r.origin[2]) * n[2]) / denom
            return floatArrayOf(r.origin[0] + r.dir[0] * t, r.origin[1] + r.dir[1] * t, r.origin[2] + r.dir[2] * t)
        }
        val a = hit(ray0) ?: return; val d = hit(ray1) ?: return
        val dv = floatArrayOf(d[0] - a[0], d[1] - a[1], d[2] - a[2])
        val dGta = floatArrayOf(-dv[0], dv[2], dv[1]) // visor → GTA (x invertida, y/z cambiadas)
        val first = !gestureActive
        gestureActive = true
        viewModelScope.launch(serial) {
            if (first) p.beginGesture("mover con el dedo")
            p.liveNudge(ids, dGta)
            if (!commitQueued) { commitQueued = true; try { commit(p, save = false) } finally { commitQueued = false } }
        }
    }

    fun dragEnd() {
        val p = project ?: return
        if (!gestureActive) return
        gestureActive = false
        viewModelScope.launch(serial) { p.endGesture(); commit(p) }
    }

    // ---- compatibilidad / añadir piezas

    private var baseInfo: BaseInfo? = null
    private var candCache: Triple<String?, String, List<Candidate>>? = null
    var candidates by mutableStateOf<List<Candidate>>(emptyList()); private set
    var candBusy by mutableStateOf(false); private set
    var candCategory by mutableStateOf<String?>(null)
    var candText by mutableStateOf("")
    var candOnlyFits by mutableStateOf(false)

    fun refreshCandidates() {
        val s = ws ?: return
        val baseId = s.state.baseVehicleId
        val cat = candCategory; val text = candText; val only = candOnlyFits
        viewModelScope.launch(Dispatchers.Default) {
            candBusy = true
            try {
                val info = baseInfo?.takeIf { it.id == baseId } ?: baseId?.let { FitPlanner.baseInfo(store, it) }?.also { baseInfo = it }
                val metas = LibraryQuery(text = text, kind = "COMPONENT", category = cat, sort = SortKey.NAME).apply(store.components()).take(200)
                val learned = store.learnedFits()
                val list = if (info == null) metas.map { Candidate(it, CompatResult(CompatLevel.DOUBTFUL, 0f, listOf("No hay carrocería base: elija una para evaluar la compatibilidad"), 1f, floatArrayOf(0f, 0f, 0f), null)) }
                else metas.map { m -> Candidate(m, FitPlanner.evaluate(info, m, m.parentId?.let { store.get(it) }, learned)) }
                    .sortedByDescending { it.result.level.rank * 10 + it.result.score }
                candidates = if (only) list.filter { it.result.level.rank >= 2 } else list
            } finally { candBusy = false }
        }
    }

    fun addPiece(c: Candidate, withFit: Boolean) = opMsg {
        if (c.meta.category == PartCategory.WHEEL.name) {
            val ids = addWheelSet(c.meta.id)
            if (ids.isEmpty()) "La base no tiene ruedas para colocar" else { selection = ids.toSet(); "Juego de ${ids.size} ruedas colocado (orientación deducida de la geometría)" }
        } else {
            val id = addPiece(c.meta.id, if (withFit) c.result else null); selection = setOf(id)
            if (c.result.level == CompatLevel.INCOMPATIBLE) "Añadida a prueba: ${c.result.level.label}. ${c.result.reasons.firstOrNull() ?: ""}" else null
        }
    }

    fun replaceSelected(c: Candidate, withFit: Boolean) = opMsg {
        val old = selection.firstOrNull() ?: return@opMsg "Seleccione la pieza que quiere sustituir"
        val id = replace(old, c.meta.id, if (withFit) c.result else null)
        if (id == null) "No se pudo sustituir (¿pieza bloqueada?)" else { selection = setOf(id); null }
    }

    fun setBase(vehicleId: String) = op { setBaseVehicle(vehicleId) }

    /** Añade al proyecto abierto una pieza del catálogo (con el ajuste sugerido si hay base). */
    fun addToCurrentProject(pieceId: String) {
        val meta = store.get(pieceId) ?: return
        val baseId = ws?.state?.baseVehicleId
        viewModelScope.launch(Dispatchers.Default) {
            val info = baseId?.let { FitPlanner.baseInfo(store, it) }
            val res = info?.let { FitPlanner.evaluate(it, meta, meta.parentId?.let { pid -> store.get(pid) }, store.learnedFits()) }
                ?: CompatResult(CompatLevel.DOUBTFUL, 0f, listOf("El proyecto no tiene carrocería base: no se puede evaluar"), 1f, floatArrayOf(0f, 0f, 0f), null)
            addPiece(Candidate(meta, res), withFit = info != null)
        }
    }

    /** «AJUSTAR»: aplica la escala/posición sugeridas por el motor de compatibilidad a la pieza elegida. */
    fun fitSelected() {
        val s = ws ?: return; val id = selection.singleOrNull() ?: run { message = "Seleccione UNA pieza"; return }
        val part = s.state.parts.firstOrNull { it.id == id } ?: return
        val lib = part.libraryId?.let { store.get(it) } ?: run { message = "Esta pieza no viene del catálogo; ajústela con los controles manuales"; return }
        viewModelScope.launch(serial) {
            val info = baseInfo?.takeIf { it.id == s.state.baseVehicleId } ?: s.state.baseVehicleId?.let { FitPlanner.baseInfo(store, it) }?.also { baseInfo = it }
            if (info == null) { message = "Falta la carrocería base"; return@launch }
            val r = FitPlanner.evaluate(info, lib, lib.parentId?.let { store.get(it) }, store.learnedFits())
            val p = project ?: return@launch
            val err = p.applyFit(id, r)
            message = err ?: "Ajuste aplicado: ${r.level.label}. ${r.reasons.take(2).joinToString(" ")}"
            commit(p)
        }
    }

    fun confirmFit() = op { selection.forEach { confirmFit(it) } }.also { message = "Ajuste guardado: se sugerirá en futuros proyectos con esta base" }

    // ---- fusión / reparación

    var analysisText by mutableStateOf<String?>(null)

    fun analyzeSelected() {
        val p = project ?: return; val id = selection.singleOrNull() ?: run { message = "Seleccione UNA pieza"; return }
        viewModelScope.launch(serial) { analysisText = try { p.analyze(id).summary() } catch (e: Throwable) { "Error: ${e.message}" } }
    }

    fun repairSelected() {
        val p = project ?: return; val id = selection.singleOrNull() ?: run { message = "Seleccione UNA pieza"; return }
        viewModelScope.launch(serial) {
            val r = try { p.repair(id, apply = true) } catch (e: Throwable) { message = "No se pudo reparar: ${e.message}"; null }
            analysisText = r?.let { if (it.validated) "REPARADA y validada.\nAntes: ${it.before.summary()}\nDespués: ${it.after.summary()}\nAcciones: ${it.actions.joinToString("; ")}" else "Sin cambios: la malla no necesitaba reparación o la reparación no mejoró las métricas.\n${it.after.summary()}" }
            commit(p)
        }
    }

    fun combineSelected() = opMsg { if (selection.size < 2) "Seleccione al menos 2 piezas" else { combine(selection.toList())?.let { selection = setOf(it) }; null } }
    fun fuseSelected() = opMsg {
        if (selection.size < 2) "Seleccione al menos 2 piezas" else fuse(selection.toList())?.let { (nid, r) -> selection = setOf(nid); analysisText = r.note; null }
    }
    fun stitchSelected() = opMsg {
        val ids = selection.toList()
        if (ids.size != 2) "Seleccione 2 piezas: la primera es la referencia (base) y la segunda se cose a ella" else {
            val moved = stitch(ids[0], ids[1]); if (moved == 0) "No había bordes a menos de ${state.fusion.stitchTolerance} m para coser" else { "$moved vértices de borde cosidos"}
        }
    }
    fun splitSelected() = opMsg { val id = selection.singleOrNull() ?: return@opMsg "Seleccione UNA pieza"; val r = splitComponents(id); if (r.isEmpty()) "La pieza es una sola superficie conexa" else { selection = r.toSet(); "Separada en ${r.size} componentes (la original queda oculta y excluida, no se destruye)" } }
    fun restoreGeometrySelected() = opMsg { val id = selection.singleOrNull() ?: return@opMsg "Seleccione UNA pieza"; if (!restoreGeometry(id)) "Esta pieza no tiene geometría editada" else null }
    fun setFusionTolerance(weld: Float, stitch: Float) = op { setFusion(state.fusion.copy(weldTolerance = weld, stitchTolerance = stitch)) }

    // ---- caras (logos / emblemas)

    fun deleteFaces() = opMsg {
        val pid = facePart ?: return@opMsg "Elija caras tocando la pieza en modo CARAS"
        val err = deleteFaces(pid, faceSel); if (err == null) { faceSel = emptySet(); facePart = null }; err
    }
    fun separateFaces() = opMsg {
        val pid = facePart ?: return@opMsg "Elija caras tocando la pieza en modo CARAS"
        val n = separateFaces(pid, faceSel); if (n != null) { faceSel = emptySet(); facePart = null; selection = setOf(n) }; if (n == null) "No se pudo separar (¿pieza bloqueada?)" else null
    }

    /** Busca por palabras clave (logo, emblem, badge…) en nombres de piezas y materiales: ayuda a localizar, no promete detectar. */
    fun findEmblems(): List<String> {
        val s = ws ?: return emptyList()
        val kw = listOf("logo", "badge", "emblem", "brand", "crest", "insignia", "emblema", "marca", "logotype", "icon")
        val out = ArrayList<String>()
        for (p in s.model.parts) {
            if (kw.any { p.name.lowercase().contains(it) }) out += "Pieza «${p.name}» (nombre)"
            for (sm in p.subMeshes) { val n = (sm.material.textureName ?: sm.material.name).lowercase(); if (kw.any { n.contains(it) }) out += "Material «${sm.material.textureName ?: sm.material.name}» en «${p.name}»" }
        }
        return out.distinct()
    }

    // ---- materiales

    fun materialDef(partId: Int, sub: Int): MatDef? = try { project?.materialDef(partId, sub) } catch (e: Throwable) { null }
    fun editMaterial(partId: Int, sub: Int, f: (MatDef) -> MatDef) = op { editMaterial(partId, sub, f) }
    fun restoreMaterial(partId: Int, sub: Int) = op { restoreMaterial(partId, sub) }
    fun assignMaterial(partId: Int, sub: Int, matId: String?) = op { assignMaterial(partId, sub, matId) }
    fun saveAsMaterial(partId: Int, sub: Int, name: String) = op { newMaterialFor(partId, sub, name) }
    fun setPaint(c: Int) = op { setPaint(primary = c) }
    fun setPaint2(c: Int) = op { setPaint(secondary = c) }
    fun saveVariant(name: String) = op { saveVariant(name) }
    fun applyVariant(name: String) = op { applyVariant(name) }

    // ---- perfil de exportación y GLB

    var exportOptions by mutableStateOf(ExportOptions())
    class ExportOptions(
        val scope: String = "FULL", // FULL, BODY, NO_WHEELS, NO_INTERIOR, SELECTION, SEPARATE
        val profile: ExportProfile = ExportProfile.GODOT,
    )

    fun savedProfiles(): List<ExportProfile> {
        val raw = prefs.getString("profiles", "") ?: ""
        val custom = raw.split("\u0001").filter { it.isNotBlank() }.mapNotNull { runCatching { kotlinx.serialization.json.Json.decodeFromString<ExportProfile>(it) }.getOrNull() }
        return ExportProfile.BUILTIN + custom
    }

    fun saveProfile(p: ExportProfile) {
        val custom = savedProfiles().drop(ExportProfile.BUILTIN.size).filter { it.name != p.name } + p
        prefs.edit().putString("profiles", custom.joinToString("\u0001") { kotlinx.serialization.json.Json.encodeToString(ExportProfile.serializer(), it) }).apply()
        libRev++
    }

    fun setProfile(p: ExportProfile) = op { setExportProfile(p) }

    var exported by mutableStateOf<ExportedFile?>(null)
    class ExportedFile(val file: File, val name: String, val mime: String, val verification: String?, val limitations: String?, val warnings: List<String>)

    fun exportProject(format: ExportFormat, opts: ExportOptions, verify: Boolean) {
        val p = project ?: return; val s = ws ?: return
        val sel = selection
        viewModelScope.launch(serial) {
            busy = "Exportando ${format.label}…"
            try {
                val m = p.materialize()
                fun isWheel(c: PartCategory) = c == PartCategory.WHEEL || c == PartCategory.BRAKES
                fun isInterior(c: PartCategory) = c == PartCategory.INTERIOR || c == PartCategory.SEAT || c == PartCategory.STEERING
                val base = p.exportIds(m)
                fun partsOf(pred: (MeshPart) -> Boolean): Set<Int> = m.parts.filter(pred).flatMap { it.subMeshes.map { s2 -> s2.id } }.toSet()
                val ids = when (opts.scope) {
                    "BODY" -> base intersect partsOf { it.category !in listOf(PartCategory.WHEEL, PartCategory.BRAKES, PartCategory.INTERIOR, PartCategory.SEAT, PartCategory.STEERING) }
                    "NO_WHEELS" -> base - partsOf { isWheel(it.category) }
                    "NO_INTERIOR" -> base - partsOf { isInterior(it.category) }
                    "SELECTION" -> base intersect partsOf { it.id in sel }
                    else -> base
                }
                if (ids.isEmpty()) { message = "No hay nada que exportar con esas opciones (¿todo excluido?)"; return@launch }
                val profile = if (opts.scope == "SEPARATE") opts.profile.copy(combineMeshes = false) else opts.profile
                val scene = p.exportScene(profile, ids)
                val out = Exporters.forFormat(format).export(scene, safeName(s.state.name))
                val f = File(ctx.cacheDir, "exports").apply { mkdirs() }.let { File(it, out.fileName) }
                f.writeBytes(out.bytes)
                val ver = if (verify && format == ExportFormat.GLB) GlbVerifier.verify(scene, out.bytes).text() else null
                exported = ExportedFile(f, out.fileName, out.mime, ver, out.limitations, out.warnings)
            } catch (e: OutOfMemoryError) { message = "Memoria insuficiente al exportar" } catch (e: Throwable) { message = "Error exportando: ${e.message}" } finally { busy = null }
        }
    }

    fun saveExportTo(uri: Uri) {
        val e = exported ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { com.webmaletin.gtaviewer.io.ExportFiles.copyTo(ctx, e.file, uri); message = "Guardado: ${e.name}" } catch (x: Throwable) { message = "No se pudo guardar: ${x.message}" }
        }
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9_\\-]+"), "_").trim('_').ifEmpty { "auto" }

    // ============================================================================ generador

    var genType by mutableStateOf<String?>(null)
    var genStyle by mutableStateOf<String?>(null)
    var genMod by mutableStateOf(2)
    var genOriginal by mutableStateOf(true)
    var genCustom by mutableStateOf(true)
    var genPaint by mutableStateOf<Int?>(null)
    var genCats by mutableStateOf(GenRequest().allowedCategories)
    var genWheels by mutableStateOf(true)
    var genInterior by mutableStateOf(true)
    var genEmblems by mutableStateOf(true)
    var genBase by mutableStateOf<String?>(null)
    var genSeed by mutableStateOf(1L)
    var genLocked by mutableStateOf<Map<String, String>>(emptyMap())
    var genCards by mutableStateOf<List<GenCard>>(emptyList()); private set
    var genBusy by mutableStateOf(false); private set

    fun request(seed: Long = genSeed) = GenRequest(genType, genStyle, genMod, genOriginal, genCustom, genPaint, genCats, genWheels, genInterior, genEmblems, genLocked, genBase, seed)

    private fun previewOf(r: GenResult): Pair<ImageBitmap?, List<String>> {
        // proyecto temporal en memoria (no se guarda): se monta de verdad para medir solapes/salientes y renderizar la vista previa
        val tmp = CarProject(store, "preview", ProjectState("Vista previa"))
        val report = ArrayList<String>()
        return try {
            val added = CarGenerator.apply(tmp, store, r)
            val m = CarGenerator.measure(tmp, added)
            m.forEach { report += "${it.partName}: ${it.verdict}" }
            r.meshChecked = true
            val model = tmp.materialize()
            val rgba = com.webmaletin.gtaviewer.library.ThumbRenderer.render(model, tmp.renderVisible(model), 280, 35f, 16f, PaintScheme(tmp.state.paintPrimary, tmp.state.paintSecondary))
            val png = PngEncoder.encode(280, 280, rgba)
            BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() to report
        } catch (e: Throwable) { null to (report + "No se pudo montar la vista previa: ${e.message}") }
    }

    fun generate(count: Int = 4) {
        if (genBusy) return
        viewModelScope.launch(Dispatchers.Default) {
            genBusy = true
            try {
                val rs = CarGenerator.alternatives(store, request(), count)
                genCards = rs.map { r -> val (bmp, rep) = previewOf(r); GenCard(r, bmp, rep) }
                if (rs.isEmpty()) message = "El catálogo no tiene lo necesario (¿hay vehículos del tipo pedido?). Importe más vehículos."
                genSeed += count
            } catch (e: Throwable) { message = "Error generando: ${e.message}" } finally { genBusy = false }
        }
    }

    fun lockSlot(slot: String, pieceId: String?) { genLocked = if (pieceId == null) genLocked - slot else genLocked + (slot to pieceId) }

    fun openGenerated(card: GenCard, favorite: Boolean, onOpen: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            busy = "Montando proyecto…"
            try {
                val r = card.result
                val p = projects.create((if (favorite) "★ " else "") + "Generado ${r.baseName}")
                val added = CarGenerator.apply(p, store, r)
                projects.save(p); projRev++
                attach(p, emptyList())
                val meas = CarGenerator.measure(p, added)
                report = buildString {
                    appendLine("Generado sobre ${r.baseName}.")
                    appendLine("Comprobación sobre mallas reales (solape / salientes):")
                    meas.forEach { appendLine("• ${it.partName}: ${it.verdict}") }
                    if (r.missing.isNotEmpty()) { appendLine("Faltan en el catálogo:"); r.missing.forEach { appendLine("• $it") } }
                    r.notes.forEach { appendLine("Nota: $it") }
                    append("Esto es una revisión geométrica automática, no sustituye mirar el resultado en el taller.")
                }
                withContext(Dispatchers.Main) { onOpen() }
            } catch (e: Throwable) { message = "No se pudo montar el resultado: ${e.message}" } finally { busy = null }
        }
    }

    // ============================================================================ editor de texturas

    var texDoc by mutableStateOf<PaintDocument?>(null); private set
    var texId by mutableStateOf<String?>(null); private set
    var texTarget by mutableStateOf<Pair<Int, Int>?>(null); private set
    var texBitmap by mutableStateOf<Bitmap?>(null); private set
    var texRev by mutableStateOf(0); private set
    var texDirty by mutableStateOf(false); private set
    var texTool by mutableStateOf(TexTool.BRUSH)
    var texColor by mutableStateOf(0xE53935)
    var texSize by mutableStateOf(12f)
    var texOpacity by mutableStateOf(1f)
    var texHardness by mutableStateOf(0.8f)
    var texFilled by mutableStateOf(true)
    var texText by mutableStateOf("TEXTO")
    var texStamp by mutableStateOf<Triple<ByteArray, Int, Int>?>(null)
    var texStampScale by mutableStateOf(1f)
    var texStampRot by mutableStateOf(0f)
    var showUv by mutableStateOf(false)
    var uvWire by mutableStateOf<FloatArray?>(null); private set
    private val texSerial = Dispatchers.Default.limitedParallelism(1)

    fun texSlots(): List<TexSlot> {
        val s = ws ?: return emptyList(); val p = project ?: return emptyList()
        return s.model.parts.flatMap { part ->
            part.subMeshes.mapIndexedNotNull { i, sm ->
                val t = sm.material.texture ?: return@mapIndexedNotNull null
                val (ref, _) = try { p.textureOf(part.id, i) } catch (e: Throwable) { null to null }
                TexSlot(part.id, part.name, i, sm.material.textureName ?: t.name, t.width, t.height, ref != null)
            }
        }.distinctBy { Triple(it.partId, it.textureName, it.width) }
    }

    fun openTexture(partId: Int, sub: Int) {
        val p = project ?: return
        viewModelScope.launch(serial) {
            busy = "Abriendo textura…"
            try {
                val id = p.adoptTexture(partId, sub)
                if (id == null) { message = "Este material no tiene textura"; return@launch }
                commit(p)
                val doc = p.paintDocument(id)
                val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                texTarget = partId to sub; texId = id; texDoc = doc; texBitmap = bmp; texDirty = false
                uvWire = p.uvWire(partId, sub)
                paintBitmap(null)
            } catch (e: OutOfMemoryError) { message = "Memoria insuficiente para esta textura" } catch (e: Throwable) { message = "No se pudo abrir la textura: ${e.message}" } finally { busy = null }
        }
    }

    /** Copia al bitmap visible la región modificada (o todo). */
    private fun paintBitmap(rect: IntArray?) {
        val doc = texDoc ?: return; val bmp = texBitmap ?: return
        val x0 = rect?.get(0)?.coerceIn(0, doc.width) ?: 0; val y0 = rect?.get(1)?.coerceIn(0, doc.height) ?: 0
        val x1 = rect?.get(2)?.coerceIn(0, doc.width) ?: doc.width; val y1 = rect?.get(3)?.coerceIn(0, doc.height) ?: doc.height
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val px = doc.flattenRegion(x0, y0, w, h)
        val ints = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = px[i * 4].toInt() and 255; val g = px[i * 4 + 1].toInt() and 255; val b = px[i * 4 + 2].toInt() and 255; val a = px[i * 4 + 3].toInt() and 255
            ints[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(ints, 0, w, x0, y0, w, h)
        texRev++
    }

    /** Operación sobre el documento de pintura (serializada); [rect] = región afectada (null = toda la textura). */
    fun texOp(label: String, rect: IntArray?, block: (PaintDocument) -> Unit) {
        val doc = texDoc ?: return
        viewModelScope.launch(texSerial) {
            try { doc.begin(label); block(doc); doc.end(); texDirty = true; paintBitmap(rect) } catch (e: Throwable) { message = "Error en el editor: ${e.message}" }
        }
    }

    /** Variante continua (trazos): el llamador abre/cierra con [texBegin]/[texEnd]. */
    fun texBegin(label: String) { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.begin(label) } }
    fun texEnd() { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.end(); texDirty = true; texRev++ } }
    fun texLive(rect: IntArray?, block: (PaintDocument) -> Unit) {
        val d = texDoc ?: return
        viewModelScope.launch(texSerial) { try { block(d); paintBitmap(rect) } catch (e: Throwable) { message = "Error en el editor: ${e.message}" } }
    }

    fun texUndo() { val d = texDoc ?: return; viewModelScope.launch(texSerial) { if (d.undo()) { texDirty = true; paintBitmap(null) } } }
    fun texRedo() { val d = texDoc ?: return; viewModelScope.launch(texSerial) { if (d.redo()) { texDirty = true; paintBitmap(null) } } }
    fun texAddLayer(name: String) { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.addLayer(name); texRev++ } }
    fun texRemoveLayer(i: Int) { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.removeLayer(i); texDirty = true; paintBitmap(null) } }
    fun texLayerVisible(i: Int, v: Boolean) { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.setVisible(i, v); paintBitmap(null) } }
    fun texLayerOpacity(i: Int, o: Float) { val d = texDoc ?: return; viewModelScope.launch(texSerial) { d.setOpacity(i, o); paintBitmap(null) } }
    fun texActiveLayer(i: Int) { val d = texDoc ?: return; d.active = i.coerceIn(0, d.layers.size - 1); texRev++ }

    /** Guarda los píxeles en la textura del proyecto (visor 3D se actualiza; exportación incluye el cambio). */
    fun texCommit() {
        val p = project ?: return; val id = texId ?: return
        viewModelScope.launch(serial) {
            try { texSerial.let { }; p.commitTexture(id); texDirty = false; commit(p); message = "Textura guardada en el proyecto (se exporta con los cambios)" }
            catch (e: Throwable) { message = "No se pudo guardar la textura: ${e.message}" }
        }
    }

    fun texRestoreOriginal() {
        val p = project ?: return; val id = texId ?: return
        viewModelScope.launch(serial) {
            p.restoreTexture(id)
            val doc = p.paintDocument(id); texDoc = doc; texDirty = false
            paintBitmap(null); commit(p); message = "Textura original restaurada"
        }
    }

    fun texClose() { texDoc = null; texBitmap = null; texId = null; texTarget = null; uvWire = null }

    fun texExportPng(uri: Uri) {
        val d = texDoc ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val png = PngEncoder.encode(d.width, d.height, d.flatten())
                ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(png) } ?: error("No se pudo escribir")
                message = "PNG guardado (${d.width}×${d.height})"
            } catch (e: Throwable) { message = "No se pudo guardar el PNG: ${e.message}" }
        }
    }

    /** Importa PNG/JPG como sello (logo, calcomanía, patrón). Se coloca tocando la textura. */
    fun texLoadStamp(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1; while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Imagen no legible")
                texStamp = Triple(bitmapRgba(bmp), bmp.width, bmp.height)
                texTool = TexTool.STAMP
                message = "Imagen cargada (${bmp.width}×${bmp.height}): toque la textura para colocarla"
            } catch (e: Throwable) { message = "No se pudo cargar la imagen: ${e.message}" }
        }
    }

    /** Renderiza texto como sello con el color elegido. */
    fun texMakeText(): Triple<ByteArray, Int, Int> {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 96f; color = (0xFF shl 24) or texColor; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val b = android.graphics.Rect(); paint.getTextBounds(texText, 0, texText.length, b)
        val w = (b.width() + 16).coerceAtLeast(8); val h = (b.height() + 16).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawText(texText, 8f - b.left, 8f - b.top, paint)
        return Triple(bitmapRgba(bmp), w, h)
    }

    private fun bitmapRgba(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height; val ints = IntArray(w * h); bmp.getPixels(ints, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        for (i in ints.indices) { val c = ints[i]; out[i * 4] = (c shr 16).toByte(); out[i * 4 + 1] = (c shr 8).toByte(); out[i * 4 + 2] = c.toByte(); out[i * 4 + 3] = (c ushr 24).toByte() }
        return out
    }

    fun texPixel(x: Int, y: Int): Int? {
        val d = texDoc ?: return null
        if (x !in 0 until d.width || y !in 0 until d.height) return null
        val f = d.flattenRegion(x, y, 1, 1)
        return ((f[0].toInt() and 255) shl 16) or ((f[1].toInt() and 255) shl 8) or (f[2].toInt() and 255)
    }

    // ---- UV (edición básica por isla)

    fun uvIslands(): List<com.webmaletin.gtaviewer.uv.UvIsland> { val (pid, sub) = texTarget ?: return emptyList(); return try { project?.uvIslands(pid, sub) ?: emptyList() } catch (e: Throwable) { emptyList() } }
    fun uvStretchText(): String? { val (pid, sub) = texTarget ?: return null; return try { project?.uvStretch(pid, sub)?.let { "${it.stretched} de ${it.triangles} triángulos con estiramiento fuera de lo normal (peor relación ${"%.1f".format(it.worstRatio)}×)" } } catch (e: Throwable) { null } }
    fun uvTransform(island: Int, du: Float, dv: Float, scale: Float, rot: Float) {
        val (pid, sub) = texTarget ?: return
        opMsg { transformUvIsland(pid, sub, island, du, dv, scale, rot) }
        viewModelScope.launch(serial) { project?.let { uvWire = it.uvWire(pid, sub) } }
    }

    // ============================================================================ ajustes

    var defaultProfileName: String
        get() = prefs.getString("defaultProfile", ExportProfile.GODOT.name) ?: ExportProfile.GODOT.name
        set(v) { prefs.edit().putString("defaultProfile", v).apply(); libRev++ }

    fun storageBytes(): Long { libRev; return store.storageBytes() }
}
