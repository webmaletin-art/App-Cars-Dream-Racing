package com.webmaletin.gtaviewer.glb

import com.webmaletin.gtaviewer.export.ExportScene
import com.webmaletin.gtaviewer.model.*
import com.webmaletin.gtaviewer.texture.PngDecoder
import com.webmaletin.gtaviewer.txd.TextureImage
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbPrimitive(val positions: FloatArray, val normals: FloatArray?, val uvs: FloatArray?, val indices: IntArray, val material: Int)
class GlbNode(val name: String, val world: FloatArray, val primitives: List<GlbPrimitive>)
class GlbMat(val name: String, val color: FloatArray, val metallic: Float, val roughness: Float, val alphaMode: String, val texture: TextureImage?)

class GlbData(val nodes: List<GlbNode>, val materials: List<GlbMat>, val images: List<TextureImage?>, val generator: String) {
    val meshNodes get() = nodes.filter { it.primitives.isNotEmpty() }
    val triangles get() = meshNodes.sumOf { n -> n.primitives.sumOf { it.indices.size / 3 } }
}

/** Lector GLB propio: valida lo que exportamos (re-importación) y permite abrir GLB externos en el visor. */
object GlbImporter {
    fun read(bytes: ByteArray): GlbData {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 20 && bb.int == 0x46546C67) { "No es un GLB (magic incorrecto)" }
        require(bb.int == 2) { "Versión de GLB no soportada" }
        val total = bb.int; require(total <= bytes.size) { "GLB truncado (longitud declarada $total > ${bytes.size})" }
        var json: JsonObject? = null; var bin: ByteArray? = null
        while (bb.position() + 8 <= total) {
            val len = bb.int; val type = bb.int
            val start = bb.position()
            if (type == 0x4E4F534A) json = Json.parseToJsonElement(String(bytes, start, len, Charsets.UTF_8)).jsonObject
            else if (type == 0x004E4942 && bin == null) bin = bytes.copyOfRange(start, start + len)
            bb.position(start + len)
        }
        val j = json ?: throw IllegalArgumentException("GLB sin chunk JSON")
        val b = bin ?: ByteArray(0)
        val views = j["bufferViews"]?.jsonArray ?: JsonArray(emptyList())
        val accs = j["accessors"]?.jsonArray ?: JsonArray(emptyList())

        fun view(i: Int) = views[i].jsonObject
        fun accessor(i: Int): Pair<FloatArray, Int> { // (datos como float, nº de componentes)
            val a = accs[i].jsonObject
            val count = a["count"]!!.jsonPrimitive.int; val type = a["type"]!!.jsonPrimitive.content; val comp = a["componentType"]!!.jsonPrimitive.int
            val n = when (type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> throw IllegalArgumentException("Tipo $type") }
            val v = view(a["bufferView"]!!.jsonPrimitive.int)
            val off = (v["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
            val csize = when (comp) { 5126, 5125 -> 4; 5123 -> 2; 5121 -> 1; else -> throw IllegalArgumentException("componentType $comp") }
            val stride = v["byteStride"]?.jsonPrimitive?.int ?: (csize * n)
            val out = FloatArray(count * n)
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            for (e in 0 until count) for (k in 0 until n) {
                val p = off + e * stride + k * csize
                out[e * n + k] = when (comp) { 5126 -> buf.getFloat(p); 5125 -> buf.getInt(p).toFloat(); 5123 -> (buf.getShort(p).toInt() and 0xFFFF).toFloat(); else -> (buf.get(p).toInt() and 0xFF).toFloat() }
            }
            return out to n
        }

        val images = (j["images"]?.jsonArray ?: JsonArray(emptyList())).map { im ->
            val o = im.jsonObject
            val bv = o["bufferView"]?.jsonPrimitive?.int ?: return@map null
            val v = view(bv); val off = v["byteOffset"]?.jsonPrimitive?.int ?: 0; val len = v["byteLength"]!!.jsonPrimitive.int
            val data = b.copyOfRange(off, off + len)
            runCatching { PngDecoder.decode(data, o["name"]?.jsonPrimitive?.content ?: "image") }.getOrNull()
        }
        val textures = (j["textures"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject["source"]?.jsonPrimitive?.int }
        val mats = (j["materials"]?.jsonArray ?: JsonArray(emptyList())).map { m ->
            val o = m.jsonObject; val pbr = o["pbrMetallicRoughness"]?.jsonObject
            val color = pbr?.get("baseColorFactor")?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f, 1f)
            val ti = pbr?.get("baseColorTexture")?.jsonObject?.get("index")?.jsonPrimitive?.int
            GlbMat(o["name"]?.jsonPrimitive?.content ?: "material", color, pbr?.get("metallicFactor")?.jsonPrimitive?.float ?: 1f, pbr?.get("roughnessFactor")?.jsonPrimitive?.float ?: 1f,
                o["alphaMode"]?.jsonPrimitive?.content ?: "OPAQUE", ti?.let { textures.getOrNull(it) }?.let { images.getOrNull(it) })
        }
        val meshes = (j["meshes"]?.jsonArray ?: JsonArray(emptyList())).map { m ->
            m.jsonObject["primitives"]!!.jsonArray.map { pr ->
                val o = pr.jsonObject; val at = o["attributes"]!!.jsonObject
                val pos = accessor(at["POSITION"]!!.jsonPrimitive.int).first
                val nor = at["NORMAL"]?.jsonPrimitive?.int?.let { accessor(it).first }
                val uv = at["TEXCOORD_0"]?.jsonPrimitive?.int?.let { accessor(it).first }
                val idx = o["indices"]?.jsonPrimitive?.int?.let { i -> accessor(i).first.map { it.toInt() }.toIntArray() } ?: IntArray(pos.size / 3) { it }
                GlbPrimitive(pos, nor, uv, idx, o["material"]?.jsonPrimitive?.int ?: -1)
            }
        }
        val jn = j["nodes"]?.jsonArray ?: JsonArray(emptyList())
        val world = arrayOfNulls<FloatArray>(jn.size)
        fun local(o: JsonObject): FloatArray {
            o["matrix"]?.jsonArray?.let { return it.map { e -> e.jsonPrimitive.float }.toFloatArray() }
            var m = Mat4.identity()
            o["translation"]?.jsonArray?.let { t -> m = Mat4.multiply(m, Mat4.translate(t[0].jsonPrimitive.float, t[1].jsonPrimitive.float, t[2].jsonPrimitive.float)) }
            o["rotation"]?.jsonArray?.let { q -> val x = q[0].jsonPrimitive.float; val y = q[1].jsonPrimitive.float; val z = q[2].jsonPrimitive.float; val w = q[3].jsonPrimitive.float
                m = Mat4.multiply(m, floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f, 2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f, 2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f, 0f, 0f, 0f, 1f)) }
            o["scale"]?.jsonArray?.let { s -> m = Mat4.multiply(m, Mat4.scale3(s[0].jsonPrimitive.float, s[1].jsonPrimitive.float, s[2].jsonPrimitive.float)) }
            return m
        }
        val parent = IntArray(jn.size) { -1 }
        jn.forEachIndexed { i, n -> n.jsonObject["children"]?.jsonArray?.forEach { c -> parent[c.jsonPrimitive.int] = i } }
        fun w(i: Int): FloatArray { world[i]?.let { return it }; val l = local(jn[i].jsonObject); val r = if (parent[i] >= 0) Mat4.multiply(w(parent[i]), l) else l; world[i] = r; return r }
        val nodes = jn.mapIndexed { i, n ->
            val o = n.jsonObject
            GlbNode(o["name"]?.jsonPrimitive?.content ?: "nodo$i", w(i), o["mesh"]?.jsonPrimitive?.int?.let { meshes[it] } ?: emptyList())
        }
        return GlbData(nodes, mats, images, j["asset"]?.jsonObject?.get("generator")?.jsonPrimitive?.content ?: "")
    }

    /** Convierte el GLB (espacio de visor) a un [VehicleModel] mostrable/editable (partes por nodo con malla). */
    fun toVehicleModel(d: GlbData, name: String): VehicleModel {
        var gid = 0; var sid = 0
        val matInfos = d.materials.mapIndexed { i, m ->
            val c = m.color
            MaterialInfo("glb:$i:${m.name}", m.name, (c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt(), (c.getOrElse(3) { 1f } * 255).toInt(), m.texture?.name, m.texture, 0, false, m.metallic, m.roughness, m.alphaMode == "BLEND")
        }
        val def = MaterialInfo("glb:default", "Material por defecto", 200, 200, 200, 255, null, null, 0, false)
        val parts = d.meshNodes.mapIndexed { pi, n ->
            val subs = n.primitives.map { pr ->
                val vc = pr.positions.size / 3
                val nor = pr.normals ?: FloatArray(vc * 3).also { for (v in 0 until vc) it[v * 3 + 1] = 1f }
                val uv = pr.uvs ?: FloatArray(vc * 2)
                SubMesh(sid++, pi, GeometryData(gid++, vc, pr.positions, nor, uv, pr.normals != null, pr.uvs != null, false), pr.indices, matInfos.getOrNull(pr.material) ?: def)
            }
            // los vértices GLB están en espacio de visor: transform GTA = GTA_TO_VIEW · mundo (la conversión es una involución)
            MeshPart(pi, n.name, PartClassifier.categoryOfName(n.name) ?: PartCategory.OTHER, null, null, Mat4.multiply(Mat4.GTA_TO_VIEW, n.world), subs, true, null, true)
        }
        return ModelAssembler.assemble(name, "GLB", parts, listOf("Importado desde GLB (${d.generator})"), validate = true)
    }
}

class VerifyCheck(val name: String, val ok: Boolean, val detail: String)
class VerifyReport(val checks: List<VerifyCheck>, val reimported: GlbData?) {
    val ok get() = checks.all { it.ok }
    fun text() = checks.joinToString("\n") { "${if (it.ok) "✓" else "✕"} ${it.name}: ${it.detail}" }
}

/** Re-importa un GLB recién exportado y lo compara con la escena que se quiso exportar. */
object GlbVerifier {
    fun verify(scene: ExportScene, glb: ByteArray): VerifyReport {
        val checks = ArrayList<VerifyCheck>()
        val data = try { GlbImporter.read(glb) } catch (e: Exception) { return VerifyReport(listOf(VerifyCheck("Lectura del GLB", false, e.message ?: "error")), null) }
        checks += VerifyCheck("Lectura del GLB", true, "${data.nodes.size} nodos, ${data.materials.size} materiales, ${data.images.size} imágenes")
        val expectedParts = scene.meshes.map { it.partName }.toSet()
        val gotParts = data.meshNodes.map { it.name }.toSet()
        checks += VerifyCheck("Piezas exportadas", expectedParts == gotParts, if (expectedParts == gotParts) "${gotParts.size} piezas" else "esperadas $expectedParts, leídas $gotParts")
        checks += VerifyCheck("Triángulos", data.triangles == scene.totalTriangles, "${data.triangles} leídos / ${scene.totalTriangles} esperados")
        val gv = data.meshNodes.sumOf { n -> n.primitives.sumOf { it.positions.size / 3 } }
        checks += VerifyCheck("Vértices", gv == scene.totalVertices, "$gv / ${scene.totalVertices}")
        checks += VerifyCheck("Materiales", data.materials.size == scene.materials.size, "${data.materials.size} / ${scene.materials.size}")
        val texOk = scene.textures.size == data.images.count { it != null } && scene.textures.all { t -> data.images.any { im -> im != null && im.width == t.image.width && im.height == t.image.height } }
        checks += VerifyCheck("Texturas embebidas", texOk, "${data.images.count { it != null }} decodificadas / ${scene.textures.size} esperadas")
        // posiciones: caja idéntica
        val eb = Bounds.empty(); scene.meshes.forEach { m -> for (v in 0 until m.vertexCount) eb.expand(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2]) }
        val gb = Bounds.empty(); val t = FloatArray(3)
        data.meshNodes.forEach { n -> n.primitives.forEach { p -> for (v in 0 until p.positions.size / 3) { Mat4.transformPoint(n.world, p.positions[v * 3], p.positions[v * 3 + 1], p.positions[v * 3 + 2], t); gb.expand(t[0], t[1], t[2]) } } }
        val same = scene.meshes.isEmpty() || (0..2).all { Math.abs(eb.min[it] - gb.min[it]) < 1e-3f && Math.abs(eb.max[it] - gb.max[it]) < 1e-3f }
        checks += VerifyCheck("Geometría (caja envolvente)", same, if (scene.meshes.isEmpty()) "sin mallas" else "dif. máx. %.5f m".format((0..2).maxOf { Math.max(Math.abs(eb.min[it] - gb.min[it]), Math.abs(eb.max[it] - gb.max[it])) }))
        var bad = 0
        data.meshNodes.forEach { n -> n.primitives.forEach { p -> if (p.positions.any { !it.isFinite() } || p.indices.any { it < 0 || it >= p.positions.size / 3 }) bad++
            p.uvs?.let { uv -> if (uv.any { !it.isFinite() }) bad++ } } }
        checks += VerifyCheck("Datos válidos (sin NaN ni índices fuera de rango)", bad == 0, if (bad == 0) "correcto" else "$bad primitivas con problemas")
        return VerifyReport(checks, data)
    }
}
