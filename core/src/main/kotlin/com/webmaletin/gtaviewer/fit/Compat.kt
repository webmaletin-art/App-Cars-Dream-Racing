package com.webmaletin.gtaviewer.fit

import com.webmaletin.gtaviewer.classify.VehicleTypes
import com.webmaletin.gtaviewer.library.*
import com.webmaletin.gtaviewer.model.PartCategory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class CompatLevel(val label: String, val rank: Int) {
    COMPATIBLE("Compatible", 3), ADJUST("Compatible con ajustes", 2), DOUBTFUL("Compatibilidad dudosa", 1), INCOMPATIBLE("Incompatible", 0),
}

class BasePart(val name: String, val category: PartCategory, val min: FloatArray, val max: FloatArray, val side: String?, val longitudinal: String?) {
    val size get() = floatArrayOf(max[0] - min[0], max[1] - min[1], max[2] - min[2])
    val center get() = floatArrayOf((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2)
}

/** Lo que se sabe de la carrocería base (solo metadatos: no hace falta cargar geometría para evaluar). */
class BaseInfo(val id: String, val dims: Dims, val center: FloatArray, val types: List<String>, val parts: List<BasePart>, val wheelAnchors: List<Anchor>)

class CompatResult(
    val level: CompatLevel, val score: Float, val reasons: List<String>,
    /** Escala uniforme sugerida (≈1 si no hace falta). */
    val scale: Float,
    /** Desplazamiento sugerido en espacio de visor (aplicado tras escalar alrededor del centro de la pieza). */
    val deltaView: FloatArray,
    /** Parte de la base que reemplazaría (misma categoría y posición). */
    val replaces: String?,
    val learned: Boolean = false,
)

object FitPlanner {
    private fun relevantAxes(c: PartCategory): List<Int> = when (c) {      // 0 = ancho (X), 1 = alto (Y), 2 = largo (Z)
        PartCategory.BUMPER, PartCategory.GRILLE, PartCategory.SPOILER, PartCategory.INTAKE, PartCategory.LIGHT, PartCategory.EXHAUST -> listOf(0)
        PartCategory.HOOD, PartCategory.TRUNK, PartCategory.ROOF -> listOf(0, 2)
        PartCategory.DOOR, PartCategory.FENDER, PartCategory.SKIRT -> listOf(2, 1)
        PartCategory.MIRROR -> listOf(0)
        else -> listOf(0, 1, 2)
    }

    fun baseInfo(store: LibraryStore, vehicleId: String): BaseInfo? {
        val meta = store.get(vehicleId) ?: return null
        val man = store.manifestOf(vehicleId) ?: return null
        val parts = man.parts.filter { it.bounds.size == 6 }.map { sp ->
            val mn = floatArrayOf(sp.bounds[0], sp.bounds[1], sp.bounds[2]); val mx = floatArrayOf(sp.bounds[3], sp.bounds[4], sp.bounds[5])
            val cx = (mn[0] + mx[0]) / 2; val cz = (mn[2] + mx[2]) / 2
            BasePart(sp.name, PartCategory.fromName(sp.category) ?: PartCategory.OTHER, mn, mx,
                sp.wheelSlot?.let { if (it.endsWith("L")) "L" else "R" } ?: if (abs(cx) > 0.25f) (if (cx > 0) "L" else "R") else null,
                sp.wheelSlot?.let { if (it.startsWith("F")) "FRONT" else "REAR" } ?: if (abs(cz - meta.center[2]) > 0.4f) (if (cz > meta.center[2]) "FRONT" else "REAR") else null)
        }
        return BaseInfo(vehicleId, meta.dims, meta.center.toFloatArray(), meta.types.map { it.label }, parts, meta.anchors.filter { it.name.startsWith("rueda") })
    }

    private val FAMILY = mapOf(
        VehicleTypes.TRUCK to "pesado", VehicleTypes.VAN to "utilitario", VehicleTypes.PICKUP to "utilitario", VehicleTypes.SUV to "todoterreno", VehicleTypes.OFFROAD to "todoterreno",
        VehicleTypes.SPORTS to "deportivo", VehicleTypes.SUPERCAR to "deportivo", VehicleTypes.MUSCLE to "deportivo", VehicleTypes.COUPE to "turismo", VehicleTypes.SEDAN to "turismo",
        VehicleTypes.HATCHBACK to "turismo", VehicleTypes.COMPACT to "turismo",
    )

    /**
     * Evalúa si [piece] (de [source]) encaja en [base]. No bloquea combinaciones de modelos distintos: clasifica y explica.
     * Nunca asume compatibilidad por el nombre: se miden proporciones, dimensiones y la región que ocuparía.
     */
    fun evaluate(base: BaseInfo, piece: PieceMeta, source: PieceMeta?, learned: List<LearnedFit> = emptyList()): CompatResult {
        val cat = PartCategory.fromName(piece.category) ?: PartCategory.OTHER
        val reasons = ArrayList<String>()
        learned.firstOrNull { it.baseId == base.id && it.pieceId == piece.id }?.let { l ->
            reasons += "Ajuste confirmado antes por el usuario (${l.count}×): escala %.2f".format(l.scale)
            return CompatResult(CompatLevel.COMPATIBLE, 0.95f, reasons, l.scale, l.deltaView.toFloatArray(), null, true)
        }
        val src = source?.dims ?: base.dims
        val sc = source?.center?.toFloatArray() ?: base.center
        if (source == null) reasons += "Se desconoce el vehículo de origen: se asume del mismo tamaño que la base"
        val ratios = floatArrayOf(base.dims.w / max(src.w, 0.05f), base.dims.h / max(src.h, 0.05f), base.dims.l / max(src.l, 0.05f))
        val axes = relevantAxes(cat)
        val pieceSize = floatArrayOf(piece.dims.w, piece.dims.h, piece.dims.l)

        // Región de destino: pieza de la base de la misma categoría (y mismo lado/longitud si se conocen)
        val region = base.parts.filter { it.category == cat && (piece.side == null || it.side == null || it.side == piece.side) &&
            (piece.longitudinal == null || it.longitudinal == null || it.longitudinal == piece.longitudinal) }
            .minByOrNull { r -> (0..2).sumOf { abs((r.center[it] - (base.center[it] + (piece.center[it] - sc[it]) * ratios[it])).toDouble()) } }

        var scale = 1f
        if (cat == PartCategory.WHEEL) {
            val bw = base.parts.filter { it.category == PartCategory.WHEEL }
            if (bw.isNotEmpty()) { val bd = bw.map { it.size[1] }.average().toFloat(); scale = bd / max(piece.dims.h, 0.05f); reasons += "Diámetro de rueda %.2f m → %.2f m en la base".format(piece.dims.h, bd) }
            else reasons += "La base no tiene ruedas identificadas: no se puede medir el diámetro"
        } else {
            val g = axes.map { ratios[it] }.fold(0.0) { a, r -> a + ln(r.toDouble()) } / axes.size
            scale = exp(g).toFloat().coerceIn(0.8f, 1.25f)
        }
        val residual = if (cat == PartCategory.WHEEL) 0f else axes.maxOf { abs(ratios[it] / scale - 1f) }
        var regionMismatch = 0f
        if (region != null) {
            regionMismatch = axes.maxOf { abs(pieceSize[it] * scale / max(region.size[it], 0.02f) - 1f) }.coerceAtMost(2f)
            reasons += "Ocuparía el lugar de '${region.name}' (dif. de tamaño %.0f %%)".format(regionMismatch * 100)
        } else reasons += "La base no tiene una pieza de esta categoría: se colocaría según su posición relativa en el vehículo de origen"

        var penalty = 1.2f * abs(ln(scale)) + 1.5f * residual + 0.9f * min(regionMismatch, 1f)
        if (abs(scale - 1f) > 0.05f) reasons += "Requiere escalar ×%.2f (proporciones de origen distintas)".format(scale)
        if (residual > 0.1f) reasons += "Las proporciones del vehículo de origen difieren de la base en %.0f %% (escala no uniforme)".format(residual * 100)
        // familia de vehículo
        val bf = base.types.mapNotNull { FAMILY[it] }.toSet(); val sf = (source?.types?.map { it.label } ?: emptyList()).mapNotNull { FAMILY[it] }.toSet()
        if (bf.isNotEmpty() && sf.isNotEmpty()) {
            if (bf.intersect(sf).isEmpty()) { penalty += 0.25f; reasons += "Procede de otra familia de vehículo (${sf.joinToString()} frente a ${bf.joinToString()})" }
            else { penalty -= 0.05f; reasons += "Misma familia de vehículo (${bf.intersect(sf).joinToString()})" }
        }
        if (cat == PartCategory.WHEEL && base.wheelAnchors.isEmpty()) { penalty += 0.1f; reasons += "Sin anclajes de rueda en la base: se usarán las posiciones detectadas" }
        val score = (1f - penalty).coerceIn(0f, 1f)
        val level = when {
            score >= 0.88f && abs(scale - 1f) < 0.05f -> CompatLevel.COMPATIBLE
            score >= 0.7f -> CompatLevel.ADJUST
            score >= 0.45f -> CompatLevel.DOUBTFUL
            else -> CompatLevel.INCOMPATIBLE
        }
        // desplazamiento: hacia el centro de la región o, si no hay, hacia la posición relativa equivalente
        val target = region?.center ?: FloatArray(3) { base.center[it] + (piece.center[it] - sc[it]) * ratios[it] }
        val delta = FloatArray(3) { target[it] - piece.center[it] }
        return CompatResult(level, score, reasons, scale, delta, region?.name)
    }
}
