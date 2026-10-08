package com.webmaletin.gtaviewer.camera

import com.webmaletin.gtaviewer.model.Bounds
import com.webmaletin.gtaviewer.model.Mat4
import com.webmaletin.gtaviewer.selection.Ray
import kotlin.math.*

enum class CameraPreset { FRONT, BACK, SIDE, TOP, THREE_QUARTER }

/**
 * Cámara orbital en espacio de visor (Y arriba; el vehículo mira a +Z, su lado izquierdo es +X).
 * yaw 0 => la cámara está delante del vehículo (vista frontal) mirando hacia -Z.
 */
class OrbitCamera {
    var yaw = 35f          // grados
    var pitch = 18f        // grados
    var distance = 8f
    val target = floatArrayOf(0f, 0.7f, 0f)
    var fovYDeg = 40f
    private var sceneRadius = 3f

    fun setPreset(p: CameraPreset) {
        when (p) {
            CameraPreset.FRONT -> { yaw = 0f; pitch = 5f }
            CameraPreset.BACK -> { yaw = 180f; pitch = 5f }
            CameraPreset.SIDE -> { yaw = 90f; pitch = 3f }
            CameraPreset.TOP -> { yaw = 0f; pitch = 89f }
            CameraPreset.THREE_QUARTER -> { yaw = 35f; pitch = 18f }
        }
    }

    /** Encuadra [b] por completo: la distancia garantiza que la esfera envolvente cabe en ambos ejes del viewport. */
    fun frame(b: Bounds, aspect: Float) {
        require(b.isValid) { "Caja inválida" }
        val c = b.center
        target[0] = c[0]; target[1] = c[1]; target[2] = c[2]
        sceneRadius = max(b.radius, 0.01f)
        distance = fitDistance(sceneRadius, aspect)
    }

    fun fitDistance(radius: Float, aspect: Float): Float {
        val v = Math.toRadians(fovYDeg.toDouble()) / 2
        val h = atan(tan(v) * aspect)
        val half = min(v, h)
        return (radius / sin(half) * 1.06).toFloat()
    }

    fun orbit(dxDeg: Float, dyDeg: Float) {
        yaw += dxDeg
        pitch = (pitch + dyDeg).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(sceneRadius * 0.15f, sceneRadius * 40f)
    }

    fun position(): FloatArray {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        val cp = cos(p)
        return floatArrayOf(
            target[0] + distance * (cp * sin(y)).toFloat(),
            target[1] + distance * sin(p).toFloat(),
            target[2] + distance * (cp * cos(y)).toFloat(),
        )
    }

    private fun basis(): Triple<FloatArray, FloatArray, FloatArray> {
        val pos = position()
        val f = normalize(floatArrayOf(target[0] - pos[0], target[1] - pos[1], target[2] - pos[2]))
        var up = floatArrayOf(0f, 1f, 0f)
        if (abs(f[1]) > 0.999f) {
            // Vista cenital: el "arriba" de pantalla es la dirección horizontal del yaw (frente del vehículo arriba).
            val yawR = Math.toRadians(yaw.toDouble())
            val s = if (f[1] < 0) 1f else -1f
            up = floatArrayOf(sin(yawR).toFloat() * s, 0f, cos(yawR).toFloat() * s)
        }
        val r = normalize(cross(f, up))
        val u = cross(r, f)
        return Triple(r, u, f)
    }

    /** Desplazamiento en pantalla (píxeles) -> mueve el objetivo. */
    fun pan(dxPx: Float, dyPx: Float, viewportHeightPx: Int) {
        val (r, u, _) = basis()
        val worldPerPx = 2f * distance * tan(Math.toRadians(fovYDeg / 2.0)).toFloat() / viewportHeightPx
        for (i in 0..2) target[i] += (-r[i] * dxPx + u[i] * dyPx) * worldPerPx
    }

    fun near() = max(distance * 0.02f, 0.01f)
    fun far() = distance + sceneRadius * 10f + 50f

    fun viewMatrix(): FloatArray {
        val pos = position()
        val (r, u, f) = basis()
        return floatArrayOf(
            r[0], u[0], -f[0], 0f,
            r[1], u[1], -f[1], 0f,
            r[2], u[2], -f[2], 0f,
            -(r[0] * pos[0] + r[1] * pos[1] + r[2] * pos[2]),
            -(u[0] * pos[0] + u[1] * pos[1] + u[2] * pos[2]),
            (f[0] * pos[0] + f[1] * pos[1] + f[2] * pos[2]),
            1f,
        )
    }

    fun projectionMatrix(aspect: Float): FloatArray {
        val n = near(); val fa = far()
        val t = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        return floatArrayOf(
            t / aspect, 0f, 0f, 0f,
            0f, t, 0f, 0f,
            0f, 0f, (fa + n) / (n - fa), -1f,
            0f, 0f, 2f * fa * n / (n - fa), 0f,
        )
    }

    /** Rayo del mundo para un píxel de pantalla (origen en y abajo = coordenadas de MotionEvent). */
    fun rayFor(px: Float, py: Float, width: Int, height: Int): Ray {
        val pos = position()
        val (r, u, f) = basis()
        val aspect = width.toFloat() / height
        val tanY = tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        val nx = (2f * px / width - 1f) * tanY * aspect
        val ny = (1f - 2f * py / height) * tanY
        val d = normalize(floatArrayOf(f[0] + r[0] * nx + u[0] * ny, f[1] + r[1] * nx + u[1] * ny, f[2] + r[2] * nx + u[2] * ny))
        return Ray(pos, d)
    }

    /** Proyecta un punto del mundo a píxeles; null si queda detrás de la cámara. */
    fun project(x: Float, y: Float, z: Float, width: Int, height: Int): FloatArray? {
        val v = viewMatrix(); val p = projectionMatrix(width.toFloat() / height)
        val vp = Mat4.multiply(p, v)
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= 1e-6f) return null
        return floatArrayOf((cx / cw * 0.5f + 0.5f) * width, (1f - (cy / cw * 0.5f + 0.5f)) * height)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).let { if (it < 1e-12f) 1f else it }
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
}
