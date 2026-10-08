package com.webmaletin.gtaviewer.gta

import com.webmaletin.gtaviewer.dff.*
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.txd.GtaSaTxdParser
import com.webmaletin.gtaviewer.txd.TextureImage
import com.webmaletin.gtaviewer.txd.TxdDocument
import com.webmaletin.gtaviewer.txd.TxdParser
import com.webmaletin.gtaviewer.zip.ByteSource
import kotlin.math.abs
import kotlin.math.sqrt

class VehicleLoadException(message: String, val diagnostics: String) : Exception(message)

/**
 * Convierte DFF+TXD de GTA SA en un [VehicleModel] editable: conserva la estructura interna (una parte por
 * atomic, un sub-mesh por material), resuelve texturas por NOMBRE (material -> textura -> TXD) e instancia
 * las ruedas a partir de la plantilla "wheel" y los dummies wheel_*_dummy como hace el motor del juego.
 */
class GtaSaVehicleBuilder(
    private val dffParser: DffParser = GtaSaDffParser(),
    private val txdParser: TxdParser = GtaSaTxdParser(),
) {
    private class Ids(var part: Int = 0, var sub: Int = 0, var geo: Int = 0)

    private class Acc(
        val textures: Map<String, TextureImage>,
        val found: LinkedHashSet<String> = LinkedHashSet(),
        val missing: LinkedHashSet<String> = LinkedHashSet(),
        val materials: LinkedHashMap<String, MaterialInfo> = LinkedHashMap(),
        val warnings: MutableList<String> = ArrayList(),
        val names: HashMap<String, Int> = HashMap(),
    )

    fun build(displayName: String, dff: ByteSource, txd: ByteSource?): VehicleModel {
        val dffBytes = try { dff.readBytes() } catch (e: Exception) {
            throw VehicleLoadException("No se pudo leer el DFF: ${e.message}", "DFF ${dff.displayName}: ${e.message}")
        }
        val doc = try { dffParser.parse(dffBytes) } catch (e: Exception) {
            throw VehicleLoadException("DFF inválido: ${e.message}", "DFF ${dff.displayName} (${dffBytes.size} bytes) no es un RenderWare Clump válido: ${e.message}")
        }
        var txdDoc: TxdDocument? = null
        var txdError: String? = null
        var txdSize = 0L
        if (txd != null) {
            try {
                val tb = txd.readBytes(); txdSize = tb.size.toLong()
                txdDoc = txdParser.parse(tb)
            } catch (e: Exception) {
                txdError = "TXD ilegible: ${e.message}"
            }
        }
        val textures = LinkedHashMap<String, TextureImage>()
        val textureErrors = ArrayList<String>()
        txdDoc?.textures?.forEach { t ->
            if (t.image != null) textures[TxdDocument.norm(t.name)] = t.image
            else textureErrors += "${t.name}: ${t.error}"
        }

        val acc = Acc(textures)
        val ids = Ids()
        val parts = buildParts(doc, acc, ids, extraSource = null)
        txdError?.let { acc.warnings += it }
        if (txd == null) acc.warnings += "Sin TXD: se muestran los colores de los materiales"
        acc.warnings += doc.issues.map { "DFF: $it" }
        txdDoc?.issues?.forEach { acc.warnings += "TXD: $it" }

        val notes = ArrayList<String>()
        if (doc.geometries.any { it?.hasSkin == true }) notes += "El DFF contiene datos de skin (esqueleto): no se exportan, la malla se usa sin deformación"
        if (doc.geometries.any { it?.isNative == true }) notes += "Hay geometrías nativas (PS2/Xbox) que no se pueden leer"
        if (doc.geometries.any { g -> g?.colors != null }) notes += "Colores de vértice leídos; no se aplican en visor ni exportación"

        val diag = ModelDiagnostics(
            dffFileName = dff.displayName, txdFileName = txd?.displayName, dffSize = dffBytes.size.toLong(), txdSize = txdSize,
            dffParser = dffParser.name, txdParser = if (txd != null) txdParser.name else null,
            rwVersion = RwVersionText.of(doc.rwVersion), dffValid = true, txdValid = txd != null && txdError == null,
            geometryCount = doc.geometries.count { it != null }, atomicCount = doc.atomics.size, frameCount = doc.frames.size,
            materialCount = acc.materials.size, texturesInTxd = txdDoc?.textures?.size ?: 0,
            texturesFound = acc.found.toList(), texturesMissing = acc.missing.toList(), textureErrors = textureErrors,
            chunkIssues = doc.issues + (txdDoc?.issues ?: emptyList()) + listOfNotNull(txdError), notes = notes,
        )
        val validation = ModelValidator.validate(parts) { p -> partBoundsOf(p) }
        return VehicleModel(displayName, "GTA_SA", parts, acc.materials.values.toList(), textures, diag, validation, acc.warnings)
    }

    /** Añade un DFF de pieza relacionada a un modelo ya cargado (nuevo modelo; el original no se modifica). */
    fun addPart(model: VehicleModel, label: String, dff: ByteSource, txd: ByteSource?): VehicleModel {
        val doc = dffParser.parse(dff.readBytes())
        val textures = LinkedHashMap(model.textures)
        txd?.let {
            try {
                txdParser.parse(it.readBytes()).textures.forEach { t -> t.image?.let { img -> textures[TxdDocument.norm(t.name)] = img } }
            } catch (e: Exception) { /* se avisa abajo con las texturas faltantes */ }
        }
        val acc = Acc(textures)
        model.materials.forEach { acc.materials[it.key] = it }
        val ids = Ids(
            part = (model.parts.maxOfOrNull { it.id } ?: -1) + 1,
            sub = (model.subMeshes.maxOfOrNull { it.id } ?: -1) + 1,
            geo = (model.subMeshes.maxOfOrNull { it.geometry.id } ?: -1) + 1,
        )
        model.parts.forEach { acc.names[it.name.lowercase()] = 1 }
        val extra = buildParts(doc, acc, ids, extraSource = label)
        val all = model.parts + extra
        val warnings = model.warnings + acc.warnings + doc.issues.map { "DFF ($label): $it" }
        return VehicleModel(model.name, model.moduleId, all, acc.materials.values.toList(), textures, model.diagnostics,
            ModelValidator.validate(all) { p -> partBoundsOf(p) }, warnings)
    }

    // ------------------------------------------------------------------------------------------------

    private fun partBoundsOf(p: MeshPart): Bounds {
        val b = Bounds.empty()
        val m = Mat4.multiply(Mat4.GTA_TO_VIEW, p.transform)
        val t = FloatArray(3)
        for (sm in p.subMeshes) {
            val pos = sm.geometry.positions
            for (i in sm.indices) {
                Mat4.transformPoint(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], t)
                b.expand(t[0], t[1], t[2])
            }
        }
        return b
    }

    private fun frameWorldMatrices(doc: DffDocument): List<FloatArray> {
        val n = doc.frames.size
        val world = arrayOfNulls<FloatArray>(n)
        fun compute(i: Int, depth: Int): FloatArray {
            world[i]?.let { return it }
            val f = doc.frames[i]
            val local = Mat4.fromFrame(f.rotation, f.position)
            val res = if (f.parent in 0 until n && f.parent != i && depth < n) Mat4.multiply(compute(f.parent, depth + 1), local) else local
            world[i] = res
            return res
        }
        return List(n) { compute(it, 0) }
    }

    private fun buildParts(doc: DffDocument, acc: Acc, ids: Ids, extraSource: String?): List<MeshPart> {
        val world = frameWorldMatrices(doc)
        val geometryCache = HashMap<Int, GeometryData?>()
        val subCache = HashMap<Int, List<Triple<GeometryData, MaterialInfo, IntArray>>>()

        fun geometryData(g: DffGeometry): GeometryData? = geometryCache.getOrPut(g.index) { makeGeometry(g, ids, acc) }
        fun subsFor(g: DffGeometry): List<Triple<GeometryData, MaterialInfo, IntArray>> = subCache.getOrPut(g.index) {
            val gd = geometryData(g) ?: return@getOrPut emptyList()
            val flip = shouldFlip(g, gd)
            if (flip) acc.warnings += "Geometría ${g.index}: winding invertido respecto a las normales; se corrigió"
            splitByMaterial(g, gd, acc).map { (matIdx, tris) ->
                val idx = if (flip) flipWinding(tris) else tris
                Triple(gd, materialFor(g, matIdx, acc), idx)
            }
        }

        val anyRendered = doc.atomics.any { it.isRendered }
        val framesWithAtomic = doc.atomics.map { it.frameIndex }.toSet()
        val parts = ArrayList<MeshPart>()
        var meshCounter = 0

        fun uniqueName(base: String): String {
            val key = base.lowercase()
            val n = acc.names.merge(key, 1, Int::plus)!!
            return if (n > 1) "$base ($n)" else base
        }

        // --- Plantilla de rueda e instancias
        val dummies = doc.frames.filter { f ->
            val nm = f.name?.lowercase() ?: return@filter false
            Regex("^wheel_(lf|rf|lb|rb|lr|rr|lm|rm)(_dummy)?$").matches(nm)
        }
        val templateAtomic = doc.atomics.firstOrNull { a -> doc.frames.getOrNull(a.frameIndex)?.name?.lowercase().let { it == "wheel" || it == "wheel_ok" } }
        val emptyDummies = dummies.filter { it.index !in framesWithAtomic }

        if (templateAtomic != null && emptyDummies.isNotEmpty()) {
            val g = doc.geometries.getOrNull(templateAtomic.geometryIndex)
            val subs = g?.let { subsFor(it) }.orEmpty()
            if (subs.isNotEmpty()) {
                for (d in emptyDummies.sortedBy { PartClassifier.wheelSlot(it.name!!.lowercase())?.ordinal ?: 99 }) {
                    val slot = PartClassifier.wheelSlot(d.name!!.lowercase())
                    var m = world[d.index]
                    val isRight = slot == WheelSlot.FR || slot == WheelSlot.RR || slot == WheelSlot.MR
                    if (isRight && m[0] > 0.5f) m = Mat4.multiply(m, Mat4.rotZ180()) // la rueda se modela una vez (lado izquierdo)
                    val pid = ids.part++
                    val name = uniqueName(slot?.let { "Wheel ${it.short}" } ?: d.name)
                    parts += MeshPart(
                        pid, name, PartCategory.WHEEL, slot, d.name, m,
                        subs.map { SubMesh(ids.sub++, pid, it.first, it.third, it.second) },
                        defaultVisible = true, variantNote = null, recognized = true, extraSource = extraSource,
                    )
                }
            }
        }

        // --- Resto de atomics
        for (a in doc.atomics) {
            if (a === templateAtomic && emptyDummies.isNotEmpty() && parts.any { it.category == PartCategory.WHEEL }) continue
            val g = doc.geometries.getOrNull(a.geometryIndex)
            if (g == null) { acc.warnings += "Atomic ${a.index} referencia la geometría ${a.geometryIndex}, que no existe o es ilegible"; continue }
            val subs = subsFor(g)
            if (subs.isEmpty()) { acc.warnings += "Atomic ${a.index}: la geometría ${a.geometryIndex} no tiene triángulos válidos"; continue }
            val frame = doc.frames.getOrNull(a.frameIndex)
            if (frame == null) acc.warnings += "Atomic ${a.index} referencia el frame ${a.frameIndex}, que no existe; se usa identidad"
            meshCounter++
            val cls = PartClassifier.classify(frame?.name)
            val name = uniqueName(frame?.name ?: "Mesh %03d".format(meshCounter))
            val pid = ids.part++
            val visible = cls.variant == null && (a.isRendered || !anyRendered)
            val note = cls.variant ?: if (!visible) "no renderizado (flag del atomic)" else null
            parts += MeshPart(
                pid, name, cls.category, cls.slot, frame?.name, frame?.let { world[it.index] } ?: Mat4.identity(),
                subs.map { SubMesh(ids.sub++, pid, it.first, it.third, it.second) },
                visible, note, cls.recognized, extraSource,
            )
        }
        return parts
    }

    // ----------------------------------------------------------------------------- geometría

    private fun makeGeometry(g: DffGeometry, ids: Ids, acc: Acc): GeometryData? {
        if (g.isNative || g.vertexCount == 0 || g.positions.size < g.vertexCount * 3) return null
        val pos = g.positions
        var hasN = g.normals != null && g.normals.size >= g.vertexCount * 3
        val normals = if (hasN) g.normals!!.copyOf() else FloatArray(g.vertexCount * 3)
        if (!hasN) computeSmoothNormals(pos, normals, g)
        val uvs = FloatArray(g.vertexCount * 2)
        val set0 = g.uvSets.firstOrNull()
        if (set0 != null && set0.size >= uvs.size) System.arraycopy(set0, 0, uvs, 0, uvs.size)
        return GeometryData(ids.geo++, g.vertexCount, pos, normals, uvs, hasN, set0 != null, g.colors != null)
    }

    private fun computeSmoothNormals(pos: FloatArray, out: FloatArray, g: DffGeometry) {
        val tris = trianglesForNormals(g)
        var i = 0
        while (i + 2 < tris.size) {
            val a = tris[i]; val b = tris[i + 1]; val c = tris[i + 2]; i += 3
            if (a >= g.vertexCount || b >= g.vertexCount || c >= g.vertexCount) continue
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { out[v * 3] += nx; out[v * 3 + 1] += ny; out[v * 3 + 2] += nz }
        }
        for (v in 0 until g.vertexCount) {
            val l = sqrt(out[v * 3] * out[v * 3] + out[v * 3 + 1] * out[v * 3 + 1] + out[v * 3 + 2] * out[v * 3 + 2])
            if (l > 1e-12f) { out[v * 3] /= l; out[v * 3 + 1] /= l; out[v * 3 + 2] /= l } else out[v * 3 + 2] = 1f
        }
    }

    private fun trianglesForNormals(g: DffGeometry): IntArray {
        val tmp = Acc(emptyMap())
        val groups = triangleGroups(g, tmp)
        return groups.values.fold(IntArray(0)) { a, b -> a + b }
    }

    /** Índices de triángulo agrupados por índice de material (usa BinMesh en geometrías tri-strip). */
    private fun triangleGroups(g: DffGeometry, acc: Acc): Map<Int, IntArray> {
        val useBin = g.binMesh != null && g.binMesh.isNotEmpty() && (g.isTriStrip || g.triangles.isEmpty())
        val out = LinkedHashMap<Int, MutableList<Int>>()
        var dropped = 0
        fun add(mat: Int, a: Int, b: Int, c: Int) {
            if (a < 0 || b < 0 || c < 0 || a >= g.vertexCount || b >= g.vertexCount || c >= g.vertexCount || a == b || b == c || a == c) { dropped++; return }
            val l = out.getOrPut(mat) { ArrayList() }; l.add(a); l.add(b); l.add(c)
        }
        if (useBin) {
            for (grp in g.binMesh!!) {
                val ix = grp.indices
                if (g.binMeshIsStrip) {
                    for (i in 2 until ix.size) {
                        if (i % 2 == 0) add(grp.materialIndex, ix[i - 2], ix[i - 1], ix[i]) else add(grp.materialIndex, ix[i - 1], ix[i - 2], ix[i])
                    }
                } else {
                    var i = 0
                    while (i + 2 < ix.size) { add(grp.materialIndex, ix[i], ix[i + 1], ix[i + 2]); i += 3 }
                }
            }
        } else {
            for (t in 0 until g.triangles.size / 3) add(g.triangleMaterials[t], g.triangles[t * 3], g.triangles[t * 3 + 1], g.triangles[t * 3 + 2])
        }
        if (dropped > 0) acc.warnings += "Geometría ${g.index}: $dropped triángulo(s) degenerados o con índices inválidos descartados"
        return out.mapValues { it.value.toIntArray() }
    }

    private fun splitByMaterial(g: DffGeometry, gd: GeometryData, acc: Acc): Map<Int, IntArray> =
        triangleGroups(g, acc).toSortedMap()

    /** Si la mayoría de triángulos tienen la normal geométrica opuesta a la normal de vértice, el winding está invertido. */
    private fun shouldFlip(g: DffGeometry, gd: GeometryData): Boolean {
        if (!gd.hasOriginalNormals) return false
        val tris = trianglesForNormals(g)
        val n = tris.size / 3
        if (n == 0) return false
        val step = maxOf(1, n / 400)
        var pos = 0; var neg = 0
        var t = 0
        val p = gd.positions; val nn = gd.normals
        while (t < n) {
            val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
            val gx = uy * vz - uz * vy; val gy = uz * vx - ux * vz; val gz = ux * vy - uy * vx
            val d = gx * (nn[a * 3] + nn[b * 3] + nn[c * 3]) + gy * (nn[a * 3 + 1] + nn[b * 3 + 1] + nn[c * 3 + 1]) + gz * (nn[a * 3 + 2] + nn[b * 3 + 2] + nn[c * 3 + 2])
            if (d > 1e-9f) pos++ else if (d < -1e-9f) neg++
            t += step
        }
        return neg > pos * 2
    }

    private fun flipWinding(idx: IntArray): IntArray {
        val r = idx.copyOf()
        var i = 0
        while (i + 2 < r.size) { val t = r[i + 1]; r[i + 1] = r[i + 2]; r[i + 2] = t; i += 3 }
        return r
    }

    private fun materialFor(g: DffGeometry, matIdx: Int, acc: Acc): MaterialInfo {
        val dm = g.materials.getOrNull(matIdx)
        if (dm == null) {
            acc.warnings += "Geometría ${g.index}: usa el material $matIdx pero solo hay ${g.materials.size}; se usa un material por defecto"
            return acc.materials.getOrPut("default") { MaterialInfo("default", "Material por defecto", 200, 200, 200, 255, null, null, 0, false) }
        }
        val texName = dm.texture?.name
        val key = "${texName?.lowercase() ?: ""}|${dm.r},${dm.g},${dm.b},${dm.a}"
        return acc.materials.getOrPut(key) {
            val tex = texName?.let { acc.textures[TxdDocument.norm(it)] }
            if (texName != null) {
                if (tex != null) acc.found += texName
                else { acc.missing += texName; acc.warnings += "Textura '$texName' no encontrada en el TXD; se usa el color del material" }
            }
            val slot = when {
                dm.r == 60 && dm.g == 255 && dm.b == 0 -> 1
                dm.r == 255 && dm.g == 0 && dm.b == 175 -> 2
                else -> 0
            }
            val nm = texName ?: "Color %02X%02X%02X".format(dm.r, dm.g, dm.b)
            MaterialInfo(key, nm, dm.r, dm.g, dm.b, dm.a, texName, tex, slot, dm.hasEnvMap)
        }
    }
}

object RwVersionText {
    fun of(v: Int): String = com.webmaletin.gtaviewer.renderware.RwVersion.format(v)
}
