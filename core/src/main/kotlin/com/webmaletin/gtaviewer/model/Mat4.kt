package com.webmaletin.gtaviewer.model

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Matrices 4x4 column-major (como OpenGL): m[col*4 + row].
 *
 * CONVERSIÓN DE COORDENADAS (única para visor y exportación, igual para todos los vehículos):
 *   GTA SA: +X derecha, +Y delante del vehículo, +Z arriba (diestro).
 *   Visor/GLB/OBJ/FBX: +Y arriba, el frente del vehículo mira a +Z (convención glTF), +X = lado izquierdo.
 *   (x, y, z)_GTA  ->  (-x, z, y)   — es una rotación propia (det = +1), no refleja el modelo ni invierte el winding.
 * Unidad: 1 unidad GTA = 1 metro.
 */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** GTA (x,y,z) -> espacio de visor/exportación (-x, z, y). */
    val GTA_TO_VIEW: FloatArray = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)

    fun gtaToView(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(-x, z, y)

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    /** Matriz de frame RW: columnas right, up, at y posición. */
    fun fromFrame(rot: FloatArray, pos: FloatArray): FloatArray = floatArrayOf(
        rot[0], rot[1], rot[2], 0f, rot[3], rot[4], rot[5], 0f, rot[6], rot[7], rot[8], 0f, pos[0], pos[1], pos[2], 1f,
    )

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }

    fun transformDir(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, o: Int = 0) {
        out[o] = m[0] * x + m[4] * y + m[8] * z
        out[o + 1] = m[1] * x + m[5] * y + m[9] * z
        out[o + 2] = m[2] * x + m[6] * y + m[10] * z
    }

    fun determinant3(m: FloatArray): Float =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    /** Inversa de una matriz afín (la parte 3x3 debe ser invertible). Devuelve null si es singular. */
    fun invertAffine(m: FloatArray): FloatArray? {
        val det = determinant3(m)
        if (abs(det) < 1e-12f) return null
        val id = 1f / det
        val r = FloatArray(16)
        r[0] = (m[5] * m[10] - m[9] * m[6]) * id
        r[1] = (m[9] * m[2] - m[1] * m[10]) * id
        r[2] = (m[1] * m[6] - m[5] * m[2]) * id
        r[4] = (m[8] * m[6] - m[4] * m[10]) * id
        r[5] = (m[0] * m[10] - m[8] * m[2]) * id
        r[6] = (m[4] * m[2] - m[0] * m[6]) * id
        r[8] = (m[4] * m[9] - m[8] * m[5]) * id
        r[9] = (m[8] * m[1] - m[0] * m[9]) * id
        r[10] = (m[0] * m[5] - m[4] * m[1]) * id
        r[12] = -(r[0] * m[12] + r[4] * m[13] + r[8] * m[14])
        r[13] = -(r[1] * m[12] + r[5] * m[13] + r[9] * m[14])
        r[14] = -(r[2] * m[12] + r[6] * m[13] + r[10] * m[14])
        r[15] = 1f
        return r
    }

    fun scale(s: Float): FloatArray = floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f)

    fun isFinite(m: FloatArray): Boolean = m.all { it.isFinite() }

    /** Rotación de 180° alrededor del eje vertical GTA (Z): x->-x, y->-y. */
    fun rotZ180(): FloatArray = floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun length(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
}

/** AABB en 3 floats min / 3 floats max. */
class Bounds(val min: FloatArray, val max: FloatArray) {
    val center: FloatArray get() = floatArrayOf((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2)
    val size: FloatArray get() = floatArrayOf(max[0] - min[0], max[1] - min[1], max[2] - min[2])
    val radius: Float get() = Mat4.length(size[0], size[1], size[2]) / 2
    val isValid: Boolean get() = min.all { it.isFinite() } && max.all { it.isFinite() } && max[0] >= min[0] && max[1] >= min[1] && max[2] >= min[2]

    companion object {
        fun empty() = Bounds(floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE), floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE))
    }

    fun expand(x: Float, y: Float, z: Float) {
        if (x < min[0]) min[0] = x; if (x > max[0]) max[0] = x
        if (y < min[1]) min[1] = y; if (y > max[1]) max[1] = y
        if (z < min[2]) min[2] = z; if (z > max[2]) max[2] = z
    }

    fun expand(o: Bounds) {
        expand(o.min[0], o.min[1], o.min[2]); expand(o.max[0], o.max[1], o.max[2])
    }
}
