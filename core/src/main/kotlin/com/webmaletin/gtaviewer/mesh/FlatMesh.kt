package com.webmaletin.gtaviewer.mesh

import com.webmaletin.gtaviewer.model.*
import kotlin.math.sqrt

/**
 * Malla "plana" editable: un solo pool de vértices (posición, normal, UV) + triángulos con un slot de material.
 * Es la representación de trabajo para reparar, separar, borrar caras y fusionar; siempre se opera sobre una COPIA.
 */
class FlatMesh(
    var pos: FloatArray, var nor: FloatArray, var uv: FloatArray,
    var tris: IntArray, var triMat: IntArray, val materials: List<MaterialInfo>,
) {
    val vertexCount get() = pos.size / 3
    val triCount get() = tris.size / 3

    fun copy() = FlatMesh(pos.copyOf(), nor.copyOf(), uv.copyOf(), tris.copyOf(), triMat.copyOf(), materials)

    /** Caja (en las coordenadas actuales de la malla) de los vértices usados. */
    fun bounds(): Bounds {
        val b = Bounds.empty()
        for (i in tris) b.expand(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
        return b
    }

    /** Elimina los triángulos de [remove] y compacta los vértices que dejan de usarse. */
    fun withoutTriangles(remove: Set<Int>): FlatMesh = subset((0 until triCount).filter { it !in remove })

    fun subset(keep: List<Int>): FlatMesh {
        val remap = IntArray(vertexCount) { -1 }
        var n = 0
        for (t in keep) for (k in 0..2) { val v = tris[t * 3 + k]; if (remap[v] < 0) remap[v] = n++ }
        val p = FloatArray(n * 3); val no = FloatArray(n * 3); val u = FloatArray(n * 2)
        for (v in remap.indices) { val r = remap[v]; if (r < 0) continue
            for (c in 0..2) { p[r * 3 + c] = pos[v * 3 + c]; no[r * 3 + c] = nor[v * 3 + c] }
            u[r * 2] = uv[v * 2]; u[r * 2 + 1] = uv[v * 2 + 1] }
        val t = IntArray(keep.size * 3); val tm = IntArray(keep.size)
        for ((i, tr) in keep.withIndex()) { for (k in 0..2) t[i * 3 + k] = remap[tris[tr * 3 + k]]; tm[i] = triMat[tr] }
        return FlatMesh(p, no, u, t, tm, materials)
    }

    /** Aplica una transformación afín a posiciones y normales (devuelve una copia). */
    fun transformed(m: FloatArray): FlatMesh {
        val c = copy(); val t = FloatArray(3)
        for (v in 0 until vertexCount) {
            Mat4.transformPoint(m, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], t); c.pos[v * 3] = t[0]; c.pos[v * 3 + 1] = t[1]; c.pos[v * 3 + 2] = t[2]
            Mat4.transformDir(m, nor[v * 3], nor[v * 3 + 1], nor[v * 3 + 2], t)
            val l = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]).let { if (it < 1e-12f) 1f else it }
            c.nor[v * 3] = t[0] / l; c.nor[v * 3 + 1] = t[1] / l; c.nor[v * 3 + 2] = t[2] / l
        }
        if (Mat4.determinant3(m) < 0f) flipWinding()
        return c
    }

    fun flipWinding() { var i = 0; while (i + 2 < tris.size) { val t = tris[i + 1]; tris[i + 1] = tris[i + 2]; tris[i + 2] = t; i += 3 } }

    /** Normales suaves ponderadas por área (se pierden los bordes duros: úsese solo si faltan o están rotas). */
    fun recomputeNormals() {
        java.util.Arrays.fill(nor, 0f)
        for (t in 0 until triCount) {
            val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (o in intArrayOf(a, b, c)) { nor[o] += nx; nor[o + 1] += ny; nor[o + 2] += nz }
        }
        for (v in 0 until vertexCount) {
            val l = sqrt(nor[v * 3] * nor[v * 3] + nor[v * 3 + 1] * nor[v * 3 + 1] + nor[v * 3 + 2] * nor[v * 3 + 2])
            if (l > 1e-12f) { nor[v * 3] /= l; nor[v * 3 + 1] /= l; nor[v * 3 + 2] /= l } else nor[v * 3 + 1] = 1f
        }
    }

    /**
     * Convierte a una parte del modelo (un sub-mesh por material usado, un único GeometryData nuevo). Conserva UV y normales.
     */
    fun toPart(partId: Int, name: String, category: PartCategory, transform: FloatArray, geometryId: Int, nextSubId: () -> Int, extraSource: String? = null): MeshPart {
        val g = GeometryData(geometryId, vertexCount, pos, nor, uv, true, true, false)
        val subs = ArrayList<SubMesh>()
        for (mi in materials.indices) {
            val idx = ArrayList<Int>()
            for (t in 0 until triCount) if (triMat[t] == mi) { idx += tris[t * 3]; idx += tris[t * 3 + 1]; idx += tris[t * 3 + 2] }
            if (idx.isNotEmpty()) subs += SubMesh(nextSubId(), partId, g, idx.toIntArray(), materials[mi])
        }
        return MeshPart(partId, name, category, null, null, transform, subs, true, null, true, extraSource)
    }

    companion object {
        /** Aplana una parte (todas sus sub-mallas) a un pool único de vértices. [space] se aplica a posiciones y normales. */
        fun fromPart(part: MeshPart, space: FloatArray? = null): FlatMesh {
            val mats = ArrayList<MaterialInfo>()
            val remap = HashMap<Long, Int>()
            val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>()
            val tris = ArrayList<Int>(); val tm = ArrayList<Int>()
            for (sm in part.subMeshes) {
                var mi = mats.indexOfFirst { it === sm.material || it.key == sm.material.key }
                if (mi < 0) { mats += sm.material; mi = mats.size - 1 }
                val g = sm.geometry
                var i = 0
                while (i + 2 < sm.indices.size) {
                    for (k in 0..2) {
                        val v = sm.indices[i + k]
                        val key = (g.id.toLong() shl 32) or v.toLong()
                        val nv = remap.getOrPut(key) {
                            pos += g.positions[v * 3]; pos += g.positions[v * 3 + 1]; pos += g.positions[v * 3 + 2]
                            nor += g.normals[v * 3]; nor += g.normals[v * 3 + 1]; nor += g.normals[v * 3 + 2]
                            uv += g.uvs[v * 2]; uv += g.uvs[v * 2 + 1]
                            pos.size / 3 - 1
                        }
                        tris += nv
                    }
                    tm += mi; i += 3
                }
            }
            val fm = FlatMesh(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), tris.toIntArray(), tm.toIntArray(), mats)
            return if (space != null) fm.transformed(space) else fm
        }

        /** Concatena varias mallas (materiales unificados por clave). */
        fun combine(list: List<FlatMesh>): FlatMesh {
            val mats = ArrayList<MaterialInfo>()
            val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val tris = ArrayList<Int>(); val tm = ArrayList<Int>()
            for (m in list) {
                val base = pos.size / 3
                m.pos.forEach { pos += it }; m.nor.forEach { nor += it }; m.uv.forEach { uv += it }
                val map = m.materials.map { mat -> var i = mats.indexOfFirst { it.key == mat.key }; if (i < 0) { mats += mat; i = mats.size - 1 }; i }
                m.tris.forEach { tris += it + base }; m.triMat.forEach { tm += map[it] }
            }
            return FlatMesh(pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(), tris.toIntArray(), tm.toIntArray(), mats)
        }
    }
}
