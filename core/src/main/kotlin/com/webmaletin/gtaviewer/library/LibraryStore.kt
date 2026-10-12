package com.webmaletin.gtaviewer.library

import com.webmaletin.gtaviewer.classify.VehicleClassifier
import com.webmaletin.gtaviewer.classify.FeatureExtractor
import com.webmaletin.gtaviewer.export.PngEncoder
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.txd.TextureImage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SourceInfo(
    val name: String, val source: String, val origin: String,
    val dffHash: String, val txdHash: String?, val dffFileName: String, val txdFileName: String?,
)

class AddResult(val vehicleId: String?, val duplicateOf: String?, val newComponents: Int, val mergedComponents: Int, val similar: Int)
class BackupSummary(val addedPieces: Int, val skippedPieces: Int, val blobs: Int)

/**
 * Biblioteca persistente. Guarda en disco SOLO resultados procesados (índice, manifiestos, blobs de geometría y texturas
 * deduplicados por hash), no los archivos originales, y mantiene en memoria únicamente los metadatos. Nunca borra ni modifica
 * archivos fuera de [root].
 */
class LibraryStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val lock = Any()
    private val index = LinkedHashMap<String, PieceMeta>()
    private var corrections = Corrections()
    private var custom = CustomCategories()
    private var fits = LearnedFits()
    private val blobDir = File(root, "blobs"); private val texDir = File(root, "tex")
    private val vehDir = File(root, "vehicles"); private val thumbDir = File(root, "thumbs")

    init {
        listOf(blobDir, texDir, vehDir, thumbDir).forEach { it.mkdirs() }
        File(root, "index.json").takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString<LibraryIndex>(f.readText()).pieces.forEach { index[it.id] = it } }
                .onFailure { // índice dañado: se conserva una copia y se reconstruye vacío en vez de perder los blobs
                    f.copyTo(File(root, "index.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
                }
        }
        File(root, "corrections.json").takeIf { it.exists() }?.let { runCatching { corrections = json.decodeFromString(it.readText()) } }
        File(root, "categories.json").takeIf { it.exists() }?.let { runCatching { custom = json.decodeFromString(it.readText()) } }
        File(root, "fits.json").takeIf { it.exists() }?.let { runCatching { fits = json.decodeFromString(it.readText()) } }
    }

    val projectsDir: File get() = File(root, "projects").also { it.mkdirs() }

    fun learnedFits(): List<LearnedFit> = fits.items
    fun learnFit(f: LearnedFit) {
        val old = fits.items.firstOrNull { it.baseId == f.baseId && it.pieceId == f.pieceId }
        fits = LearnedFits(fits.items.filter { it !== old } + (old?.let { f.copy(count = it.count + 1) } ?: f))
        atomicWrite(File(root, "fits.json"), json.encodeToString(fits).toByteArray())
    }

    fun manifestOf(id: String): VehicleManifest? = manifest(id)

    // ------------------------------------------------------------------ consulta básica

    fun all(): List<PieceMeta> = synchronized(lock) { index.values.toList() }
    fun get(id: String): PieceMeta? = synchronized(lock) { index[id] }
    fun vehicles() = all().filter { it.kind == "VEHICLE" }
    fun components() = all().filter { it.kind == "COMPONENT" }
    fun componentsOf(vehicleId: String) = all().filter { it.parentId == vehicleId }
    fun customCategories(): List<String> = custom.names
    fun correctionList(): List<Correction> = corrections.items
    fun findVehicleByHashes(dff: String, txd: String?): PieceMeta? = vehicles().firstOrNull { it.dffHash == dff && it.txdHash == txd }

    // ------------------------------------------------------------------ persistencia atómica

    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "No se pudo guardar ${f.name}" } }
    }

    private fun saveIndex() = synchronized(lock) {
        atomicWrite(File(root, "index.json"), json.encodeToString(LibraryIndex(1, index.values.toList())).toByteArray())
    }

    private fun saveSmall() {
        atomicWrite(File(root, "corrections.json"), json.encodeToString(corrections).toByteArray())
        atomicWrite(File(root, "categories.json"), json.encodeToString(custom).toByteArray())
    }

    // ------------------------------------------------------------------ blobs y texturas (contenido direccionado por hash)

    fun putBlob(raw: ByteArray): String {
        val h = Hashing.sha1(raw)
        val f = File(blobDir, "$h.gz")
        if (!f.exists()) atomicWrite(f, BlobCodec.compress(raw))
        return h
    }

    fun putTexture(t: TextureImage): String {
        val h = BlobCodec.textureHash(t)
        val f = File(texDir, "$h.tex")
        if (!f.exists()) atomicWrite(f, BlobCodec.encodeTexture(t))
        return h
    }

    private val texCache = java.util.LinkedHashMap<String, TextureImage>(16, 0.75f, true)

    fun texture(hash: String): TextureImage? = synchronized(texCache) {
        texCache[hash] ?: File(texDir, "$hash.tex").takeIf { it.exists() }?.let { BlobCodec.decodeTexture(it.readBytes()) }?.also {
            texCache[hash] = it; if (texCache.size > 24) texCache.remove(texCache.keys.first())
        }
    }

    fun hasBlob(h: String) = File(blobDir, "$h.gz").exists()
    fun blobBytes(h: String): ByteArray = BlobCodec.decompress(File(blobDir, "$h.gz").readBytes())

    // ------------------------------------------------------------------ alta de vehículos y componentes

    private fun round2(f: Float) = Math.round(f * 100f) / 100f

    private fun sideOf(p: MeshPart, center: FloatArray): String? = p.wheelSlot?.let { if (it.short.endsWith("L")) "L" else "R" }
        ?: p.frameName?.lowercase()?.let { n ->
            when {
                Regex("(^|_)(lf|lb|lr|lm|l)(_|$)").containsMatchIn(n) -> "L"
                Regex("(^|_)(rf|rb|rr|rm|r)(_|$)").containsMatchIn(n) -> "R"
                else -> null
            }
        } ?: if (kotlin.math.abs(center[0]) > 0.25f) (if (center[0] > 0) "L" else "R") else null // visor: +X = izquierda

    /** Guarda un vehículo (y, opcionalmente, piezas relacionadas ya construidas) y devuelve qué se añadió. */
    fun addVehicle(model: VehicleModel, info: SourceInfo, related: List<Pair<String, VehicleModel>> = emptyList()): AddResult {
        findVehicleByHashes(info.dffHash, info.txdHash)?.let { return AddResult(null, it.id, 0, 0, 0) }
        val vid = "v_" + Hashing.sha1(info.dffHash + (info.txdHash ?: "")).take(12)
        val now = System.currentTimeMillis()

        fun storePart(p: MeshPart): StoredPart {
            val raw = BlobCodec.encode(p) { putTexture(it) }
            val h = putBlob(raw)
            val bb = ModelAssembler.partBounds(p)
            return StoredPart(p.name, p.category.name, p.wheelSlot?.name, p.frameName, p.transform.toList(), h, p.defaultVisible, p.variantNote, p.recognized, p.extraSource,
                if (bb.isValid) bb.min.toList() + bb.max.toList() else emptyList())
        }
        val keep = model.parts.filter { it.defaultVisible }
        val stored = keep.map(::storePart)
        atomicWrite(File(vehDir, "$vid.json"), json.encodeToString(VehicleManifest(vid, model.name, stored, model.warnings.take(30), info.dffFileName, info.txdFileName)).toByteArray())

        val feat = FeatureExtractor.extract(model, listOf(info.dffFileName.substringBeforeLast('.'), info.origin.substringBeforeLast('/').substringAfterLast('/')))
        val types = VehicleClassifier.classify(feat, corrections.items)
        val shell = ModelAssembler.robustBounds(keep.filter { it.category != PartCategory.WHEEL && it.category != PartCategory.MIRROR }) ?: model.fullBounds()
        val s = shell?.size ?: floatArrayOf(0f, 0f, 0f); val c = shell?.center ?: floatArrayOf(0f, 0f, 0f)
        val vehicleMeta = PieceMeta(
            id = vid, name = model.name, kind = "VEHICLE", category = PartCategory.BODY.name, source = info.source, origin = info.origin,
            dffHash = info.dffHash, txdHash = info.txdHash, dims = Dims(round2(s[0]), round2(s[1]), round2(s[2])), center = c.toList(),
            triangles = keep.sumOf { p -> p.subMeshes.sumOf { it.triangleCount } }, vertices = keep.flatMap { p -> p.subMeshes.map { it.geometry } }.distinctBy { it.id }.sumOf { it.vertexCount },
            materials = model.materials.map { it.name }.distinct(), textures = model.materials.mapNotNull { it.textureName }.distinct(),
            createdAt = now, geoHash = Hashing.sha1(stored.joinToString { it.blob }), blobs = stored.map { it.blob }.distinct(),
            types = types.labels, wheelCount = keep.count { it.category == PartCategory.WHEEL },
            featureVec = feat.vector().toList(), nameKey = feat.nameKey,
            anchors = autoAnchors(model, keep),
        )
        synchronized(lock) { index[vid] = vehicleMeta }

        var created = 0; var merged = 0; var similar = 0
        fun addComponents(m: VehicleModel, sourceTag: String) {
            val byBlob = LinkedHashMap<String, MutableList<Pair<MeshPart, StoredPart>>>()
            for (p in m.parts.filter { it.defaultVisible && p0(it) }) {
                val sp = if (m === model) stored[keep.indexOf(p)] else storePart(p)
                byBlob.getOrPut(sp.blob) { ArrayList() } += p to sp
            }
            for ((blob, list) in byBlob) {
                val (p, sp) = list[0]
                val cid = "c_" + blob.take(12)
                val b = ModelAssembler.partBounds(p)
                val sz = b.size; val ct = b.center
                val existing = synchronized(lock) { index[cid] }
                if (existing != null) {
                    if (existing.source != info.source || existing.origin != info.origin) {
                        val also = (existing.alsoFoundIn + "${info.source}: ${info.name}").distinct().take(20)
                        synchronized(lock) { index[cid] = existing.copy(alsoFoundIn = also) }
                    }
                    merged++; continue
                }
                val cat = (PartCategory.fromName(sp.category) ?: PartCategory.OTHER)
                val dims = Dims(round2(sz[0]), round2(sz[1]), round2(sz[2]))
                val sim = synchronized(lock) { index.values.firstOrNull { it.kind == "COMPONENT" && it.category == cat.name && closeDims(it.dims, dims) && it.triangles == p.subMeshes.sumOf { s2 -> s2.triangleCount } } }
                if (sim != null) similar++
                val meta = PieceMeta(
                    id = cid, name = PartNaming.friendly(p).let { if (it == p.name) p.name else "$it (${p.name})" }, kind = "COMPONENT", category = cat.name, parentId = vid,
                    source = info.source, origin = info.origin + if (sourceTag.isNotEmpty()) " [$sourceTag]" else "", dffHash = info.dffHash, txdHash = info.txdHash, dims = dims,
                    center = ct.toList(), triangles = p.subMeshes.sumOf { it.triangleCount }, vertices = p.subMeshes.map { it.geometry }.distinctBy { it.id }.sumOf { it.vertexCount },
                    materials = p.subMeshes.map { it.material.name }.distinct(), textures = p.subMeshes.mapNotNull { it.material.textureName }.distinct(),
                    createdAt = now, geoHash = blob, blobs = listOf(blob), wheelCount = if (cat == PartCategory.WHEEL) list.size else 0,
                    side = sideOf(p, ct), longitudinal = p.wheelSlot?.let { if (it.short.startsWith("F")) "FRONT" else if (it.short.startsWith("R")) "REAR" else null }
                        ?: (if (kotlin.math.abs(ct[2] - c[2]) > 0.4f) (if (ct[2] > c[2]) "FRONT" else "REAR") else null),
                    transform = p.transform.toList(), similarTo = sim?.id, tags = listOfNotNull(sourceTag.ifEmpty { null }),
                )
                synchronized(lock) { index[cid] = meta }
                created++
            }
        }
        addComponents(model, "")
        for ((rname, rm) in related) addComponents(rm, "tuning: $rname")
        saveIndex()
        return AddResult(vid, null, created, merged, similar)
    }

    private fun p0(p: MeshPart) = p.subMeshes.sumOf { it.triangleCount } >= 8

    private fun closeDims(a: Dims, b: Dims): Boolean {
        fun c(x: Float, y: Float) = kotlin.math.abs(x - y) <= 0.015f * maxOf(x, y, 0.05f) + 0.005f
        return c(a.w, b.w) && c(a.h, b.h) && c(a.l, b.l)
    }

    private fun autoAnchors(model: VehicleModel, parts: List<MeshPart>): List<Anchor> {
        val out = ArrayList<Anchor>()
        for (w in parts.filter { it.category == PartCategory.WHEEL }) {
            val c = model.partBounds(w).center
            out += Anchor("rueda_${w.wheelSlot?.short?.lowercase() ?: w.name}", c.toList(), listOf(if (c[0] > 0) 1f else -1f, 0f, 0f))
        }
        return out
    }

    // ------------------------------------------------------------------ carga bajo demanda

    private fun manifest(id: String): VehicleManifest? = File(vehDir, "$id.json").takeIf { it.exists() }?.let { json.decodeFromString<VehicleManifest>(it.readText()) }

    private class Loader(val store: LibraryStore) {
        var nextGeo = 0; var nextPart = 0; var nextSub = 0
        val cache = HashMap<String, BlobCodec.Decoded>()
        fun part(sp: StoredPart): MeshPart {
            val d = cache.getOrPut(sp.blob) { BlobCodec.decode(store.blobBytes(sp.blob), { nextGeo++ }) { store.texture(it) } }
            val pid = nextPart++
            val subs = d.subs.map { SubMesh(nextSub++, pid, d.geometries[it.geometryIndex], it.indices, it.material) }
            return MeshPart(pid, sp.name, PartCategory.fromName(sp.category) ?: PartCategory.OTHER, sp.wheelSlot?.let { WheelSlot.valueOf(it) },
                sp.frameName, sp.transform.toFloatArray(), subs, sp.defaultVisible, sp.variantNote, sp.recognized, sp.extraSource)
        }
    }

    fun loadVehicle(id: String): VehicleModel {
        val meta = get(id) ?: throw IllegalArgumentException("Vehículo no encontrado: $id")
        val man = manifest(id) ?: throw IllegalStateException("Falta el manifiesto de ${meta.name}")
        val l = Loader(this)
        val parts = man.parts.map { l.part(it) }
        return ModelAssembler.assemble(meta.name, "LIBRARY", parts, man.warnings, man.dffFileName, man.txdFileName)
    }

    /** Un componente en su posición original (espacio de su vehículo de origen). */
    fun loadComponent(id: String): VehicleModel {
        val meta = get(id) ?: throw IllegalArgumentException("Pieza no encontrada: $id")
        val l = Loader(this)
        val sp = StoredPart(meta.name, meta.category, null, null, meta.transform.ifEmpty { Mat4.identity().toList() }, meta.blobs.first(), true)
        return ModelAssembler.assemble(meta.name, "LIBRARY", listOf(l.part(sp)))
    }

    /** Parte lista para insertar en un proyecto (ids que asigna el proyecto). */
    fun loadComponentPart(id: String, nextGeo: () -> Int, nextPart: () -> Int, nextSub: () -> Int): MeshPart {
        val meta = get(id) ?: throw IllegalArgumentException("Pieza no encontrada: $id")
        val d = BlobCodec.decode(blobBytes(meta.blobs.first()), nextGeo) { texture(it) }
        val pid = nextPart()
        val subs = d.subs.map { SubMesh(nextSub(), pid, d.geometries[it.geometryIndex], it.indices, it.material) }
        return MeshPart(pid, meta.name, PartCategory.fromName(meta.category) ?: PartCategory.OTHER, null, null,
            meta.transform.ifEmpty { Mat4.identity().toList() }.toFloatArray(), subs, true, null, true, meta.id)
    }

    // ------------------------------------------------------------------ miniaturas (bajo demanda)

    fun thumbnailPng(id: String, size: Int = 160): ByteArray? {
        val f = File(thumbDir, "$id.png")
        if (f.exists()) return f.readBytes()
        val meta = get(id) ?: return null
        val model = try { if (meta.kind == "VEHICLE") loadVehicle(id) else loadComponent(id) } catch (e: Exception) { return null }
        val rgba = ThumbRenderer.render(model, null, size, if (meta.kind == "VEHICLE") 35f else 30f, 18f)
        val png = PngEncoder.encode(size, size, rgba)
        atomicWrite(f, png)
        return png
    }

    // ------------------------------------------------------------------ edición de metadatos

    fun update(id: String, f: (PieceMeta) -> PieceMeta) { synchronized(lock) { index[id]?.let { index[id] = f(it) } }; saveIndex() }
    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun setFavorite(id: String, fav: Boolean) = update(id) { it.copy(favorite = fav) }
    fun setNotes(id: String, notes: String) = update(id) { it.copy(notes = notes) }
    fun setCategory(id: String, category: String) = update(id) { it.copy(category = category) }

    fun addCustomCategory(name: String) { val n = name.trim(); if (n.isNotEmpty() && n !in custom.names && PartCategory.fromName(n) == null) { custom = CustomCategories(custom.names + n); saveSmall() } }
    fun removeCustomCategory(name: String) { custom = CustomCategories(custom.names - name); saveSmall()
        synchronized(lock) { index.replaceAll { _, p -> if (p.category == name) p.copy(category = PartCategory.OTHER.name) else p } }; saveIndex() }

    /** El usuario corrige el tipo de un vehículo: se guarda y se aprende para clasificaciones futuras parecidas. */
    fun correctVehicleTypes(id: String, labels: List<String>) {
        val m = get(id) ?: return
        update(id) { it.copy(types = labels.map { l -> TypeLabel(l, 1f, "corrección del usuario") }, typesCorrected = true) }
        if (m.featureVec.isNotEmpty()) {
            corrections = Corrections(corrections.items.filter { it.name != m.nameKey } + Correction(m.featureVec, labels, m.nameKey))
            saveSmall()
        }
    }

    fun addAnchor(id: String, a: Anchor) = update(id) { it.copy(anchors = it.anchors.filter { x -> x.name != a.name } + a) }

    // ------------------------------------------------------------------ borrado seguro (solo archivos de la biblioteca)

    /** Quita piezas del catálogo. Si se borra un vehículo con [cascade], también sus componentes. Nunca toca los originales. */
    fun delete(ids: Set<String>, cascade: Boolean = false): Int {
        var n = 0
        val toRemove = HashSet(ids)
        if (cascade) synchronized(lock) { index.values.filter { it.parentId in ids }.forEach { toRemove += it.id } }
        synchronized(lock) { for (id in toRemove) if (index.remove(id) != null) { n++; File(vehDir, "$id.json").delete(); File(thumbDir, "$id.png").delete() } }
        saveIndex(); gc()
        return n
    }

    /** Elimina blobs/texturas ya sin referencias (dentro de la biblioteca). */
    fun gc(): Int {
        val usedBlobs = HashSet<String>(); val usedTex = HashSet<String>()
        synchronized(lock) { index.values.forEach { usedBlobs += it.blobs } }
        vehDir.listFiles()?.forEach { f -> runCatching { json.decodeFromString<VehicleManifest>(f.readText()).parts.forEach { usedBlobs += it.blob } } }
        // los proyectos también referencian blobs y texturas (por hash de 40 hex): nunca se recolectan
        val hex = Regex("[0-9a-f]{40}")
        projectsDir.listFiles()?.forEach { f -> runCatching { hex.findAll(f.readText()).forEach { usedBlobs += it.value; usedTex += it.value } } }
        var removed = 0
        blobDir.listFiles()?.forEach { f -> if (f.name.endsWith(".gz") && f.name.removeSuffix(".gz") !in usedBlobs) { f.delete(); removed++ } }
        // las referencias a texturas están dentro de los blobs: se recolectan decodificando solo cabeceras de material
        for (h in usedBlobs) if (hasBlob(h)) runCatching { BlobCodec.decode(blobBytes(h), { 0 }) { th -> usedTex += th; null } }
        texDir.listFiles()?.forEach { f -> if (f.name.removeSuffix(".tex") !in usedTex) { f.delete(); removed++ } }
        return removed
    }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------------ copias de seguridad

    fun exportBackup(out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun add(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            saveIndex()
            for (n in listOf("index.json", "corrections.json", "categories.json")) File(root, n).takeIf { it.exists() }?.let { add(n, it.readBytes()) }
            for (dir in listOf(vehDir, blobDir, texDir)) dir.listFiles()?.forEach { add("${dir.name}/${it.name}", it.readBytes()) }
        }
    }

    /** Combina una copia de seguridad: añade lo que falta, no sobrescribe lo existente. */
    fun importBackup(input: InputStream): BackupSummary {
        var added = 0; var skipped = 0; var blobs = 0
        var idx: LibraryIndex? = null
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (name.contains("..") || name.startsWith("/")) continue
                val data = z.readBytes()
                when {
                    name == "index.json" -> idx = json.decodeFromString(String(data))
                    name == "corrections.json" -> { val c = json.decodeFromString<Corrections>(String(data)); corrections = Corrections((corrections.items + c.items).distinctBy { it.name + it.features }) }
                    name == "categories.json" -> { val c = json.decodeFromString<CustomCategories>(String(data)); custom = CustomCategories((custom.names + c.names).distinct()) }
                    name.startsWith("blobs/") || name.startsWith("tex/") || name.startsWith("vehicles/") -> {
                        val f = File(root, name)
                        if (!f.exists()) { f.parentFile.mkdirs(); atomicWrite(f, data); if (!name.startsWith("vehicles/")) blobs++ }
                    }
                }
            }
        }
        idx?.pieces?.forEach { p -> synchronized(lock) { if (p.id in index) skipped++ else { index[p.id] = p; added++ } } }
        saveIndex(); saveSmall()
        return BackupSummary(added, skipped, blobs)
    }
}
