package com.webmaletin.gtaviewer.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "DFF cargado" no garantiza un modelo correcto: se validan caja, escala, contadores, transformaciones y UV.
 * No modifica el modelo; solo informa y propone encuadre/normalización (acciones del usuario).
 */
object ModelValidator {
    fun validate(parts: List<MeshPart>, bounds: (MeshPart) -> Bounds): ValidationReport {
        val reasons = ArrayList<String>()
        val shown = parts.filter { it.defaultVisible }.ifEmpty { parts }
        val tris = shown.sumOf { p -> p.subMeshes.sumOf { it.triangleCount } }
        if (shown.isEmpty() || tris == 0) {
            return ValidationReport(true, listOf("No se encontró geometría renderizable (0 triángulos)"), null, 1f)
        }
        if (shown.any { !Mat4.isFinite(it.transform) }) reasons += "Hay transformaciones con valores inválidos (NaN/infinito)"

        val boxes = shown.map { it to bounds(it) }.filter { it.second.isValid }
        if (boxes.isEmpty()) return ValidationReport(true, reasons + "Ninguna pieza tiene una caja envolvente válida", null, 1f)

        // Centro mediano y piezas alejadas
        fun median(v: List<Float>) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val centers = boxes.map { it.second.center }
        val med = floatArrayOf(median(centers.map { it[0] }), median(centers.map { it[1] }), median(centers.map { it[2] }))
        val robust = Bounds.empty()
        val outliers = ArrayList<String>()
        for ((p, b) in boxes) {
            val c = b.center
            val d = sqrt((c[0] - med[0]).let { it * it } + (c[1] - med[1]).let { it * it } + (c[2] - med[2]).let { it * it })
            val far = d > OUTLIER_DISTANCE && d > 6 * max(b.radius, 0.5f)
            // Una pieza grande y alejada no se descarta si no hay una masa clara de piezas cercanas
            if (far && boxes.size >= 3) outliers += p.name else robust.expand(b)
        }
        if (!robust.isValid) robust.expand(boxes.first().second)
        if (outliers.isNotEmpty()) reasons += "${outliers.size} pieza(s) muy lejos del resto (${outliers.take(3).joinToString()}${if (outliers.size > 3) "…" else ""})"

        val full = Bounds.empty().also { e -> boxes.forEach { e.expand(it.second) } }
        val fullMax = full.size.max()
        val robustMax = robust.size.max()
        if (fullMax > ABSURD_SIZE) reasons += "Caja envolvente gigantesca (%.1f m)".format(fullMax)
        if (fullMax < TINY_SIZE) reasons += "Caja envolvente diminuta (%.3f m): posible escala incorrecta".format(fullMax)
        else if (robustMax < 1.0f || robustMax > 40f) reasons += "Tamaño inusual para un vehículo (%.2f m): revise la escala".format(robustMax)

        // UV absurdas
        var badUv = false
        for (p in shown) for (sm in p.subMeshes) {
            val uv = sm.geometry.uvs
            var i = 0
            while (i < uv.size) { if (!uv[i].isFinite() || abs(uv[i]) > 1000f) { badUv = true; break }; i += 97 }
            if (badUv) break
        }
        if (badUv) reasons += "Coordenadas UV fuera de rango razonable"

        val dim = if (robustMax > 0f && robustMax.isFinite()) robustMax else 1f
        val suggested = if (dim in 2f..25f) 1f else (TARGET_LENGTH / dim)
        return ValidationReport(reasons.isNotEmpty(), reasons, robust, suggested)
    }

    const val OUTLIER_DISTANCE = 25f
    const val ABSURD_SIZE = 80f
    const val TINY_SIZE = 0.1f
    const val TARGET_LENGTH = 4.5f
}
