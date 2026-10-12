package com.webmaletin.gtaviewer.uv

import com.webmaletin.gtaviewer.model.GeometryData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class UvIsland(val triangles: IntArray, val minU: Float, val minV: Float, val maxU: Float, val maxV: Float) {
    val centerU get() = (minU + maxU) / 2
    val centerV get() = (minV + maxV) / 2
}

/** Informe de estiramiento: relación área 3D / área UV de cada triángulo respecto a la mediana del conjunto. */
class StretchReport(val triangles: Int, val stretched: Int, val worstRatio: Float, val flaggedTriangles: List<Int>) {
    val fraction get() = if (triangles == 0) 0f else stretched.toFloat() / triangles
}

object UvTools {
    /** Islas = triángulos conectados por vértices compartidos (en el mismo GeometryData/sub-mesh). */
    fun islands(g: GeometryData, indices: IntArray): List<UvIsland> {
        val n = indices.size / 3
        val parent = IntArray(g.vertexCount) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for (t in 0 until n) { val a = find(indices[t * 3]); for (k in 1..2) { val b = find(indices[t * 3 + k]); if (a != b) parent[b] = a } }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (t in 0 until n) groups.getOrPut(find(indices[t * 3])) { ArrayList() } += t
        return groups.values.map { tris ->
            var mnU = Float.MAX_VALUE; var mnV = Float.MAX_VALUE; var mxU = -Float.MAX_VALUE; var mxV = -Float.MAX_VALUE
            for (t in tris) for (k in 0..2) { val v = indices[t * 3 + k]; val u = g.uvs[v * 2]; val w = g.uvs[v * 2 + 1]; mnU = minOf(mnU, u); mxU = maxOf(mxU, u); mnV = minOf(mnV, w); mxV = maxOf(mxV, w) }
            UvIsland(tris.toIntArray(), mnU, mnV, mxU, mxV)
        }
    }

    fun stretch(g: GeometryData, indices: IntArray, factor: Float = 4f): StretchReport {
        val n = indices.size / 3
        val ratios = FloatArray(n)
        for (t in 0 until n) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val ux = g.positions[b * 3] - g.positions[a * 3]; val uy = g.positions[b * 3 + 1] - g.positions[a * 3 + 1]; val uz = g.positions[b * 3 + 2] - g.positions[a * 3 + 2]
            val vx = g.positions[c * 3] - g.positions[a * 3]; val vy = g.positions[c * 3 + 1] - g.positions[a * 3 + 1]; val vz = g.positions[c * 3 + 2] - g.positions[a * 3 + 2]
            val area3 = 0.5f * sqrt((uy * vz - uz * vy).let { it * it } + (uz * vx - ux * vz).let { it * it } + (ux * vy - uy * vx).let { it * it })
            val du1 = g.uvs[b * 2] - g.uvs[a * 2]; val dv1 = g.uvs[b * 2 + 1] - g.uvs[a * 2 + 1]; val du2 = g.uvs[c * 2] - g.uvs[a * 2]; val dv2 = g.uvs[c * 2 + 1] - g.uvs[a * 2 + 1]
            val areaUv = 0.5f * abs(du1 * dv2 - du2 * dv1)
            ratios[t] = if (areaUv < 1e-12f) Float.POSITIVE_INFINITY else area3 / areaUv
        }
        val finite = ratios.filter { it.isFinite() && it > 0f }.sorted()
        val med = if (finite.isEmpty()) 1f else finite[finite.size / 2]
        val flagged = ArrayList<Int>(); var worst = 1f
        for (t in 0 until n) {
            val r = ratios[t] / med
            val dev = if (!r.isFinite() || r <= 0f) Float.POSITIVE_INFINITY else maxOf(r, 1f / r)
            if (dev > factor) flagged += t
            if (dev.isFinite() && dev > worst) worst = dev
        }
        return StretchReport(n, flagged.size, worst, flagged)
    }

    /** Copia de las UV con la isla movida/rotada/escalada alrededor de su centro. NO modifica el original. */
    fun transformIsland(uvs: FloatArray, indices: IntArray, island: UvIsland, du: Float, dv: Float, scale: Float, rotationDeg: Float): FloatArray {
        val out = uvs.copyOf(); val done = HashSet<Int>()
        val rad = Math.toRadians(rotationDeg.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        for (t in island.triangles) for (k in 0..2) {
            val v = indices[t * 3 + k]; if (!done.add(v)) continue
            val x = (uvs[v * 2] - island.centerU) * scale; val y = (uvs[v * 2 + 1] - island.centerV) * scale
            out[v * 2] = island.centerU + du + c * x - s * y; out[v * 2 + 1] = island.centerV + dv + s * x + c * y
        }
        return out
    }
}
