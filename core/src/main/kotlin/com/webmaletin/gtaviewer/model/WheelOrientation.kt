package com.webmaletin.gtaviewer.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Orientación de una rueda deducida de SU GEOMETRÍA REAL (no de "es una rueda derecha").
 *
 * Qué se sabe del formato (verificado con un DFF real de GTA SA, ver [WheelOrientationTest]):
 *  - la rueda es un sólido de revolución cuyo EJE es la dimensión más estrecha de su caja (en GTA SA suele ser X local);
 *  - la geometría NO es simétrica a lo largo del eje: llanta/disco exterior a un lado; disco de freno, sombra y
 *    borde interior del neumático al otro;
 *  - el DFF puede traer las 4 ruedas como copias idénticas con matriz identidad (el motor del juego decide el lado),
 *    de modo que usar solo la matriz del frame deja las ruedas de un lado mirando hacia dentro.
 *
 * Por eso: (1) se detecta en la geometría qué lado del eje es el EXTERIOR; (2) con la matriz real del punto de montaje se
 * calcula hacia dónde apunta ese lado en el mundo; (3) solo si apunta hacia el centro del vehículo se aplica un giro de 180°
 * alrededor del eje vertical que pasa por el centro de la rueda (rotación propia: det = +1, sin espejo, sin escalas negativas).
 * Si las señales no bastan, NO se toca nada (se respeta la matriz del DFF) y se informa.
 */
object WheelOrientation {
    /** Parte de la rueda: vértices usados de una geometría y, si existe, su textura (para las señales de material). */
    class Piece(val geometry: GeometryData, val indices: IntArray, val textureName: String?)

    class Analysis(
        /** Eje local de la rueda: 0 = X, 1 = Y, 2 = Z. */
        val axis: Int,
        /** Signo (+1/-1) del lado EXTERIOR a lo largo de [axis] en coordenadas locales. */
        val outerSign: Int,
        /** Centro de la rueda en coordenadas locales (centro de la caja de los vértices usados). */
        val center: FloatArray,
        val cues: List<String>,
    )

    class Outcome(val analysis: Analysis?, val reason: String)

    private val INNER = listOf("brake", "rotor", "caliper", "shadow", "disc", "drum")
    private val RIM = listOf("wheel", "rim", "alloy", "spoke")

    fun analyze(pieces: List<Piece>): Outcome {
        // --- vértices usados y caja
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var any = false
        for (p in pieces) for (i in p.indices) {
            val pos = p.geometry.positions
            for (c in 0..2) { val v = pos[i * 3 + c]; if (v < mn[c]) mn[c] = v; if (v > mx[c]) mx[c] = v }
            any = true
        }
        if (!any) return Outcome(null, "sin geometría")
        val ext = FloatArray(3) { mx[it] - mn[it] }
        val center = FloatArray(3) { (mn[it] + mx[it]) / 2 }
        val axis = (0..2).minByOrNull { ext[it] }!!
        val a = (axis + 1) % 3; val b = (axis + 2) % 3
        if (ext[axis] > 0.8f * min(ext[a], ext[b]) || ext[a] <= 1e-6f || ext[b] <= 1e-6f ||
            max(ext[a], ext[b]) / min(ext[a], ext[b]) > 1.33f
        ) return Outcome(null, "la geometría no parece una rueda (extensiones %.3f × %.3f × %.3f)".format(ext[0], ext[1], ext[2]))
        val half = ext[axis] / 2
        val radius = max(ext[a], ext[b]) / 2

        fun axial(g: GeometryData, v: Int) = g.positions[v * 3 + axis] - center[axis]
        fun radial(g: GeometryData, v: Int): Float {
            val x = g.positions[v * 3 + a] - center[a]; val y = g.positions[v * 3 + b] - center[b]
            return sqrt(x * x + y * y)
        }

        var posW = 0; var negW = 0
        val cues = ArrayList<String>()
        fun vote(name: String, signedScore: Int, weight: Int, detail: String) {
            if (signedScore > 0) posW += weight else if (signedScore < 0) negW += weight
            cues += "$name: ${if (signedScore > 0) "+" else if (signedScore < 0) "-" else "0"}$detail"
        }

        // --- señal M: posición axial media de los materiales de llanta frente a los de freno/sombra (interior)
        val rim = HashSet<Long>(); val inner = HashSet<Long>()
        var rimSum = 0.0; var innerSum = 0.0
        for (p in pieces) {
            val tn = p.textureName?.lowercase() ?: continue
            val isInner = INNER.any { it in tn }
            val isRim = !isInner && RIM.any { it in tn }
            if (!isInner && !isRim) continue
            for (i in p.indices) {
                val key = (p.geometry.id.toLong() shl 32) or i.toLong()
                if (isInner) { if (inner.add(key)) innerSum += axial(p.geometry, i) }
                else if (rim.add(key)) rimSum += axial(p.geometry, i)
            }
        }
        if (rim.size >= 12 && inner.size >= 12) {
            val d = (rimSum / rim.size - innerSum / inner.size).toFloat()
            if (abs(d) >= 0.1f * half) vote("materiales(llanta vs freno/sombra)", if (d > 0) 1 else -1, 2, " Δ=%.3f".format(d))
        }

        // --- señales geométricas sobre triángulos (sin nombres)
        var sumAx = 0.0; var nIn = 0
        val bins = IntArray(41); val binSum = DoubleArray(41)
        for (p in pieces) {
            val pos = p.geometry.positions; val idx = p.indices
            var t = 0
            while (t + 2 < idx.size) {
                val v0 = idx[t]; val v1 = idx[t + 1]; val v2 = idx[t + 2]; t += 3
                val cx = (axial(p.geometry, v0) + axial(p.geometry, v1) + axial(p.geometry, v2)) / 3
                val r = (radial(p.geometry, v0) + radial(p.geometry, v1) + radial(p.geometry, v2)) / 3
                if (r < 0.8f * radius) { sumAx += cx; nIn++ }
                // triángulos cuya normal es paralela al eje (caras planas de disco)
                val u = FloatArray(3) { pos[v1 * 3 + it] - pos[v0 * 3 + it] }
                val w = FloatArray(3) { pos[v2 * 3 + it] - pos[v0 * 3 + it] }
                val n = floatArrayOf(u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
                val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
                if (len > 1e-12f && abs(n[axis]) / len > 0.95f && r < 0.95f * radius) {
                    val bin = (((cx / half) * 0.5f + 0.5f) * 40f).toInt().coerceIn(0, 40)
                    bins[bin]++; binSum[bin] += cx.toDouble()
                }
            }
        }
        if (nIn >= 8) {
            val m = (sumAx / nIn).toFloat()
            if (abs(m) >= 0.12f * half) vote("media axial del interior de la rueda", if (m > 0) 1 else -1, 1, " m=%.3f".format(m))
        }
        val best = bins.indices.maxByOrNull { bins[it] }!!
        if (bins[best] >= 8) {
            val c = (binSum[best] / bins[best]).toFloat()
            if (abs(c) >= 0.4f * half) vote("plano dominante perpendicular al eje", if (c > 0) 1 else -1, 1, " x=%.3f (%d tri)".format(c, bins[best]))
        }

        val win = max(posW, negW); val lose = min(posW, negW)
        if (win < 2 || lose * 2 > win) return Outcome(null, "señales insuficientes o contradictorias (${cues.joinToString("; ").ifEmpty { "ninguna" }})")
        return Outcome(Analysis(axis, if (posW > negW) 1 else -1, center, cues), "ok")
    }

    /** Corrección en espacio LOCAL de la rueda: `transform' = transform × local`. */
    class Correction(val local: FloatArray, val note: String)

    class Decision(val correction: Correction?, val outwardWorldX: Float, val side: Int, val reason: String)

    /**
     * Decide si hace falta girar la rueda montada con la matriz mundo [world] (espacio GTA: +X derecha del vehículo).
     * [centerlineX] es la x de la línea central del vehículo (el origen de un vehículo GTA está sobre su eje longitudinal).
     */
    fun decide(an: Analysis, world: FloatArray, centerlineX: Float = 0f): Decision {
        val c = FloatArray(3); Mat4.transformPoint(world, an.center[0], an.center[1], an.center[2], c)
        val lateral = c[0] - centerlineX
        if (abs(lateral) < 0.15f) return Decision(null, 0f, 0, "rueda sobre la línea central (x=%.2f): no hay lado".format(lateral))
        val side = if (lateral > 0) 1 else -1
        val dir = FloatArray(3); dir[an.axis] = an.outerSign.toFloat()
        val w = FloatArray(3); Mat4.transformDir(world, dir[0], dir[1], dir[2], w)
        val len = Mat4.length(w[0], w[1], w[2]).let { if (it < 1e-9f) 1f else it }
        val ox = w[0] / len
        if (abs(ox) < 0.6f) return Decision(null, ox, side, "el eje de la rueda no es transversal al vehículo (cos=%.2f)".format(ox))
        if (ox * side > 0) return Decision(null, ox, side, "la cara exterior ya apunta hacia fuera")
        // El eje vertical local es el eje (distinto del de la rueda) que en el mundo está más cerca de la vertical GTA (Z).
        val others = (0..2).filter { it != an.axis }
        val upAxis = others.maxByOrNull { k -> val d = FloatArray(3); d[k] = 1f; val t = FloatArray(3); Mat4.transformDir(world, d[0], d[1], d[2], t); abs(t[2]) / Mat4.length(t[0], t[1], t[2]).coerceAtLeast(1e-9f) }!!
        val t = FloatArray(3); val d = FloatArray(3); d[upAxis] = 1f; Mat4.transformDir(world, d[0], d[1], d[2], t)
        if (abs(t[2]) / Mat4.length(t[0], t[1], t[2]).coerceAtLeast(1e-9f) < 0.6f)
            return Decision(null, ox, side, "no se encontró un eje vertical local para girar la rueda")
        return Decision(Correction(rotate180(upAxis, an.center), "cara exterior hacia el interior: giro de 180° sobre el eje vertical local ${"XYZ"[upAxis]}"), ox, side, "corregida")
    }

    /** Rotación de 180° alrededor del eje local [k] que pasa por [pivot]: R = 2·uuᵀ − I, t = p − R·p. */
    fun rotate180(k: Int, pivot: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = (if (i == k && j == k) 2f else 0f) - (if (i == j) 1f else 0f)
        val m = Mat4.identity()
        for (col in 0..2) for (row in 0..2) m[col * 4 + row] = r[row * 3 + col]
        for (row in 0..2) m[12 + row] = pivot[row] - (r[row * 3] * pivot[0] + r[row * 3 + 1] * pivot[1] + r[row * 3 + 2] * pivot[2])
        return m
    }
}
