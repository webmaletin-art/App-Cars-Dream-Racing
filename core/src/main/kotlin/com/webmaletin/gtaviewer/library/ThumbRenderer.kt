package com.webmaletin.gtaviewer.library

import com.webmaletin.gtaviewer.model.Bounds
import com.webmaletin.gtaviewer.model.Mat4
import com.webmaletin.gtaviewer.model.VehicleModel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Miniaturas por software (ortográfica, z-buffer, iluminación lambertiana + muestreo de textura). No necesita OpenGL, por lo
 * que se puede generar en segundo plano y bajo demanda sin contexto gráfico.
 */
object ThumbRenderer {
    /** @return RGBA size×size con fondo transparente. */
    fun render(model: VehicleModel, visible: Set<Int>? = null, size: Int = 160, yawDeg: Float = 35f, pitchDeg: Float = 18f,
               paint: com.webmaletin.gtaviewer.export.PaintScheme = com.webmaletin.gtaviewer.export.PaintScheme()): ByteArray {
        val parts = model.parts.filter { p -> if (visible == null) p.defaultVisible else p.subMeshes.any { it.id in visible } }
        val out = ByteArray(size * size * 4)
        if (parts.isEmpty()) return out
        val yaw = Math.toRadians(yawDeg.toDouble()); val pitch = Math.toRadians(pitchDeg.toDouble())
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat(); val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()
        // vista = Rx(pitch) · Ry(-yaw): la cámara está en (sin yaw, ·, cos yaw) mirando al centro, como OrbitCamera
        fun rot(x: Float, y: Float, z: Float, o: FloatArray) {
            val x1 = cy * x - sy * z; val z1 = sy * x + cy * z
            val y2 = cp * y - sp * z1; val z2 = sp * y + cp * z1
            o[0] = x1; o[1] = y2; o[2] = z2
        }
        // 1) transformar vértices y obtener caja en pantalla
        class Prepared(val sx: FloatArray, val sy: FloatArray, val sz: FloatArray, val wn: FloatArray)
        val prepared = HashMap<Pair<Int, Int>, Prepared>()
        val bb = Bounds.empty()
        val tmp = FloatArray(3); val r = FloatArray(3)
        for (p in parts) {
            val m = model.viewMatrix(p)
            for (sm in p.subMeshes) {
                if (visible != null && sm.id !in visible) continue
                val g = sm.geometry
                val key = p.id to g.id
                if (key in prepared) continue
                val n = g.vertexCount
                val sx = FloatArray(n); val syy = FloatArray(n); val sz = FloatArray(n); val wn = FloatArray(n * 3)
                for (v in 0 until n) {
                    Mat4.transformPoint(m, g.positions[v * 3], g.positions[v * 3 + 1], g.positions[v * 3 + 2], tmp)
                    rot(tmp[0], tmp[1], tmp[2], r); sx[v] = r[0]; syy[v] = r[1]; sz[v] = r[2]
                    Mat4.transformDir(m, g.normals[v * 3], g.normals[v * 3 + 1], g.normals[v * 3 + 2], tmp)
                    rot(tmp[0], tmp[1], tmp[2], r); wn[v * 3] = r[0]; wn[v * 3 + 1] = r[1]; wn[v * 3 + 2] = r[2]
                }
                prepared[key] = Prepared(sx, syy, sz, wn)
                for (i in sm.indices) bb.expand(sx[i], syy[i], sz[i])
            }
        }
        if (!bb.isValid) return out
        val span = max(bb.max[0] - bb.min[0], bb.max[1] - bb.min[1]).coerceAtLeast(1e-4f)
        val scale = (size - 8) / span
        val ox = (size - (bb.max[0] - bb.min[0]) * scale) / 2 - bb.min[0] * scale
        val oy = (size - (bb.max[1] - bb.min[1]) * scale) / 2 + bb.max[1] * scale
        val zbuf = FloatArray(size * size) { -Float.MAX_VALUE }
        val light = floatArrayOf(0.35f, 0.75f, 0.55f).let { l -> val n = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2]); floatArrayOf(l[0] / n, l[1] / n, l[2] / n) }

        for (p in parts) for (sm in p.subMeshes) {
            if (visible != null && sm.id !in visible) continue
            val g = sm.geometry; val pr = prepared[p.id to g.id] ?: continue
            val mat = sm.material
            val tex = mat.texture
            val painted = paint.colorFor(mat.paintSlot)
            val baseR = (painted?.let { (it shr 16) and 255 } ?: mat.r) / 255f; val baseG = (painted?.let { (it shr 8) and 255 } ?: mat.g) / 255f; val baseB = (painted?.let { it and 255 } ?: mat.b) / 255f
            val idx = sm.indices
            var t = 0
            while (t + 2 < idx.size) {
                val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]; t += 3
                val ax = pr.sx[a] * scale + ox; val ay = oy - pr.sy[a] * scale
                val bx = pr.sx[b] * scale + ox; val by = oy - pr.sy[b] * scale
                val cx = pr.sx[c] * scale + ox; val cy2 = oy - pr.sy[c] * scale
                val minX = max(0, min(ax, min(bx, cx)).toInt()); val maxX = min(size - 1, max(ax, max(bx, cx)).toInt() + 1)
                val minY = max(0, min(ay, min(by, cy2)).toInt()); val maxY = min(size - 1, max(ay, max(by, cy2)).toInt() + 1)
                val den = (by - cy2) * (ax - cx) + (cx - bx) * (ay - cy2)
                if (abs(den) < 1e-9f) continue
                val inv = 1f / den
                for (py in minY..maxY) for (px in minX..maxX) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    val w0 = ((by - cy2) * (fx - cx) + (cx - bx) * (fy - cy2)) * inv
                    val w1 = ((cy2 - ay) * (fx - cx) + (ax - cx) * (fy - cy2)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val z = w0 * pr.sz[a] + w1 * pr.sz[b] + w2 * pr.sz[c]
                    val zi = py * size + px
                    if (z <= zbuf[zi]) continue
                    // normal interpolada (a dos caras) e iluminación
                    var nx = w0 * pr.wn[a * 3] + w1 * pr.wn[b * 3] + w2 * pr.wn[c * 3]
                    var ny = w0 * pr.wn[a * 3 + 1] + w1 * pr.wn[b * 3 + 1] + w2 * pr.wn[c * 3 + 1]
                    var nz = w0 * pr.wn[a * 3 + 2] + w1 * pr.wn[b * 3 + 2] + w2 * pr.wn[c * 3 + 2]
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= nl; ny /= nl; nz /= nl
                    if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz } // cara vista desde la cámara (z+ = hacia la cámara)
                    val lam = max(0f, nx * light[0] + ny * light[1] + nz * light[2])
                    val shade = 0.35f + 0.75f * lam
                    var rr = baseR; var gg = baseG; var bbb = baseB
                    if (tex != null && g.hasOriginalUvs) {
                        val u = w0 * g.uvs[a * 2] + w1 * g.uvs[b * 2] + w2 * g.uvs[c * 2]
                        val v = w0 * g.uvs[a * 2 + 1] + w1 * g.uvs[b * 2 + 1] + w2 * g.uvs[c * 2 + 1]
                        val tx = ((u - Math.floor(u.toDouble()).toFloat()) * tex.width).toInt().coerceIn(0, tex.width - 1)
                        val ty = ((v - Math.floor(v.toDouble()).toFloat()) * tex.height).toInt().coerceIn(0, tex.height - 1)
                        val o = (ty * tex.width + tx) * 4
                        if ((tex.rgba[o + 3].toInt() and 0xFF) < 40 && tex.hasAlpha) continue
                        rr *= (tex.rgba[o].toInt() and 0xFF) / 255f; gg *= (tex.rgba[o + 1].toInt() and 0xFF) / 255f; bbb *= (tex.rgba[o + 2].toInt() and 0xFF) / 255f
                    }
                    zbuf[zi] = z
                    val o = zi * 4
                    out[o] = (min(1f, rr * shade) * 255).toInt().toByte(); out[o + 1] = (min(1f, gg * shade) * 255).toInt().toByte()
                    out[o + 2] = (min(1f, bbb * shade) * 255).toInt().toByte(); out[o + 3] = 255.toByte()
                }
            }
        }
        return out
    }
}
