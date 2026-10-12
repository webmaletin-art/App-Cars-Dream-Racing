package com.webmaletin.gtaviewer.project

import com.webmaletin.gtaviewer.export.*
import com.webmaletin.gtaviewer.fit.CompatResult
import com.webmaletin.gtaviewer.library.*
import com.webmaletin.gtaviewer.mesh.FlatMesh
import com.webmaletin.gtaviewer.mesh.MeshAnalysis
import com.webmaletin.gtaviewer.mesh.MeshFusion
import com.webmaletin.gtaviewer.mesh.RepairResult
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.texture.PaintDocument
import com.webmaletin.gtaviewer.txd.TextureImage
import com.webmaletin.gtaviewer.uv.UvTools

/**
 * Proyecto editable del taller de vehículos. [state] es inmutable y serializable (cada operación crea uno nuevo → deshacer/rehacer
 * trivial). La geometría vive en blobs deduplicados del catálogo; las ediciones de geometría crean blobs nuevos y conservan el
 * original (`origBlob`). Las texturas importadas nunca se modifican: se editan copias del proyecto.
 */
class CarProject(val store: LibraryStore, val id: String, initial: ProjectState) {
    var state: ProjectState = initial; private set
    var revision = 0; private set

    private val undoS = ArrayDeque<ProjectState>(); private val redoS = ArrayDeque<ProjectState>()
    val canUndo get() = undoS.isNotEmpty()
    val canRedo get() = redoS.isNotEmpty()

    private var geoCounter = 1
    private val decoded = HashMap<String, BlobCodec.Decoded>()
    private val live = HashMap<String, TextureImage>(); private val liveHash = HashMap<String, String>()
    private val docs = HashMap<String, PaintDocument>()

    init { syncLive() }

    // ------------------------------------------------------------------ estado / historial

    private fun push(label: String, f: (ProjectState) -> ProjectState) {
        undoS.addLast(state); if (undoS.size > 120) undoS.removeFirst()
        redoS.clear()
        val n = f(state)
        state = n.copy(modified = System.currentTimeMillis(), history = (n.history + label).takeLast(300))
        syncLive(); revision++
    }

    fun undo(): Boolean { val s = undoS.removeLastOrNull() ?: return false; redoS.addLast(state); state = s; syncLive(); revision++; return true }
    fun redo(): Boolean { val s = redoS.removeLastOrNull() ?: return false; undoS.addLast(state); state = s; syncLive(); revision++; return true }

    private fun syncLive() {
        for (t in state.textures) {
            if (liveHash[t.id] != t.hash) {
                store.texture(t.hash)?.let { live[t.id] = it.copy(t.name); liveHash[t.id] = t.hash; docs.remove(t.id) }
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    private fun decodedOf(blob: String) = decoded.getOrPut(blob) { BlobCodec.decode(store.blobBytes(blob), { geoCounter++ }) { store.texture(it) } }

    fun part(id: Int) = state.parts.firstOrNull { it.id == id }

    private fun gtaFromView(c: List<Float>) = listOf(-c[0], c[2], c[1])

    private fun transformOf(p: PPart): FloatArray {
        val t = p.trs
        var m = Mat4.multiply(Mat4.translate(-p.pivot[0], -p.pivot[1], -p.pivot[2]), p.baseTransform.toFloatArray())
        m = Mat4.multiply(Mat4.scale3(t.scale[0], t.scale[1], t.scale[2]), m)
        val r = Mat4.multiply(Mat4.rotZ(t.rot[2]), Mat4.multiply(Mat4.rotY(t.rot[1]), Mat4.rotX(t.rot[0])))
        m = Mat4.multiply(r, m)
        m = Mat4.multiply(Mat4.translate(p.pivot[0] + t.pos[0], p.pivot[1] + t.pos[1], p.pivot[2] + t.pos[2]), m)
        return m
    }

    private fun origKey(sd: BlobCodec.SubData) = sd.material.key + "#" + (sd.textureHash ?: "")

    private fun matDefOf(origin: BlobCodec.SubData, id: String = "orig") = MatDef(
        id, origin.material.name, origin.material.r, origin.material.g, origin.material.b, origin.material.a,
        origin.material.metallic, origin.material.roughness, origin.material.glass, "ORIGINAL", null, origin.material.paintSlot,
    )

    private fun adoptedTexture(sd: BlobCodec.SubData): TextureImage? =
        sd.textureHash?.let { h -> state.textures.firstOrNull { it.originalHash == h }?.let { live[it.id] } }

    private fun materialOf(p: PPart, i: Int, sd: BlobCodec.SubData): MaterialInfo {
        val matId = p.subMaterials[i.toString()] ?: state.overrides[origKey(sd)]
        val def = matId?.let { id -> state.materials.firstOrNull { it.id == id } }
        val base = sd.material
        if (def == null) {
            val adopted = adoptedTexture(sd) ?: return base
            return MaterialInfo(base.key, base.name, base.r, base.g, base.b, base.a, base.textureName, adopted, base.paintSlot, base.hasEnvMap, base.metallic, base.roughness, base.glass)
        }
        val (tex, name) = when (def.texMode) {
            "NONE" -> null to null
            "REF" -> def.texRef?.let { r -> live[r] to state.textures.firstOrNull { it.id == r }?.name } ?: (null to null)
            else -> (adoptedTexture(sd) ?: base.texture) to base.textureName
        }
        return MaterialInfo("mat:${def.id}", def.name, def.r, def.g, def.b, def.a, name, tex, def.paintSlot, false, def.metallic, def.roughness, def.glass)
    }

    // ------------------------------------------------------------------ construcción de modelo para visor y exportación

    /** Reconstruye el modelo (ids de parte/sub-mesh ESTABLES: id de parte y id·1000+índice). */
    fun materialize(): VehicleModel {
        val parts = state.parts.filter { !it.deleted }.map { p ->
            val d = decodedOf(p.blob)
            val subs = d.subs.mapIndexed { i, sd -> SubMesh(p.id * 1000 + i, p.id, d.geometries[sd.geometryIndex], sd.indices, materialOf(p, i, sd)) }
            MeshPart(p.id, p.name, PartCategory.fromName(p.category) ?: PartCategory.OTHER, p.wheelSlot?.let { runCatching { WheelSlot.valueOf(it) }.getOrNull() },
                null, transformOf(p), subs, p.visible, p.note, true, p.libraryId)
        }
        return ModelAssembler.assemble(state.name, "PROJECT", parts, validate = false)
    }

    /** Sub-meshes visibles en el visor 3D. */
    fun renderVisible(model: VehicleModel): Set<Int> = model.parts.filter { p -> part(p.id)?.visible == true }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()

    /** Sub-meshes que se exportan (independiente de si están visibles). */
    fun exportIds(model: VehicleModel): Set<Int> = model.parts.filter { p -> part(p.id)?.export == true }.flatMap { p -> p.subMeshes.map { it.id } }.toSet()

    fun exportSummary(): ExportSummary {
        val inc = ArrayList<String>(); val om = ArrayList<Pair<String, String>>(); val hid = ArrayList<String>()
        for (p in state.parts) when {
            p.deleted -> om += p.name to "eliminada del proyecto"
            !p.export -> om += p.name to if (p.visible) "excluida de la exportación (visible en pantalla)" else "excluida de la exportación y oculta"
            else -> { inc += p.name; if (!p.visible) hid += p.name }
        }
        return ExportSummary(inc, om, hid)
    }

    fun exportScene(profile: ExportProfile = state.exportProfile, onlyIds: Set<Int>? = null, name: String = state.name): ExportScene {
        val m = materialize()
        val ids = onlyIds ?: exportIds(m)
        val scene = ExportSceneBuilder.build(m, ids, PaintScheme(state.paintPrimary, state.paintSecondary), 1f, name)
        return ProfileApplier.apply(scene, profile)
    }

    // ------------------------------------------------------------------ altas

    private fun newPart(f: (Int) -> PPart): Int { val id = state.nextPartId; push("añadir pieza") { s -> s.copy(parts = s.parts + f(id), nextPartId = id + 1) }; return id }

    /** La carrocería base (todas sus partes visibles) se carga bloqueada: es la referencia y no se deforma automáticamente. */
    fun setBaseVehicle(vehicleId: String): Int {
        val man = store.manifestOf(vehicleId) ?: throw IllegalArgumentException("Vehículo no encontrado")
        val meta = store.get(vehicleId)!!
        var next = state.nextPartId
        val newParts = man.parts.filter { it.defaultVisible }.map { sp ->
            val piv = if (sp.bounds.size == 6) gtaFromView(listOf((sp.bounds[0] + sp.bounds[3]) / 2, (sp.bounds[1] + sp.bounds[4]) / 2, (sp.bounds[2] + sp.bounds[5]) / 2)) else listOf(0f, 0f, 0f)
            PPart(next++, sp.name, sp.category, "base", null, sp.blob, null, sp.transform, Trs(), piv, true, true, true, false, true, sp.wheelSlot)
        }
        push("carrocería base: ${meta.name}") { s -> s.copy(parts = s.parts.filter { !it.isBase } + newParts, nextPartId = next, baseVehicleId = vehicleId, name = if (s.name.isBlank() || s.name == "Proyecto") "Proyecto ${meta.name}" else s.name) }
        return newParts.size
    }

    /** Añade una pieza del catálogo; si se pasa [fit] se coloca con la escala/desplazamiento sugeridos. */
    fun addPiece(pieceId: String, fit: CompatResult? = null): Int {
        val meta = store.get(pieceId) ?: throw IllegalArgumentException("Pieza no encontrada")
        require(meta.kind == "COMPONENT") { "Solo se pueden añadir componentes (use 'carrocería base' para un vehículo)" }
        val piv = gtaFromView(meta.center)
        val trs = fit?.let { f -> val d = gtaFromView(f.deltaView.toList()).let { listOf(it[0], it[1], it[2]) }
            // gtaFromView invierte el signo de X y reordena ejes: para un DESPLAZAMIENTO se aplica la misma conversión lineal
            Trs(listOf(-f.deltaView[0], f.deltaView[2], f.deltaView[1]), listOf(0f, 0f, 0f), listOf(f.scale, f.scale, f.scale)) } ?: Trs()
        return newPart { id -> PPart(id, meta.name, meta.category, "library", pieceId, meta.blobs.first(), null, meta.transform.ifEmpty { Mat4.identity().toList() }, trs, piv, true, true, false) }
    }

    /**
     * Pone una rueda del catálogo en cada rueda de la base. La orientación de cada instancia se deduce de la geometría (cara exterior
     * hacia fuera) con la misma regla que el importador; la escala iguala el diámetro de las ruedas de la base.
     */
    fun addWheelSet(pieceId: String): List<Int> {
        val meta = store.get(pieceId) ?: throw IllegalArgumentException("Pieza no encontrada")
        val targets = state.parts.filter { it.isBase && !it.deleted && it.category == PartCategory.WHEEL.name }
        require(targets.isNotEmpty()) { "La carrocería base no tiene ruedas identificadas: añada la rueda manualmente" }
        val d = decodedOf(meta.blobs.first())
        val pieces = d.subs.map { WheelOrientation.Piece(d.geometries[it.geometryIndex], it.indices, it.material.textureName) }
        val outcome = WheelOrientation.analyze(pieces)
        val an = outcome.analysis
        val tr = meta.transform.ifEmpty { Mat4.identity().toList() }.toFloatArray()
        val baseDiam = targets.map { t -> val b = partBoundsView(t); b[4] - b[1] }.average().toFloat()
        val scale = if (meta.dims.h > 0.05f) (baseDiam / meta.dims.h).coerceIn(0.5f, 2f) else 1f
        val ids = ArrayList<Int>()
        var next = state.nextPartId
        val created = targets.map { t ->
            val bv = partBoundsView(t)
            val centerGta = gtaFromView(listOf((bv[0] + bv[3]) / 2, (bv[1] + bv[4]) / 2, (bv[2] + bv[5]) / 2))
            val local = an?.center ?: floatArrayOf(0f, 0f, 0f)
            var m = Mat4.translate(centerGta[0] - local[0], centerGta[1] - local[1], centerGta[2] - local[2])
            var note: String? = null
            if (an != null) {
                val dec = WheelOrientation.decide(an, m)
                dec.correction?.let { c -> m = Mat4.multiply(m, c.local); note = "orientada: ${c.note}" }
            } else note = "orientación no determinable (${outcome.reason}); colocada sin girar"
            val pid = next++
            ids += pid
            PPart(pid, "${meta.name} ${t.wheelSlot ?: ""}".trim(), PartCategory.WHEEL.name, "library", pieceId, meta.blobs.first(), null, m.toList(), Trs(listOf(0f, 0f, 0f), listOf(0f, 0f, 0f), listOf(scale, scale, scale)), centerGta, true, true, false, false, false, t.wheelSlot, emptyMap(), note)
        }
        push("juego de ruedas: ${meta.name}") { s -> s.copy(parts = s.parts + created, nextPartId = next) }
        return ids
    }

    /** Caja de la parte en espacio de visor (min xyz, max xyz). */
    fun partBoundsView(p: PPart): FloatArray {
        val d = decodedOf(p.blob)
        val m = Mat4.multiply(Mat4.GTA_TO_VIEW, transformOf(p))
        val b = Bounds.empty(); val t = FloatArray(3)
        for (sd in d.subs) { val g = d.geometries[sd.geometryIndex]; for (i in sd.indices) { Mat4.transformPoint(m, g.positions[i * 3], g.positions[i * 3 + 1], g.positions[i * 3 + 2], t); b.expand(t[0], t[1], t[2]) } }
        return b.min + b.max
    }

    // ------------------------------------------------------------------ estado por pieza (visibilidad ≠ exportación)

    private fun mapParts(label: String, ids: Collection<Int>, f: (PPart) -> PPart) { push(label) { s -> s.copy(parts = s.parts.map { if (it.id in ids) f(it) else it }) } }

    fun setVisible(ids: Collection<Int>, v: Boolean) = mapParts(if (v) "mostrar" else "ocultar", ids) { it.copy(visible = v) }
    fun setExport(ids: Collection<Int>, e: Boolean) = mapParts(if (e) "incluir en exportación" else "excluir de exportación", ids) { it.copy(export = e) }
    fun setLocked(ids: Collection<Int>, l: Boolean) = mapParts(if (l) "bloquear" else "desbloquear", ids) { it.copy(locked = l) }
    fun rename(id: Int, name: String) = mapParts("renombrar", listOf(id)) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun setCategory(ids: Collection<Int>, c: PartCategory) = mapParts("categoría", ids) { it.copy(category = c.name) }
    fun delete(ids: Collection<Int>) = mapParts("eliminar", ids) { it.copy(deleted = true) }
    fun restore(ids: Collection<Int>) = mapParts("recuperar", ids) { it.copy(deleted = false) }
    fun deletedParts() = state.parts.filter { it.deleted }

    private fun lockedCheck(ids: Collection<Int>): String? =
        state.parts.filter { it.id in ids && it.locked }.takeIf { it.isNotEmpty() }?.let { "Bloqueada: ${it.joinToString { p -> p.name }}. Desbloquéela para transformarla." }

    // ------------------------------------------------------------------ transformaciones

    fun setTrs(id: Int, trs: Trs): String? { lockedCheck(listOf(id))?.let { return it }; mapParts("transformar", listOf(id)) { it.copy(trs = trs) }; return null }

    fun nudge(ids: Collection<Int>, dPos: FloatArray = floatArrayOf(0f, 0f, 0f), dRot: FloatArray = floatArrayOf(0f, 0f, 0f), scaleFactor: Float = 1f): String? {
        lockedCheck(ids)?.let { return it }
        mapParts("mover/rotar/escalar", ids) { p -> val t = p.trs
            p.copy(trs = Trs(listOf(t.pos[0] + dPos[0], t.pos[1] + dPos[1], t.pos[2] + dPos[2]), listOf(t.rot[0] + dRot[0], t.rot[1] + dRot[1], t.rot[2] + dRot[2]), t.scale.map { it * scaleFactor })) }
        return null
    }

    private var gestureLabel: String? = null

    /** Gesto continuo (arrastrar con el dedo): UN solo paso de deshacer; los cambios intermedios no generan historial. */
    fun beginGesture(label: String) {
        undoS.addLast(state); if (undoS.size > 120) undoS.removeFirst(); redoS.clear(); gestureLabel = label
    }

    fun liveNudge(ids: Collection<Int>, dPos: FloatArray): String? {
        lockedCheck(ids)?.let { return it }
        state = state.copy(parts = state.parts.map { p -> if (p.id in ids) p.copy(trs = p.trs.copy(pos = listOf(p.trs.pos[0] + dPos[0], p.trs.pos[1] + dPos[1], p.trs.pos[2] + dPos[2]))) else p })
        revision++; return null
    }

    fun endGesture() {
        val l = gestureLabel ?: return; gestureLabel = null
        state = state.copy(modified = System.currentTimeMillis(), history = (state.history + l).takeLast(300))
    }

    fun resetTrs(ids: Collection<Int>): String? { lockedCheck(ids)?.let { return it }; mapParts("restablecer transformación", ids) { it.copy(trs = Trs()) }; return null }

    /** Aplica una colocación sugerida (escala controlada + desplazamiento) a una pieza ya añadida. La base nunca se mueve. */
    fun applyFit(id: Int, f: CompatResult): String? {
        val p = part(id) ?: return "Pieza inexistente"
        if (p.isBase) return "La carrocería base es la referencia y no se ajusta automáticamente"
        lockedCheck(listOf(id))?.let { return it }
        return setTrs(id, Trs(listOf(-f.deltaView[0], f.deltaView[2], f.deltaView[1]), p.trs.rot, listOf(f.scale, f.scale, f.scale)))
    }

    /** El ajuste actual se recuerda para colocaciones futuras de la misma pieza en la misma base. */
    fun confirmFit(id: Int) {
        val p = part(id) ?: return; val base = state.baseVehicleId ?: return; val lib = p.libraryId ?: return
        store.learnFit(LearnedFit(base, lib, p.category, p.trs.scale[0], listOf(-p.trs.pos[0], p.trs.pos[2], p.trs.pos[1]), p.trs.rot))
    }

    // ------------------------------------------------------------------ duplicar / reemplazar / espejo

    fun duplicate(id: Int): Int? {
        val p = part(id) ?: return null
        val nid = state.nextPartId
        push("duplicar ${p.name}") { s -> s.copy(parts = s.parts + p.copy(id = nid, name = p.name + " (copia)", origin = "copy", locked = false, isBase = false), nextPartId = nid + 1) }
        return nid
    }

    /** Sustituye una pieza por otra del catálogo (colocada en su lugar); la original queda oculta y excluida, no se destruye. */
    fun replace(oldId: Int, pieceId: String, fit: CompatResult?): Int? {
        val old = part(oldId) ?: return null
        lockedCheck(listOf(oldId))?.let { return null }
        val nid = addPiece(pieceId, fit)
        mapParts("sustituir ${old.name}", listOf(oldId)) { it.copy(visible = false, export = false, note = "sustituida por $pieceId") }
        return nid
    }

    /** Copia especular respecto al plano medio del vehículo (x = 0): p. ej. puerta izquierda → derecha. */
    fun mirror(id: Int): Int? {
        val p = part(id) ?: return null
        val flat = flatOf(p, withTransform = true)
        val mirrored = flat.transformed(Mat4.scale3(-1f, 1f, 1f))
        return addGeometry(mirrored, p.name + " (espejo)", PartCategory.fromName(p.category) ?: PartCategory.OTHER, "edited", p.libraryId, null)
    }

    // ------------------------------------------------------------------ geometría (siempre sobre copias; el original se conserva)

    private fun flatOf(p: PPart, withTransform: Boolean = false): FlatMesh {
        val m = materialize()
        val part = m.parts.firstOrNull { it.id == p.id } ?: throw IllegalStateException("Pieza no disponible")
        return FlatMesh.fromPart(part, if (withTransform) part.transform else null)
    }

    private fun storeFlat(flat: FlatMesh, name: String, cat: PartCategory): String {
        var gid = 0; var sid = 0
        val mp = flat.toPart(0, name, cat, Mat4.identity(), gid, { sid++ })
        val h = store.putBlob(BlobCodec.encode(mp) { store.putTexture(it) })
        return h
    }

    private fun pivotOf(flat: FlatMesh): List<Float> {
        val b = flat.bounds(); return if (b.isValid) b.center.toList() else listOf(0f, 0f, 0f)
    }

    /** Añade una malla en espacio mundo GTA como parte nueva (transformación identidad). */
    private fun addGeometry(flat: FlatMesh, name: String, cat: PartCategory, origin: String, libId: String?, from: List<Int>?): Int {
        val h = storeFlat(flat, name, cat)
        return newPart { id -> PPart(id, name, cat.name, origin, libId, h, null, Mat4.identity().toList(), Trs(), pivotOf(flat), true, true, false, false, false, null, emptyMap(), null, from ?: emptyList()) }
    }

    private fun replaceGeometry(id: Int, flatLocal: FlatMesh, label: String, note: String) {
        val p = part(id)!!
        val h = storeFlat(flatLocal, p.name, PartCategory.fromName(p.category) ?: PartCategory.OTHER)
        mapParts(label, listOf(id)) { it.copy(blob = h, origBlob = it.origBlob ?: it.blob, origin = if (it.origin == "base") it.origin else "edited", note = note, subMaterials = emptyMap()) }
    }

    /** Índice de triángulo en el aplanado de la parte a partir de (índice de sub-mesh, triángulo dentro del sub-mesh). */
    fun flatTriangle(partId: Int, subIndex: Int, triInSub: Int): Int {
        val d = decodedOf(part(partId)!!.blob)
        return d.subs.take(subIndex).sumOf { it.indices.size / 3 } + triInSub
    }

    fun deleteFaces(partId: Int, flatTris: Set<Int>): String? {
        val p = part(partId) ?: return "Pieza inexistente"; lockedCheck(listOf(partId))?.let { return it }
        if (flatTris.isEmpty()) return "No hay caras seleccionadas"
        val flat = flatOf(p); val res = flat.withoutTriangles(flatTris)
        if (res.triCount == 0) return "Se eliminarían todas las caras: use 'Eliminar pieza'"
        replaceGeometry(partId, res, "eliminar ${flatTris.size} caras", "${flatTris.size} caras eliminadas (UV, normales y materiales del resto intactos)")
        return null
    }

    /** Separa las caras seleccionadas en una pieza nueva (la original conserva el resto). */
    fun separateFaces(partId: Int, flatTris: Set<Int>): Int? {
        val p = part(partId) ?: return null; lockedCheck(listOf(partId))?.let { return null }
        if (flatTris.isEmpty()) return null
        val flat = flatOf(p)
        val sel = flat.subset(flatTris.sorted()); val rest = flat.withoutTriangles(flatTris)
        val cat = PartCategory.fromName(p.category) ?: PartCategory.OTHER
        val h = storeFlat(sel, p.name + " (sección)", cat)
        val nid = state.nextPartId
        push("separar ${flatTris.size} caras de ${p.name}") { s ->
            val restBlob = if (rest.triCount > 0) storeFlat(rest, p.name, cat) else p.blob
            s.copy(parts = s.parts.map { if (it.id == partId && rest.triCount > 0) it.copy(blob = restBlob, origBlob = it.origBlob ?: it.blob, origin = "edited", subMaterials = emptyMap()) else it } +
                p.copy(id = nid, name = p.name + " (sección)", blob = h, origBlob = null, origin = "edited", locked = false, isBase = false, subMaterials = emptyMap(), pivot = pivotOf(sel).let { c -> val t = transformOf(p); val o = FloatArray(3); Mat4.transformPoint(t, c[0], c[1], c[2], o); o.toList() }),
                nextPartId = nid + 1)
        }
        return nid
    }

    /** Separa una pieza en sus componentes conexos (no destructivo: la pieza original queda oculta y excluida). */
    fun splitComponents(partId: Int, minTriangles: Int = 1): List<Int> {
        val p = part(partId) ?: return emptyList()
        val flat = flatOf(p); val comps = MeshAnalysis.connectedComponents(flat).filter { it.size >= minTriangles }
        if (comps.size < 2) return emptyList()
        val cat = PartCategory.fromName(p.category) ?: PartCategory.OTHER
        var next = state.nextPartId
        val created = comps.mapIndexed { i, c -> val f = flat.subset(c.toList()); PPart(next++, "${p.name} · parte ${i + 1}", cat.name, "edited", p.libraryId, storeFlat(f, p.name, cat), null, p.baseTransform, p.trs, p.pivot, true, true, false, false, false, p.wheelSlot, emptyMap(), "componente conexo ${i + 1}/${comps.size}", emptyList()) }
        push("separar ${p.name} en ${comps.size} componentes") { s -> s.copy(parts = s.parts.map { if (it.id == partId) it.copy(visible = false, export = false, note = "origen de la separación en ${comps.size} partes") else it } + created, nextPartId = next) }
        return created.map { it.id }
    }

    /** Región de caras conectadas con ángulo suave a partir de una cara semilla (índice del aplanado de la parte). */
    fun growFaceRegion(partId: Int, seedFlatTri: Int, angleDeg: Float, sameMaterial: Boolean): Set<Int> {
        val p = part(partId) ?: return emptySet()
        return MeshAnalysis.growRegion(flatOf(p), seedFlatTri, angleDeg, sameMaterial)
    }

    fun faceCount(partId: Int): Int { val p = part(partId) ?: return 0; return decodedOf(p.blob).subs.sumOf { it.indices.size / 3 } }

    /** Triángulos (x,y,z ×3 por cara) en espacio de visor de las caras indicadas, para resaltarlas en pantalla. */
    fun faceOverlay(partId: Int, flatTris: Collection<Int>, limit: Int = 20000): FloatArray {
        val p = part(partId) ?: return FloatArray(0)
        val f = flatOf(p, withTransform = true)
        val out = FloatArray(minOf(flatTris.size, limit) * 9); var n = 0; val t = FloatArray(3)
        for (tri in flatTris) {
            if (n + 9 > out.size) break
            if (tri < 0 || tri >= f.triCount) continue
            for (k in 0..2) { val v = f.tris[tri * 3 + k]; Mat4.transformPoint(Mat4.GTA_TO_VIEW, f.pos[v * 3], f.pos[v * 3 + 1], f.pos[v * 3 + 2], t); out[n++] = t[0]; out[n++] = t[1]; out[n++] = t[2] }
        }
        return out.copyOf(n)
    }

    fun analyze(partId: Int) = flatOf(part(partId) ?: throw IllegalArgumentException("Pieza inexistente")).let { MeshAnalysis.analyze(it) }

    /** Repara una copia y VALIDA (re-analiza). Solo se aplica si la reparación mejora las métricas; devuelve el informe. */
    fun repair(partId: Int, apply: Boolean = true): RepairResult? {
        val p = part(partId) ?: return null
        val res = MeshAnalysis.repair(flatOf(p))
        if (apply && res.validated) replaceGeometry(partId, res.mesh, "reparar ${p.name}", "reparada y validada: ${res.actions.joinToString("; ")}")
        return res
    }

    /** Combina (une mallas) varias piezas en una sola, con sus transformaciones horneadas. Las originales se conservan ocultas/excluidas. */
    fun combine(ids: List<Int>, name: String = "Malla combinada"): Int? {
        val parts = ids.mapNotNull { part(it) }; if (parts.size < 2) return null
        val flat = FlatMesh.combine(parts.map { flatOf(it, true) })
        val nid = addGeometry(flat, name, PartCategory.BODY, "merged", null, ids)
        mapParts("ocultar originales de la combinación", ids) { it.copy(visible = false, export = false, note = "combinada en '$name'") }
        return nid
    }

    /**
     * Fusiona superficies: combina y además suelda vértices coincidentes (misma posición y UV) y unifica normales de costura.
     * No es una booleana de volumen. Las originales se conservan (ocultas y excluidas) para poder volver atrás.
     */
    fun fuse(ids: List<Int>, name: String = "Superficie fusionada"): Pair<Int, MeshFusion.FuseResult>? {
        val parts = ids.mapNotNull { part(it) }; if (parts.size < 2) return null
        val cfg = state.fusion
        val flat = FlatMesh.combine(parts.map { flatOf(it, true) })
        val fr = MeshFusion.fuse(flat, cfg.weldTolerance, cfg.uvTolerance)
        val nid = addGeometry(fr.mesh, name, PartCategory.BODY, "merged", null, ids)
        mapParts("ocultar originales de la fusión", ids) { it.copy(visible = false, export = false, note = "fusionada en '$name'") }
        return nid to fr
    }

    /** Acerca los bordes de [pieceId] a los vértices cercanos de [baseId] (tolerancia de la configuración de fusión). */
    fun stitch(baseId: Int, pieceId: Int): Int {
        val b = part(baseId) ?: return 0; val p = part(pieceId) ?: return 0
        if (p.locked) return 0
        val (res, moved) = MeshFusion.stitch(flatOf(b, true), flatOf(p, true), state.fusion.stitchTolerance)
        if (moved > 0) {
            val cat = PartCategory.fromName(p.category) ?: PartCategory.OTHER
            val h = storeFlat(res, p.name, cat)
            mapParts("coser bordes de ${p.name}", listOf(pieceId)) { it.copy(blob = h, origBlob = it.origBlob ?: it.blob, baseTransform = Mat4.identity().toList(), trs = Trs(), pivot = pivotOf(res), origin = "edited", note = "$moved vértices de borde cosidos a '${b.name}'") }
        }
        return moved
    }

    fun restoreGeometry(partId: Int): Boolean {
        val p = part(partId) ?: return false; val o = p.origBlob ?: return false
        mapParts("restaurar geometría original", listOf(partId)) { it.copy(blob = o, origBlob = null, note = "geometría original restaurada", subMaterials = emptyMap()) }
        return true
    }

    /** Edita las UV de una isla (copia: la distribución original se puede restaurar con [restoreGeometry]). */
    fun transformUvIsland(partId: Int, subIndex: Int, islandIndex: Int, du: Float, dv: Float, scale: Float, rotDeg: Float): String? {
        val p = part(partId) ?: return "Pieza inexistente"; lockedCheck(listOf(partId))?.let { return it }
        val d = decodedOf(p.blob); val sd = d.subs.getOrNull(subIndex) ?: return "Sub-malla inexistente"
        val g = d.geometries[sd.geometryIndex]
        val isl = UvTools.islands(g, sd.indices).getOrNull(islandIndex) ?: return "Isla inexistente"
        val newUv = UvTools.transformIsland(g.uvs, sd.indices, isl, du, dv, scale, rotDeg)
        // el nuevo blob usa la geometría con las UV cambiadas
        val g2 = GeometryData(0, g.vertexCount, g.positions, g.normals, newUv, g.hasOriginalNormals, true, g.hasVertexColors)
        val subs = d.subs.mapIndexed { i, s -> SubMesh(i, 0, if (s.geometryIndex == sd.geometryIndex) g2 else d.geometries[s.geometryIndex], s.indices, s.material) }
        val mp = MeshPart(0, p.name, PartCategory.fromName(p.category) ?: PartCategory.OTHER, null, null, Mat4.identity(), subs, true, null, true)
        val h = store.putBlob(BlobCodec.encode(mp) { store.putTexture(it) })
        mapParts("editar UV", listOf(partId)) { it.copy(blob = h, origBlob = it.origBlob ?: it.blob, note = "UV editadas (isla $islandIndex)") }
        return null
    }

    /** Aristas UV de un sub-mesh: (u0,v0,u1,v1,u2,v2) por triángulo, para dibujar el mapa UV sobre la textura. */
    fun uvWire(partId: Int, subIndex: Int): FloatArray {
        val p = part(partId) ?: return FloatArray(0); val d = decodedOf(p.blob); val sd = d.subs.getOrNull(subIndex) ?: return FloatArray(0)
        val g = d.geometries[sd.geometryIndex]; val n = sd.indices.size / 3; val out = FloatArray(n * 6)
        for (t in 0 until n) for (k in 0..2) { val v = sd.indices[t * 3 + k]; out[t * 6 + k * 2] = g.uvs[v * 2]; out[t * 6 + k * 2 + 1] = g.uvs[v * 2 + 1] }
        return out
    }

    fun uvIslands(partId: Int, subIndex: Int): List<com.webmaletin.gtaviewer.uv.UvIsland> {
        val d = decodedOf(part(partId)!!.blob); val sd = d.subs[subIndex]
        return UvTools.islands(d.geometries[sd.geometryIndex], sd.indices)
    }

    fun uvStretch(partId: Int, subIndex: Int): com.webmaletin.gtaviewer.uv.StretchReport {
        val d = decodedOf(part(partId)!!.blob); val sd = d.subs[subIndex]
        return UvTools.stretch(d.geometries[sd.geometryIndex], sd.indices)
    }

    // ------------------------------------------------------------------ materiales

    /** Clave del material de origen de un sub-mesh. */
    fun materialKeyOf(partId: Int, subIndex: Int): String = origKey(decodedOf(part(partId)!!.blob).subs[subIndex])

    /** Material actual (propio del proyecto o el original convertido a definición editable). */
    fun materialDef(partId: Int, subIndex: Int): MatDef {
        val p = part(partId)!!; val sd = decodedOf(p.blob).subs[subIndex]
        val id = p.subMaterials[subIndex.toString()] ?: state.overrides[origKey(sd)]
        return id?.let { i -> state.materials.firstOrNull { it.id == i } } ?: matDefOf(sd)
    }

    /** Edita el material de ORIGEN de un sub-mesh: afecta a todos los sub-meshes que lo usan en el proyecto (p. ej. toda la pintura). */
    fun editMaterial(partId: Int, subIndex: Int, f: (MatDef) -> MatDef) {
        val p = part(partId)!!; val sd = decodedOf(p.blob).subs[subIndex]; val key = origKey(sd)
        push("editar material ${sd.material.name}") { s ->
            val existingId = s.overrides[key]
            val cur = existingId?.let { i -> s.materials.firstOrNull { it.id == i } }
            if (cur != null) s.copy(materials = s.materials.map { if (it.id == cur.id) f(it).copy(id = cur.id) else it })
            else { val nid = "m${s.nextMatId}"; s.copy(materials = s.materials + f(matDefOf(sd, nid)).copy(id = nid), overrides = s.overrides + (key to nid), nextMatId = s.nextMatId + 1) }
        }
    }

    fun restoreMaterial(partId: Int, subIndex: Int) {
        val sd = decodedOf(part(partId)!!.blob).subs[subIndex]
        push("restaurar material original") { s -> s.copy(overrides = s.overrides - origKey(sd)) }
    }

    /** Crea un material nuevo y lo asigna SOLO a este sub-mesh. */
    fun newMaterialFor(partId: Int, subIndex: Int, name: String): String {
        val sd = decodedOf(part(partId)!!.blob).subs[subIndex]
        var id = ""
        push("material nuevo $name") { s -> id = "m${s.nextMatId}"
            s.copy(materials = s.materials + matDefOf(sd, id).copy(name = name), nextMatId = s.nextMatId + 1, parts = s.parts.map { if (it.id == partId) it.copy(subMaterials = it.subMaterials + (subIndex.toString() to id)) else it }) }
        return id
    }

    fun assignMaterial(partId: Int, subIndex: Int, matId: String?) =
        mapParts("asignar material", listOf(partId)) { it.copy(subMaterials = if (matId == null) it.subMaterials - subIndex.toString() else it.subMaterials + (subIndex.toString() to matId)) }

    fun duplicateMaterial(matId: String): String? {
        val m = state.materials.firstOrNull { it.id == matId } ?: return null
        var id = ""
        push("duplicar material") { s -> id = "m${s.nextMatId}"; s.copy(materials = s.materials + m.copy(id = id, name = m.name + " (copia)"), nextMatId = s.nextMatId + 1) }
        return id
    }

    fun setPaint(primary: Int? = null, secondary: Int? = null) = push("pintura de carrocería") { s -> s.copy(paintPrimary = primary ?: s.paintPrimary, paintSecondary = secondary ?: s.paintSecondary) }

    // ------------------------------------------------------------------ texturas (se guardan en píxeles)

    /** Imagen de textura del sub-mesh (la editada si ya se adoptó; si no, la original) y el id de TexRef si existe. */
    fun textureOf(partId: Int, subIndex: Int): Pair<String?, TextureImage?> {
        val sd = decodedOf(part(partId)!!.blob).subs[subIndex]
        val ref = sd.textureHash?.let { h -> state.textures.firstOrNull { it.originalHash == h } }
        return ref?.id to (ref?.let { live[it.id] } ?: sd.material.texture)
    }

    /** Hace editable la textura de un sub-mesh (crea una copia del proyecto; la importada no se toca). Devuelve el id de TexRef. */
    fun adoptTexture(partId: Int, subIndex: Int): String? {
        val sd = decodedOf(part(partId)!!.blob).subs[subIndex]
        val tex = sd.material.texture ?: return null; val oh = sd.textureHash ?: return null
        state.textures.firstOrNull { it.originalHash == oh }?.let { return it.id }
        val id = "t${state.nextTexId}"
        val ref = TexRef(id, tex.name, oh, oh, tex.width, tex.height)
        live[id] = tex.copy(); liveHash[id] = oh
        push("textura editable ${tex.name}") { s -> s.copy(textures = s.textures + ref, nextTexId = s.nextTexId + 1) }
        return id
    }

    fun liveTexture(id: String): TextureImage? = live[id]
    fun textureRef(id: String) = state.textures.firstOrNull { it.id == id }

    /** Documento de pintura de la textura (capas guardadas o capa base). */
    fun paintDocument(texId: String): PaintDocument = docs.getOrPut(texId) {
        val ref = state.textures.first { it.id == texId }
        val orig = store.texture(ref.originalHash)!!
        val doc = PaintDocument(ref.width, ref.height, (if (ref.layers.isEmpty()) store.texture(ref.hash) ?: orig else store.texture(ref.layers[0].hash) ?: orig).rgba, ref.name)
        // el original de restauración es la textura importada
        System.arraycopy(orig.rgba, 0, doc.original, 0, orig.rgba.size)
        for (lr in ref.layers.drop(1)) store.texture(lr.hash)?.let { img -> doc.addLayer(lr.name); System.arraycopy(img.rgba, 0, doc.layers.last().rgba, 0, img.rgba.size); doc.layers.last().visible = lr.visible; doc.layers.last().opacity = lr.opacity }
        doc.active = 0
        doc
    }

    /** Aplana el documento, actualiza la textura viva (visor) y guarda los píxeles + capas en el catálogo. */
    fun commitTexture(texId: String) {
        val doc = docs[texId] ?: return
        val img = live[texId] ?: return
        doc.commitTo(img)
        val flatHash = store.putTexture(img)
        val layerRefs = doc.layers.mapIndexed { i, l -> LayerRef(l.name, store.putTexture(TextureImage("layer$i:${doc.name}", doc.width, doc.height, l.rgba.copyOf(), true, "layer")), l.visible, l.opacity) }
        liveHash[texId] = flatHash
        push("pintar textura ${doc.name}") { s -> s.copy(textures = s.textures.map { if (it.id == texId) it.copy(hash = flatHash, layers = layerRefs) else it }) }
        liveHash[texId] = flatHash
    }

    fun restoreTexture(texId: String) {
        val ref = textureRef(texId) ?: return
        val orig = store.texture(ref.originalHash) ?: return
        live[texId]?.let { System.arraycopy(orig.rgba, 0, it.rgba, 0, it.rgba.size); it.markChanged() }
        docs.remove(texId)
        liveHash[texId] = ref.originalHash
        push("restaurar textura original") { s -> s.copy(textures = s.textures.map { if (it.id == texId) it.copy(hash = it.originalHash, layers = emptyList()) else it }) }
    }

    /** Guarda el estado actual de pintura como variante (color de carrocería + texturas editadas). */
    fun saveVariant(name: String) = push("variante $name") { s -> s.copy(variants = s.variants.filter { it.name != name } + PaintVariant(name, s.paintPrimary, s.textures.associate { it.id to it.hash }, s.textures.associate { it.id to it.layers })) }

    fun applyVariant(name: String): Boolean {
        val v = state.variants.firstOrNull { it.name == name } ?: return false
        push("aplicar variante $name") { s -> s.copy(paintPrimary = v.primary, textures = s.textures.map { t -> v.textures[t.id]?.let { h -> t.copy(hash = h, layers = v.layers[t.id] ?: emptyList()) } ?: t }) }
        return true
    }

    // ------------------------------------------------------------------ anclajes / configuración

    fun addAnchor(a: Anchor) = push("anclaje ${a.name}") { s -> s.copy(anchors = s.anchors.filter { it.name != a.name } + a) }
    fun setFusion(c: FusionConfig) = push("configuración de fusión") { s -> s.copy(fusion = c) }
    fun setExportProfile(p: ExportProfile) = push("perfil de exportación") { s -> s.copy(exportProfile = p) }
    fun setName(n: String) = push("renombrar proyecto") { s -> s.copy(name = n.trim().ifEmpty { s.name }) }
}
