package com.webmaletin.gtaviewer.beamng

import com.webmaletin.gtaviewer.model.Mat4
import java.io.InputStream

/** Triángulos de una malla Collada que usan un mismo material (símbolo). Los índices apuntan a [ColladaMesh] (vértices ya unificados). */
class ColladaGroup(val materialSymbol: String, val indices: IntArray)

class ColladaMesh(
    val id: String, val name: String,
    val positions: FloatArray, val normals: FloatArray,
    /** (u, v) con origen ARRIBA-izquierda (v ya invertida respecto a Collada/OpenGL). */
    val uvs: FloatArray, val hasNormals: Boolean, val hasUvs: Boolean,
    val groups: List<ColladaGroup>,
) { val vertexCount get() = positions.size / 3 }

/** Nodo de la escena: instancia de una geometría con su transformación de mundo (columna-mayor) y el mapa símbolo → id de material. */
class ColladaNode(val id: String, val name: String, val geometryId: String, val world: FloatArray, val materials: Map<String, String>)

class ColladaScene(
    val upAxis: String, val unitMeters: Float,
    val nodes: List<ColladaNode>,
    /** id de material → nombre. */
    val materialNames: Map<String, String>,
    /** id de material → color difuso del efecto (RGBA 0..1). */
    val materialColors: Map<String, FloatArray>,
    val geometryNames: Map<String, String>,
)

/** Lector Collada en streaming: primero la escena (rápido, sin tocar los arrays numéricos), después solo las geometrías necesarias. */
object Collada {
    private class NodeCtx(val id: String, val name: String) { var local: FloatArray = Mat4.identity() }

    fun readScene(ins: InputStream): ColladaScene {
        val x = XmlPull(ins)
        var up = "Y_UP"; var unit = 1f
        val nodes = ArrayList<ColladaNode>()
        val matNames = LinkedHashMap<String, String>(); val matEffect = HashMap<String, String>()
        val effectColor = HashMap<String, FloatArray>()
        val geoNames = HashMap<String, String>()
        val stack = ArrayList<NodeCtx>()
        var curEffect: String? = null; var inDiffuse = false; var curMatId: String? = null
        var inVisualScene = false
        var instGeo: String? = null; var instMats: LinkedHashMap<String, String>? = null
        fun world(): FloatArray { var w = Mat4.identity(); for (n in stack) w = Mat4.multiply(w, n.local); return w }
        while (x.nextTag()) {
            if (x.isEnd) {
                when (x.name) {
                    "node" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    "effect" -> curEffect = null
                    "diffuse" -> inDiffuse = false
                    "material" -> curMatId = null
                    "visual_scene" -> inVisualScene = false
                    "instance_geometry" -> {
                        val g = instGeo; val m = instMats
                        if (g != null && m != null && stack.isNotEmpty()) { val n = stack.last(); nodes += ColladaNode(n.id, n.name, g, world(), m) }
                        instGeo = null; instMats = null
                    }
                }
                continue
            }
            when (x.name) {
                "up_axis" -> up = x.readText()
                "unit" -> unit = x.attr("meter")?.toFloatOrNull() ?: 1f
                "effect" -> curEffect = x.attr("id")
                "diffuse" -> inDiffuse = true
                "color" -> if (inDiffuse && curEffect != null) { val f = x.readFloats(4); if (f.size >= 3) effectColor[curEffect] = floatArrayOf(f[0], f[1], f[2], if (f.size > 3) f[3] else 1f) }
                "material" -> if (!inVisualScene) { val id = x.attr("id"); if (id != null) { matNames[id] = x.attr("name") ?: id; curMatId = id; if (x.selfClosing) curMatId = null } }
                "instance_effect" -> curMatId?.let { matEffect[it] = (x.attr("url") ?: "").removePrefix("#") }
                "geometry" -> { val id = x.attr("id"); if (id != null) geoNames[id] = x.attr("name") ?: id; x.skipElement() }
                "visual_scene" -> inVisualScene = true
                "node" -> if (inVisualScene) {
                    stack += NodeCtx(x.attr("id") ?: "", x.attr("name") ?: x.attr("id") ?: "")
                    if (x.selfClosing) stack.removeAt(stack.size - 1)
                }
                "matrix" -> if (inVisualScene && stack.isNotEmpty()) {
                    val f = x.readFloats(16)
                    if (f.size >= 16) { val m = FloatArray(16); for (r in 0..3) for (c in 0..3) m[c * 4 + r] = f[r * 4 + c]; stack.last().local = m }
                }
                "instance_geometry" -> if (inVisualScene && stack.isNotEmpty()) {
                    instGeo = (x.attr("url") ?: "").removePrefix("#"); instMats = LinkedHashMap()
                    if (x.selfClosing) { nodes += ColladaNode(stack.last().id, stack.last().name, instGeo!!, world(), instMats!!); instGeo = null; instMats = null }
                }
                "instance_material" -> { val m = instMats; if (m != null) { val s = x.attr("symbol"); val t = x.attr("target"); if (s != null && t != null) m[s] = t.removePrefix("#") } }
            }
        }
        val colors = matNames.keys.mapNotNull { id -> effectColor[matEffect[id]]?.let { id to it } }.toMap()
        return ColladaScene(up, unit, nodes, matNames, colors, geoNames)
    }

    // ------------------------------------------------------------------------------------------------ geometrías

    private class Source(val id: String, var data: FloatArray, var stride: Int)
    private class Input(val semantic: String, val source: String, val offset: Int, val set: Int)
    private class Prim(val material: String, val inputs: List<Input>, val stride: Int, val tri: IntArray)

    /** Lee solo las geometrías cuyo id esté en [wanted] (las demás se saltan sin interpretar sus números). */
    fun readGeometries(ins: InputStream, wanted: Set<String>): Map<String, ColladaMesh> {
        val x = XmlPull(ins)
        val out = HashMap<String, ColladaMesh>()
        while (x.nextTag()) {
            if (x.isEnd || x.name != "geometry") continue
            val id = x.attr("id") ?: continue
            if (id !in wanted) { x.skipElement(); continue }
            readGeometry(x, id, x.attr("name") ?: id)?.let { out[id] = it }
        }
        return out
    }

    private fun readGeometry(x: XmlPull, id: String, name: String): ColladaMesh? {
        val sources = HashMap<String, Source>()
        val vertexAlias = HashMap<String, String>() // <vertices id> -> fuente POSITION
        val prims = ArrayList<Prim>()
        var depth = 1
        var curSource: Source? = null
        while (depth > 0 && x.nextTag()) {
            if (x.isEnd) { depth--; if (x.name == "source") curSource = null; continue }
            val open = !x.selfClosing
            when (x.name) {
                "source" -> { val sid = x.attr("id"); curSource = if (sid != null) Source(sid, FloatArray(0), 1).also { sources[sid] = it } else null }
                "float_array" -> { val cnt = x.attr("count")?.toIntOrNull() ?: 16; curSource?.data = x.readFloats(cnt) }
                "accessor" -> { val st = x.attr("stride")?.toIntOrNull(); if (st != null) curSource?.stride = st }
                "vertices" -> {
                    val vid = x.attr("id")
                    if (open) {
                        var d2 = 1
                        while (d2 > 0 && x.nextTag()) {
                            if (x.isEnd) { d2--; continue }
                            if (!x.selfClosing) d2++
                            if (x.name == "input" && x.attr("semantic") == "POSITION" && vid != null) vertexAlias[vid] = (x.attr("source") ?: "").removePrefix("#")
                        }
                        continue // el cierre de <vertices> ya se consumió
                    }
                }
                "triangles", "polylist", "polygons" -> {
                    val kind = x.name; val mat = x.attr("material") ?: ""
                    val inputs = ArrayList<Input>(); var vcount: IntArray? = null; var p: IntArray? = null
                    if (open) {
                        var d2 = 1
                        while (d2 > 0 && x.nextTag()) {
                            if (x.isEnd) { d2--; continue }
                            if (!x.selfClosing) d2++
                            when (x.name) {
                                "input" -> inputs += Input(x.attr("semantic") ?: "", (x.attr("source") ?: "").removePrefix("#"), x.attr("offset")?.toIntOrNull() ?: 0, x.attr("set")?.toIntOrNull() ?: 0)
                                "vcount" -> vcount = x.readInts(1024)
                                "p" -> if (p == null) p = x.readInts(4096)
                            }
                        }
                        val stride = (inputs.maxOfOrNull { it.offset } ?: 0) + 1
                        val idx = p ?: IntArray(0)
                        val tri = if (kind == "polylist") triangulate(idx, vcount ?: IntArray(0), stride) else idx
                        prims += Prim(mat, inputs, stride, tri)
                        continue // el cierre del elemento primitivo ya se consumió
                    }
                }
            }
            if (open) depth++
        }
        return build(id, name, sources, vertexAlias, prims)
    }

    private fun triangulate(p: IntArray, vcount: IntArray, stride: Int): IntArray {
        var total = 0; for (c in vcount) total += maxOf(0, c - 2) * 3 * stride
        val out = IntArray(total); var w = 0; var o = 0
        for (c in vcount) {
            for (k in 1 until c - 1) {
                for (corner in intArrayOf(0, k, k + 1)) for (s in 0 until stride) out[w++] = p[o + corner * stride + s]
            }
            o += c * stride
        }
        return out
    }

    private fun build(id: String, name: String, sources: Map<String, Source>, vertexAlias: Map<String, String>, prims: List<Prim>): ColladaMesh? {
        if (prims.isEmpty()) return null
        // fuentes por semántica (se toman de la primera primitiva; las demás comparten datos en Blender)
        val ref = prims.first().inputs
        fun src(sem: String, setNo: Int? = null): Pair<Input, Source>? {
            val i = ref.firstOrNull { it.semantic == sem && (setNo == null || it.set == setNo) } ?: return null
            val sid = if (sem == "VERTEX") vertexAlias[i.source] ?: i.source else i.source
            return sources[sid]?.let { i to it }
        }
        val pos = src("VERTEX") ?: return null
        val nor = src("NORMAL"); val uv = src("TEXCOORD", 0) ?: src("TEXCOORD")
        val posS = pos.second; val norS = nor?.second; val uvS = uv?.second
        // unificación (posición, normal, uv) -> vértice
        val map = HashMap<Long, Int>()
        val pl = ArrayList<Float>(); var vcount = 0
        var outPos = FloatArray(1024 * 3); var outNor = FloatArray(1024 * 3); var outUv = FloatArray(1024 * 2)
        fun grow(n: Int) { if (n * 3 > outPos.size) { val c = maxOf(n * 3, outPos.size * 2); outPos = outPos.copyOf(c); outNor = outNor.copyOf(c); outUv = outUv.copyOf(c / 3 * 2) } }
        val groups = ArrayList<ColladaGroup>()
        for (pr in prims) {
            val pOff = pr.inputs.firstOrNull { it.semantic == "VERTEX" }?.offset ?: 0
            val nOff = pr.inputs.firstOrNull { it.semantic == "NORMAL" }?.offset ?: -1
            val tOff = (pr.inputs.firstOrNull { it.semantic == "TEXCOORD" && it.set == (uv?.first?.set ?: 0) } ?: pr.inputs.firstOrNull { it.semantic == "TEXCOORD" })?.offset ?: -1
            val n = pr.tri.size / pr.stride
            val idx = IntArray(n)
            for (i in 0 until n) {
                val b = i * pr.stride
                val pi = pr.tri[b + pOff]; val ni = if (nOff >= 0 && norS != null) pr.tri[b + nOff] else -1; val ti = if (tOff >= 0 && uvS != null) pr.tri[b + tOff] else -1
                val key = (pi.toLong() shl 42) or ((ni + 1).toLong() shl 21) or (ti + 1).toLong()
                var v = map[key]
                if (v == null) {
                    v = vcount++; grow(vcount); map[key] = v
                    val ps = posS.stride
                    outPos[v * 3] = posS.data[pi * ps]; outPos[v * 3 + 1] = posS.data[pi * ps + 1]; outPos[v * 3 + 2] = posS.data[pi * ps + 2]
                    if (ni >= 0 && norS != null) { val s = norS.stride; outNor[v * 3] = norS.data[ni * s]; outNor[v * 3 + 1] = norS.data[ni * s + 1]; outNor[v * 3 + 2] = norS.data[ni * s + 2] }
                    if (ti >= 0 && uvS != null) { val s = uvS.stride; outUv[v * 2] = uvS.data[ti * s]; outUv[v * 2 + 1] = 1f - uvS.data[ti * s + 1] }
                }
                idx[i] = v
            }
            groups += ColladaGroup(pr.material, idx)
        }
        val hasN = nor != null; val hasUv = uv != null
        val posA = outPos.copyOf(vcount * 3); val norA = outNor.copyOf(vcount * 3); val uvA = outUv.copyOf(vcount * 2)
        if (!hasN) smoothNormals(posA, norA, groups)
        return ColladaMesh(id, name, posA, norA, uvA, hasN, hasUv, groups)
    }

    private fun smoothNormals(pos: FloatArray, out: FloatArray, groups: List<ColladaGroup>) {
        for (g in groups) {
            var i = 0
            while (i + 2 < g.indices.size) {
                val a = g.indices[i]; val b = g.indices[i + 1]; val c = g.indices[i + 2]; i += 3
                val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
                val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, b, c)) { out[v * 3] += nx; out[v * 3 + 1] += ny; out[v * 3 + 2] += nz }
            }
        }
        for (v in 0 until out.size / 3) { val l = Math.sqrt((out[v * 3] * out[v * 3] + out[v * 3 + 1] * out[v * 3 + 1] + out[v * 3 + 2] * out[v * 3 + 2]).toDouble()).toFloat(); if (l > 1e-12f) { out[v * 3] /= l; out[v * 3 + 1] /= l; out[v * 3 + 2] /= l } else out[v * 3 + 2] = 1f }
    }
}
