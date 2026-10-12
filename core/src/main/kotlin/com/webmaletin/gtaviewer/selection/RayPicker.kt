package com.webmaletin.gtaviewer.selection

import com.webmaletin.gtaviewer.model.Mat4
import com.webmaletin.gtaviewer.model.SubMesh
import com.webmaletin.gtaviewer.model.VehicleModel
import kotlin.math.abs

class Ray(val origin: FloatArray, val dir: FloatArray)
/** [triangle] = índice del triángulo dentro del sub-mesh tocado (-1 si no se calculó). */
class Hit(val subMeshId: Int, val distance: Float, val triangle: Int = -1)

/** Picking por rayo contra los triángulos reales (sin buffers de color), en espacio de visor. */
object RayPicker {
    /**
     * @param sceneMatrix matriz aplicada a toda la escena en el visor (p. ej. escala de normalización).
     */
    fun pick(model: VehicleModel, ray: Ray, isVisible: (SubMesh) -> Boolean, sceneMatrix: FloatArray = Mat4.identity()): Hit? {
        var best: Hit? = null
        for (part in model.parts) {
            val m = Mat4.multiply(sceneMatrix, model.viewMatrix(part))
            val inv = Mat4.invertAffine(m) ?: continue
            val o = FloatArray(3); val d = FloatArray(3)
            Mat4.transformPoint(inv, ray.origin[0], ray.origin[1], ray.origin[2], o)
            Mat4.transformDir(inv, ray.dir[0], ray.dir[1], ray.dir[2], d)
            for (sm in part.subMeshes) {
                if (!isVisible(sm)) continue
                val (t, tri) = intersectTri(sm, o, d) ?: continue
                if (best == null || t < best.distance) best = Hit(sm.id, t, tri)
            }
        }
        return best
    }

    /** Möller–Trumbore a dos caras. Devuelve t (en unidades del parámetro del rayo, igual en espacio local y de visor). */
    fun intersect(sm: SubMesh, o: FloatArray, d: FloatArray): Float? = intersectTri(sm, o, d)?.first

    fun intersectTri(sm: SubMesh, o: FloatArray, d: FloatArray): Pair<Float, Int>? {
        val p = sm.geometry.positions
        val idx = sm.indices
        var best = Float.MAX_VALUE
        var found = false
        var bestTri = -1
        var i = 0
        while (i + 2 < idx.size) {
            val a = idx[i] * 3; val b = idx[i + 1] * 3; val c = idx[i + 2] * 3
            i += 3
            val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
            val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
            val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz
            if (abs(det) < 1e-12f) continue
            val inv = 1f / det
            val tx = o[0] - p[a]; val ty = o[1] - p[a + 1]; val tz = o[2] - p[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv
            if (u < 0f || u > 1f) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val t = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (t > 1e-5f && t < best) { best = t; found = true; bestTri = i / 3 - 1 }
        }
        return if (found) best to bestTri else null
    }
}
